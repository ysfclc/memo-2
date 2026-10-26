package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONObject

/** What kind of backing an [Evidence] record carries. */
enum class EvidenceKind { QUOTE, MEASURE, OBSERVATION }

/**
 * Verbatim backing for a step conclusion. A QUOTE must be contained
 * (whitespace-normalized) in the source it claims; containment is enforced
 * by `app.memo.core.plan.OutcomeParser` before acceptance, never stored
 * on blind trust.
 */
data class Evidence(
    val kind: EvidenceKind = EvidenceKind.QUOTE,
    val quote: String = "",
    val source: String = "",
    val capturedAt: Long = 0,
) {
    /** True when the record violates caps or is empty; such records are quarantined, not crashlogged. */
    fun isInvalid(): Boolean =
        quote.isBlank() ||
            quote.length > Limits.MAX_EVIDENCE_CHARS ||
            source.length > Limits.MAX_METRIC_TARGET_CHARS

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("quote", quote)
        .put("source", source)
        .put("capturedAt", capturedAt)

    companion object {
        fun fromJson(raw: JSONObject): Evidence {
            val kind = runCatching {
                EvidenceKind.valueOf(raw.optString("kind", "OBSERVATION"))
            }.getOrDefault(EvidenceKind.OBSERVATION)
            return Evidence(
                kind = kind,
                // Frontier/OCR text is preserved verbatim (smart quotes, dashes stay).
                quote = raw.optString("quote"),
                source = raw.optString("source"),
                capturedAt = raw.optLong("capturedAt", 0L),
            )
        }
    }
}
