package app.memo.platform.android.llm.llama

import java.io.File
import java.io.IOException

/** Generic arm64 build (ARMv8.0 baseline), and the only build on other ABIs. */
const val LLAMA_LIB_GENERIC = "memo_llama"

/** arm64 build with dotprod and fp16 (ARMv8.2-A). Loaded only when the CPU has both. */
const val LLAMA_LIB_DOTPROD = "memo_llama_dp"

/** CPU feature tokens (from the /proc/cpuinfo `Features` line) that the dotprod build needs. */
private val DOTPROD_TOKENS = setOf("asimddp", "fphp")

/**
 * Chooses the native library to load. The dotprod build is used only on arm64 and only when the
 * CPU's `Features` line lists `asimddp` (dot product) and `fphp` (scalar fp16). Every other case
 * gets the generic build, including a missing or unreadable `Features` line.
 *
 * @param isArm64 true when the primary ABI is arm64-v8a.
 * @param features the value of the `Features` line of /proc/cpuinfo, without the key, or null.
 */
fun selectLlamaLibrary(isArm64: Boolean, features: String?): String {
    if (!isArm64 || features == null) return LLAMA_LIB_GENERIC
    val tokens = features.split(' ', '\t').filter { it.isNotEmpty() }.toSet()
    return if (tokens.containsAll(DOTPROD_TOKENS)) LLAMA_LIB_DOTPROD else LLAMA_LIB_GENERIC
}

/**
 * Reads the value of the first `Features` line in /proc/cpuinfo. Returns null if the file is
 * missing or unreadable, so the caller falls back to the generic build.
 */
fun readCpuFeatures(file: File = File("/proc/cpuinfo")): String? = try {
    file.useLines { lines ->
        lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':', missingDelimiterValue = "")
    }
} catch (e: IOException) {
    null
} catch (e: SecurityException) {
    null
}
