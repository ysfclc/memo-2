package app.memo.core.codec

import app.memo.core.util.Sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Deterministic JSON serialization: object keys sorted, numbers written
 * plainly, strings escaped via org.json, doubles through `Double.toString`
 * (deterministic by JVM spec) so the same logical value always hashes the
 * same. Everything that feeds a SHA-256 — registry content signatures,
 * audit chain links, approval content hashes — MUST go through here.
 */
object CanonicalJson {

    fun canonical(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    /** SHA-256 hex of the canonical form. */
    fun contentHash(value: Any?): String = Sha256Hex.hex(canonical(value))

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            // org.json's explicit-null sentinel (put(key, null) would REMOVE the key).
            JSONObject.NULL -> sb.append("null")
            is JSONObject -> {
                val keys = value.keySet().sorted()
                sb.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    sb.append(JSONObject.quote(key)).append(':')
                    write(value.opt(key), sb)
                }
                sb.append('}')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until value.length()) {
                    if (i > 0) sb.append(',')
                    write(value.opt(i), sb)
                }
                sb.append(']')
            }
            is String -> sb.append(JSONObject.quote(value))
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> sb.append(plain(value))
            is Float -> sb.append(plain(value.toDouble()))
            // JSONObject.NULL and anything else falls back to its string form.
            else -> sb.append(JSONObject.quote(value.toString()))
        }
    }

    private fun plain(text: Double): String {
        require(abs(text).isFinite()) { "Non-finite numbers cannot be canonicalized" }
        return BigDecimal.valueOf(text).stripTrailingZeros().toPlainString()
    }
}
