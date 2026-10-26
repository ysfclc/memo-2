package app.memo.core.redact

/**
 * Secret redaction for anything that could leave the device (product law:
 * no silent egress of secrets). Applied to packet fields, gate summaries
 * and audit action text before they are used anywhere outbound.
 */
data class SecretHit(val kind: String, val start: Int, val end: Int)

data class Redaction(val text: String, val hits: List<SecretHit>)

object Redactor {

    /** Ordered rules; earlier kinds win overlap collisions. */
    private val rules: List<Pair<String, List<Regex>>> = listOf(
        "PRIVATE_KEY" to listOf(
            Regex("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"),
        ),
        "OPENAI_KEY" to listOf(
            Regex("""sk-[A-Za-z0-9_-]{16,}"""),
        ),
        "GITHUB_TOKEN" to listOf(
            Regex("""gh[pousr]_[A-Za-z0-9]{16,}"""),
        ),
        "AWS_KEY" to listOf(
            Regex("""AKIA[0-9A-Z]{12,}"""),
        ),
        "JWT" to listOf(
            Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}"""),
        ),
        "BEARER" to listOf(
            Regex("""(?i)bearer\s+[A-Za-z0-9_\-.=+/]{12,}"""),
        ),
        "SECRET_ASSIGN" to listOf(
            Regex("""(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|token)\s*[:=]\s*\S{6,}"""),
        ),
    )

    private val CARD_CANDIDATE = Regex("""\b[\d][\d ]{11,24}[\d]\b""")

    /**
     * Find secret spans in [text]. Overlaps resolve by rule priority
     * (list order), then longer match wins.
     */
    fun scan(text: String): List<SecretHit> {
        val found = mutableListOf<SecretHit>()
        for (rule in rules) {
            val kind = rule.first
            for (regex in rule.second) {
                for (match in regex.findAll(text)) {
                    found += SecretHit(kind, match.range.first, match.range.last + 1)
                }
            }
        }
        // Card candidates get a digit-count verification (13..19 digits).
        for (match in CARD_CANDIDATE.findAll(text)) {
            val digits = match.value.count { it.isDigit() }
            if (digits in 13..19) {
                found += SecretHit("CARD", match.range.first, match.range.last + 1)
            }
        }
        return selectNonOverlapping(found)
    }

    /** Replace every detected secret with a visible `[REDACTED_<KIND>]` marker. */
    fun redact(text: String): Redaction {
        if (text.isEmpty()) return Redaction(text, emptyList())
        val hits = scan(text)
        if (hits.isEmpty()) return Redaction(text, emptyList())
        val sb = StringBuilder()
        var cursor = 0
        for (hit in hits) {
            sb.append(text, cursor, hit.start)
            sb.append("[REDACTED_").append(hit.kind).append(']')
            cursor = hit.end
        }
        sb.append(text, cursor, text.length)
        return Redaction(sb.toString(), hits)
    }

    private fun selectNonOverlapping(hits: List<SecretHit>): List<SecretHit> {
        val sorted = hits.sortedWith(
            compareBy<SecretHit> { it.start }.thenByDescending { it.end - it.start },
        )
        val selected = mutableListOf<SecretHit>()
        var lastEnd = -1
        for (hit in sorted) {
            if (hit.start >= lastEnd) {
                selected += hit
                lastEnd = hit.end
            }
        }
        return selected
    }
}
