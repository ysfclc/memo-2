#include <jni.h>
#include <mutex>
#include <string>

#include "llama.h"

// JNI entry points for app.memo.platform.android.llm.llama.LlamaNative.
// Only functions present in llama.cpp b11460 include/llama.h are used.

namespace {
std::once_flag g_backend_once;
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeVersion(JNIEnv *env, jobject /* thiz */) {
    std::string info = std::string("llama.cpp b11460 | ") + llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_app_memo_platform_android_llm_llama_LlamaNative_nativeInitBackend(JNIEnv * /* env */, jobject /* thiz */) {
    std::call_once(g_backend_once, []() { llama_backend_init(); });
}
