#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "llama.h"

// JNI bridge for app.memo.platform.android.llm.llama.LlamaNative.
// Every llama.cpp function used here is declared in include/llama.h at tag b11460.
//
// Error contract: failures are thrown to Java. Out-of-memory becomes
// java.lang.OutOfMemoryError. Everything else becomes
// app.memo.platform.android.llm.llama.LlamaNativeException whose message starts
// with a kind code: "LOAD:", "CTX:", "GEN:" or "UNSUPPORTED:". Messages are
// fixed text and never contain prompt or output text.

namespace {

constexpr const char *kExceptionClass = "app/memo/platform/android/llm/llama/LlamaNativeException";
constexpr int32_t kPrefillChunk = 512;
constexpr int32_t kPenaltyLastN = 64;
constexpr float kRepeatPenalty = 1.1f;
constexpr float kMinP = 0.05f;

std::once_flag g_backend_once;

struct MemoLlama {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    // Chat template copied from GGUF metadata. Empty means "no template".
    std::string chat_template;
    std::atomic<bool> cancel{false};
};

void throw_llama(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass(kExceptionClass);
    if (cls == nullptr) {
        return; // FindClass already left a pending NoClassDefFoundError
    }
    env->ThrowNew(cls, msg);
    env->DeleteLocalRef(cls);
}

void throw_oom(JNIEnv *env) {
    jclass cls = env->FindClass("java/lang/OutOfMemoryError");
    if (cls != nullptr) {
        env->ThrowNew(cls, "native allocation failed");
        env->DeleteLocalRef(cls);
    }
}

// Java String -> UTF-8 bytes via String.getBytes("UTF-8"). NewStringUTF is not
// used because it takes modified UTF-8, which differs for supplementary characters.
bool jstring_to_utf8(JNIEnv *env, jstring js, std::string &out) {
    if (js == nullptr) {
        out.clear();
        return true;
    }
    jclass str_cls = env->FindClass("java/lang/String");
    jmethodID get_bytes = env->GetMethodID(str_cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    jbyteArray arr = static_cast<jbyteArray>(env->CallObjectMethod(js, get_bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(str_cls);
    if (arr == nullptr) {
        return false; // exception pending
    }
    jsize len = env->GetArrayLength(arr);
    out.resize(static_cast<size_t>(len));
    if (len > 0) {
        env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    }
    env->DeleteLocalRef(arr);
    return true;
}

// UTF-8 bytes -> Java String via new String(bytes, "UTF-8"). Invalid sequences
// become U+FFFD, so model output can never produce a malformed Java string.
jstring utf8_to_jstring(JNIEnv *env, const std::string &bytes) {
    jclass str_cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str_cls, "<init>", "([BLjava/lang/String;)V");
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (arr == nullptr) {
        env->DeleteLocalRef(str_cls);
        return nullptr;
    }
    if (!bytes.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte *>(bytes.data()));
    }
    jstring charset = env->NewStringUTF("UTF-8");
    jobject s = env->NewObject(str_cls, ctor, arr, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(str_cls);
    return static_cast<jstring>(s);
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 code point.
size_t complete_utf8_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    // Look back at most 3 bytes for a lead byte whose sequence is still incomplete.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        unsigned char c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back < need ? n - back : n;
    }
    return n;
}

struct SinkRef {
    JNIEnv *env;
    jobject sink;
    jmethodID on_piece;
};

// Calls LlamaTokenSink.onPiece(String): Boolean. Returns false to stop generation.
// A pending Java exception also returns false; the caller checks ExceptionCheck().
bool emit_piece(const SinkRef &ref, const std::string &bytes) {
    if (bytes.empty()) return true;
    JNIEnv *env = ref.env;
    jstring js = utf8_to_jstring(env, bytes);
    if (js == nullptr) return false;
    jboolean keep = env->CallBooleanMethod(ref.sink, ref.on_piece, js);
    env->DeleteLocalRef(js);
    return keep == JNI_TRUE && !env->ExceptionCheck();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeVersion(JNIEnv *env, jobject /* thiz */) {
    std::string info = std::string("llama.cpp b11460 | ") + llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeInitBackend(JNIEnv * /* env */, jobject /* thiz */) {
    std::call_once(g_backend_once, []() { llama_backend_init(); });
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeLoad(
        JNIEnv *env, jobject /* thiz */, jstring jpath, jint n_ctx_requested, jint n_threads) {
    try {
        std::call_once(g_backend_once, []() { llama_backend_init(); });

        std::string path;
        if (!jstring_to_utf8(env, jpath, path)) return 0;
        if (path.empty()) {
            throw_llama(env, "LOAD: model path is empty");
            return 0;
        }

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;                     // CPU only
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;     // memory-map the GGUF file
        llama_model *model = llama_model_load_from_file(path.c_str(), mp);
        if (model == nullptr) {
            throw_llama(env, "LOAD: model file could not be opened or parsed");
            return 0;
        }

        // Clamp the requested context to what the model was trained with.
        uint32_t n_ctx = static_cast<uint32_t>(std::max(256, static_cast<int>(n_ctx_requested)));
        int32_t n_ctx_train = llama_model_n_ctx_train(model);
        if (n_ctx_train > 0 && n_ctx > static_cast<uint32_t>(n_ctx_train)) {
            n_ctx = static_cast<uint32_t>(n_ctx_train);
        }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = n_ctx;
        cp.n_batch = std::min<uint32_t>(n_ctx, 2048);
        cp.n_threads = std::max(1, static_cast<int>(n_threads));
        cp.n_threads_batch = cp.n_threads;
        llama_context *ctx = llama_init_from_model(model, cp);
        if (ctx == nullptr) {
            llama_model_free(model);
            throw_llama(env, "LOAD: context could not be created");
            return 0;
        }

        auto *m = new (std::nothrow) MemoLlama();
        if (m == nullptr) {
            llama_free(ctx);
            llama_model_free(model);
            throw_oom(env);
            return 0;
        }
        m->model = model;
        m->ctx = ctx;
        m->vocab = llama_model_get_vocab(model);

        const char *tmpl = llama_model_chat_template(model, nullptr);
        if (tmpl != nullptr) {
            m->chat_template = tmpl;
        }
        return reinterpret_cast<jlong>(m);
    } catch (const std::bad_alloc &) {
        throw_oom(env);
    } catch (const std::exception &) {
        throw_llama(env, "LOAD: unexpected native failure");
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeFree(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *m = reinterpret_cast<MemoLlama *>(handle);
    if (m == nullptr) return;
    if (m->ctx != nullptr) llama_free(m->ctx);
    if (m->model != nullptr) llama_model_free(m->model);
    delete m;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeContextSize(JNIEnv *env, jobject /* thiz */, jlong handle) {
    auto *m = reinterpret_cast<MemoLlama *>(handle);
    if (m == nullptr || m->ctx == nullptr) {
        throw_llama(env, "GEN: no model is loaded");
        return 0;
    }
    return static_cast<jint>(llama_n_ctx(m->ctx));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeHasChatTemplate(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *m = reinterpret_cast<MemoLlama *>(handle);
    return (m != nullptr && !m->chat_template.empty()) ? JNI_TRUE : JNI_FALSE;
}

// Renders turns with the GGUF chat template. Returns null when the model has none.
extern "C" JNIEXPORT jstring JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeApplyChatTemplate(
        JNIEnv *env, jobject /* thiz */, jlong handle, jobjectArray jroles, jobjectArray jcontents,
        jboolean add_assistant) {
    try {
        auto *m = reinterpret_cast<MemoLlama *>(handle);
        if (m == nullptr) {
            throw_llama(env, "GEN: no model is loaded");
            return nullptr;
        }
        if (m->chat_template.empty()) return nullptr;

        jsize n = env->GetArrayLength(jroles);
        if (env->GetArrayLength(jcontents) != n) {
            throw_llama(env, "GEN: role and content arrays differ in length");
            return nullptr;
        }
        std::vector<std::string> roles(static_cast<size_t>(n));
        std::vector<std::string> contents(static_cast<size_t>(n));
        std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
        size_t total = 0;
        for (jsize i = 0; i < n; ++i) {
            auto jr = static_cast<jstring>(env->GetObjectArrayElement(jroles, i));
            auto jc = static_cast<jstring>(env->GetObjectArrayElement(jcontents, i));
            bool ok = jstring_to_utf8(env, jr, roles[i]) && jstring_to_utf8(env, jc, contents[i]);
            env->DeleteLocalRef(jr);
            env->DeleteLocalRef(jc);
            if (!ok) return nullptr;
            msgs[i].role = roles[i].c_str();
            msgs[i].content = contents[i].c_str();
            total += roles[i].size() + contents[i].size();
        }

        std::string buf(total * 2 + 1024, '\0');
        int32_t written = llama_chat_apply_template(
                m->chat_template.c_str(), msgs.data(), msgs.size(), add_assistant == JNI_TRUE,
                &buf[0], static_cast<int32_t>(buf.size()));
        if (written > static_cast<int32_t>(buf.size())) {
            buf.assign(static_cast<size_t>(written), '\0');
            written = llama_chat_apply_template(
                    m->chat_template.c_str(), msgs.data(), msgs.size(), add_assistant == JNI_TRUE,
                    &buf[0], static_cast<int32_t>(buf.size()));
        }
        if (written < 0) {
            throw_llama(env, "GEN: chat template could not be applied");
            return nullptr;
        }
        buf.resize(static_cast<size_t>(written));
        return utf8_to_jstring(env, buf);
    } catch (const std::bad_alloc &) {
        throw_oom(env);
    } catch (const std::exception &) {
        throw_llama(env, "GEN: unexpected native failure");
    }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeCountTokens(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jprompt, jboolean add_special) {
    try {
        auto *m = reinterpret_cast<MemoLlama *>(handle);
        if (m == nullptr) {
            throw_llama(env, "GEN: no model is loaded");
            return 0;
        }
        std::string text;
        if (!jstring_to_utf8(env, jprompt, text)) return 0;
        int32_t n = llama_tokenize(m->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                   nullptr, 0, add_special == JNI_TRUE, true);
        return static_cast<jint>(n < 0 ? -n : n);
    } catch (const std::bad_alloc &) {
        throw_oom(env);
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeCancel(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *m = reinterpret_cast<MemoLlama *>(handle);
    if (m != nullptr) m->cancel.store(true);
}

// Runs one generation. Calls sink.onPiece for each complete UTF-8 chunk and stops
// when it returns false, when cancelled, at end of generation, or at max_tokens.
// Returns the number of tokens sampled.
extern "C" JNIEXPORT jint JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring jprompt, jboolean add_special,
        jfloat temperature, jint top_k, jfloat top_p, jint seed, jint max_tokens, jobject sink) {
    try {
        auto *m = reinterpret_cast<MemoLlama *>(handle);
        if (m == nullptr || m->ctx == nullptr) {
            throw_llama(env, "GEN: no model is loaded");
            return 0;
        }
        if (max_tokens <= 0) {
            throw_llama(env, "UNSUPPORTED: max_tokens must be positive");
            return 0;
        }

        std::string text;
        if (!jstring_to_utf8(env, jprompt, text)) return 0;

        // Tokenise: a first call with no buffer returns the negated token count.
        int32_t need = llama_tokenize(m->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                      nullptr, 0, add_special == JNI_TRUE, true);
        if (need == INT32_MIN) {
            throw_llama(env, "GEN: prompt is too large to tokenise");
            return 0;
        }
        std::vector<llama_token> tokens(static_cast<size_t>(need < 0 ? -need : need));
        int32_t n_prompt = llama_tokenize(m->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                          tokens.data(), static_cast<int32_t>(tokens.size()),
                                          add_special == JNI_TRUE, true);
        if (n_prompt < 0) {
            throw_llama(env, "GEN: prompt could not be tokenised");
            return 0;
        }
        tokens.resize(static_cast<size_t>(n_prompt));

        const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(m->ctx));
        if (static_cast<int64_t>(n_prompt) + max_tokens > n_ctx) {
            throw_llama(env, "CTX: prompt and output do not fit the context window");
            return 0;
        }

        // Each request starts from an empty KV cache. Conversations are re-sent in full.
        llama_memory_clear(llama_get_memory(m->ctx), true);
        m->cancel.store(false);

        // Sampler chain: repeat penalty, then top-k, top-p, min-p, temperature, and a
        // seeded draw. Temperature 0 uses greedy decoding.
        llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (chain == nullptr) {
            throw_oom(env);
            return 0;
        }
        const int32_t n_vocab = llama_vocab_n_tokens(m->vocab);
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                n_vocab, kPenaltyLastN, kRepeatPenalty, 0.0f, 0.0f));
        if (temperature <= 0.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(chain, llama_sampler_init_min_p(kMinP, 1));
            llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
        }

        // Frees the sampler chain on every return path below.
        struct ChainGuard {
            llama_sampler *s;
            ~ChainGuard() { llama_sampler_free(s); }
        } guard{chain};

        // Prefill in chunks no larger than the batch size.
        for (size_t off = 0; off < tokens.size(); off += kPrefillChunk) {
            int32_t cnt = static_cast<int32_t>(std::min<size_t>(kPrefillChunk, tokens.size() - off));
            llama_batch batch = llama_batch_get_one(tokens.data() + off, cnt);
            if (llama_decode(m->ctx, batch) != 0) {
                throw_llama(env, "GEN: prompt decoding failed");
                return 0;
            }
        }

        jclass sink_cls = env->GetObjectClass(sink);
        SinkRef ref{env, sink, env->GetMethodID(sink_cls, "onPiece", "(Ljava/lang/String;)Z")};
        env->DeleteLocalRef(sink_cls);
        if (ref.on_piece == nullptr) {
            return 0; // NoSuchMethodError is already pending
        }

        std::string pending;
        int32_t sampled = 0;
        bool keep_going = true;
        while (keep_going && sampled < max_tokens && !m->cancel.load()) {
            llama_token id = llama_sampler_sample(chain, m->ctx, -1);
            if (llama_vocab_is_eog(m->vocab, id)) break;
            ++sampled;

            char small[256];
            int32_t len = llama_token_to_piece(m->vocab, id, small, sizeof(small), 0, false);
            if (len < 0) {
                std::vector<char> big(static_cast<size_t>(-len));
                len = llama_token_to_piece(m->vocab, id, big.data(), static_cast<int32_t>(big.size()), 0, false);
                if (len < 0) {
                    throw_llama(env, "GEN: token could not be converted to text");
                    return 0;
                }
                pending.append(big.data(), static_cast<size_t>(len));
            } else {
                pending.append(small, static_cast<size_t>(len));
            }

            size_t cut = complete_utf8_prefix(pending);
            if (cut > 0) {
                std::string out = pending.substr(0, cut);
                pending.erase(0, cut);
                keep_going = emit_piece(ref, out);
                if (env->ExceptionCheck()) return 0; // Java exception from the sink propagates
            }
            if (!keep_going) break;

            llama_batch batch = llama_batch_get_one(&id, 1);
            if (llama_decode(m->ctx, batch) != 0) {
                throw_llama(env, "GEN: token decoding failed");
                return 0;
            }
        }

        if (keep_going && !pending.empty()) {
            emit_piece(ref, pending);
            if (env->ExceptionCheck()) return 0;
        }
        return static_cast<jint>(sampled);
    } catch (const std::bad_alloc &) {
        throw_oom(env);
    } catch (const std::exception &) {
        throw_llama(env, "GEN: unexpected native failure");
    }
    return 0;
}
