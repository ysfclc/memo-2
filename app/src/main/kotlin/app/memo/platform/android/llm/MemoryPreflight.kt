package app.memo.platform.android.llm

import android.app.ActivityManager
import android.content.Context

/** Shared limits for on-device runtimes. */
object LlmDefaults {
    /**
     * Context window requested from both runtimes. [app.memo.core.platform.ModelRef]
     * carries no context size, so this is fixed here and reported by `info()`.
     */
    const val CONTEXT_TOKENS = 4096

    private const val MIB = 1L shl 20

    /** Headroom kept free on top of the model bytes for the KV cache and the OS. */
    const val RAM_HEADROOM_BYTES = 768 * MIB
}

/**
 * Pure RAM check run before a model load. A model of [modelBytes] needs its
 * own size with 30% extra for runtime buffers, plus fixed headroom. An unknown
 * size (0) is checked against the headroom alone.
 */
fun hasRamHeadroom(availableBytes: Long, modelBytes: Long): Boolean {
    require(modelBytes >= 0) { "modelBytes must be >= 0" }
    val needed = modelBytes + modelBytes * 3 / 10 + LlmDefaults.RAM_HEADROOM_BYTES
    return availableBytes >= needed
}

/** Reads the currently available system memory with [ActivityManager.MemoryInfo]. */
fun availableRamBytes(context: Context): Long {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return if (info.lowMemory) 0L else info.availMem
}
