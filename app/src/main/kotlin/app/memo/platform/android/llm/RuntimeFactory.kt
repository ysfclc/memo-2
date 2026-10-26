package app.memo.platform.android.llm

import android.content.Context
import app.memo.core.platform.LlmRuntime
import app.memo.core.platform.ModelFormat
import app.memo.platform.android.llm.litert.LiteRtLmRuntime
import app.memo.platform.android.llm.llama.LlamaCppRuntime
import java.io.File

/**
 * Runtime selection by model format, for [app.memo.runtime.InferenceManager]. The
 * manager calls this once per format, on its worker thread.
 */
fun androidRuntimeFactory(context: Context): (ModelFormat) -> LlmRuntime {
    val app = context.applicationContext
    return { format ->
        when (format) {
            ModelFormat.LITERTLM -> LiteRtLmRuntime(
                cacheDir = File(app.cacheDir, "litert"),
                availableRam = { availableRamBytes(app) },
            )
            ModelFormat.GGUF -> LlamaCppRuntime(availableRam = { availableRamBytes(app) })
        }
    }
}
