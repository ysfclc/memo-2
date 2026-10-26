package app.memo.core.plan

import app.memo.core.Limits
import app.memo.core.domain.Evidence
import app.memo.core.domain.EvidenceKind
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Outcome law (Fieldwork/Threadline inheritance): a claim is accepted only
 * when backed by verbatim containment in the source text. Unbacked claims
 * become evidence-less output that downstream gates treat as suspect —
 * never silently "verified".
 */
object OutcomeParser {

    /** A number captured from page text; `approximated` flags k/M/B short-forms. */
    data class ParsedNumber(val value: Long, val approximated: Boolean)

    /**
     * Whitespace-normalized, case-SENSITIVE verbatim containment (product
     * evidence must be quotable literally).
     */
    fun containsVerbatim(quote: String, haystack: String): Boolean {
        val normalizedQuote = Parsing.normalizeWhitespace(quote.trim())
        if (normalizedQuote.isEmpty()) return false
        val normalizedHaystack = Parsing.normalizeWhitespace(haystack)
        return normalizedHaystack.contains(normalizedQuote)
    }

    /**
     * Accept a quote as evidence only if it is verbatim-contained in
     * [output], respects caps, and has a non-blank source label.
     * Returns null (never throws) when the law is violated; the caller
     * decides what "no evidence" means.
     */
    fun verifyEvidence(
        kind: EvidenceKind,
        quote: String,
        source: String,
        output: String,
        now: Long,
    ): Evidence? {
        if (quote.isBlank() || quote.length > Limits.MAX_EVIDENCE_CHARS) return null
        if (source.isBlank() || source.length > Limits.MAX_METRIC_TARGET_CHARS) return null
        if (output.length > Limits.MAX_OUTPUT_CHARS) return null
        if (!containsVerbatim(quote, output)) return null
        return Evidence(kind = kind, quote = quote, source = source, capturedAt = now)
    }

    /** First JSON object the model produced (if any). */
    fun extractFirstJsonObject(text: String): String? =
        Parsing.locateFirstJsonObject(text)

    /**
     * Extract the first count-like number for metric capture.
     * Supports `1,234`, `1.2k`, `3.4M`, `12k`, plain integers; suffixes
     * (k/M/B) mark the result APPROXIMATED. Rounding is DOWN — evidence
     * never overstates. IMPORTANT: pass the metric snippet region only,
     * not raw page HTML (URLs contain digits).
     */
    fun extractNumber(text: String): ParsedNumber? {
        if (text.length > Limits.MAX_OUTPUT_CHARS) return null
        for (match in NUMBER_CANDIDATE.findAll(text)) {
            val cleaned = match.value.replace(",", "").replace(" ", "")
            val shaped = NUMBER_SHAPE.find(cleaned) ?: continue
            val numberPart = shaped.groupValues[1]
            val suffix = shaped.groupValues[2].lowercase()
            val decimal = numberPart.toBigDecimalOrNull() ?: continue
            val multiplier = when (suffix) {
                "" -> BigDecimal.ONE
                "k" -> BigDecimal(1_000L)
                "m" -> BigDecimal(1_000_000L)
                "b" -> BigDecimal(1_000_000_000L)
                else -> continue
            }
            val scaled = decimal.multiply(multiplier)
            val value = scaled.setScale(0, RoundingMode.DOWN).toLongOrNull() ?: continue
            if (value >= 0) {
                return ParsedNumber(value, approximated = multiplier != BigDecimal.ONE)
            }
        }
        return null
    }

    private val NUMBER_CANDIDATE = Regex("\\d[\\d,]*(?:\\.\\d+)?\\s*(?:[kKmMbB](?![a-zA-Z]))?")

    private val NUMBER_SHAPE = Regex("^(\\d+(?:\\.\\d+)?)([kKmMbB])?$")
}
