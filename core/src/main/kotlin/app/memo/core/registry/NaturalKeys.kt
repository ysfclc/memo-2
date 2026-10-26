package app.memo.core.registry

import app.memo.core.util.Sha256Hex
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic natural-key builders (AGENTS.md rule 2 — reuse first).
 * Ids are short SHA-256 prefixes of the key parts so they are stable
 * across reparses, exports and restores without leaking raw contact data.
 */
object NaturalKeys {

    const val DAY_MS: Long = 86_400_000L

    /** "p:" + 16 hex chars of (name|phoneDigits). */
    fun person(fullName: String, phone: String): String {
        if (fullName.isBlank()) return ""
        return short("p:", normalizeName(fullName) + "|" + digitsOnly(phone))
    }

    /** "c:" + hash of the normalized domain. */
    fun company(domainRaw: String): String {
        val domain = normalizeDomain(domainRaw)
        if (domain.isBlank()) return ""
        return short("c:", domain)
    }

    /** "j:" + hash of (companyKey|role). */
    fun job(companyKey: String, role: String): String {
        val roleNorm = role.trim().lowercase()
        if (companyKey.isBlank() || roleNorm.isBlank()) return ""
        return short("j:", companyKey.trim() + "|" + roleNorm)
    }

    /** "a:" + hash of the job key — one application per posting. */
    fun application(jobKey: String): String {
        if (jobKey.isBlank()) return ""
        return short("a:", jobKey.trim())
    }

    /** "t:" + hash of (titleSlug|day-of-dueAt-or-0). */
    fun task(title: String, dueAt: Long): String {
        val slug = slug(title)
        if (slug.isBlank()) return ""
        val day = if (dueAt > 0) dayBucket(dueAt).toString() else "0"
        return short("t:", slug + "|" + day)
    }

    /** "d:" + hash of (kind|title-slug|creation day). */
    fun draft(kind: String, title: String, bodyHead: String, createdDay: Long): String {
        val anchor = slug(title.ifBlank { bodyHead })
        if (anchor.isBlank()) return ""
        return short("d:", kind.trim().uppercase() + "|" + anchor + "|" + createdDay)
    }

    /** "po:" + hash of the normalized URL. */
    fun post(urlRaw: String): String {
        val url = normalizeUrl(urlRaw)
        if (url.isBlank()) return ""
        return short("po:", url)
    }

    /** "ms:" + hash of (postKey|metric|capture day). */
    fun metric(postKey: String, metric: String, capturedAt: Long): String {
        val metricNorm = metric.trim().lowercase()
        if (postKey.isBlank() || metricNorm.isBlank()) return ""
        return short("ms:", postKey + "|" + metricNorm + "|" + dayBucket(capturedAt))
    }

    /** "pj:" + hash of the project slug. */
    fun project(name: String): String {
        val slug = slug(name)
        if (slug.isBlank()) return ""
        return short("pj:", slug)
    }

    /** "nt:" + hash of (projectKey|titleSlug|creation day). Blank projectKey = unfiled note. */
    fun note(projectKey: String, title: String, createdAt: Long): String {
        val slug = slug(title)
        if (slug.isBlank()) return ""
        return short("nt:", projectKey.trim().lowercase() + "|" + slug + "|" + dayBucket(createdAt))
    }

    /** "wt:" + hash of the workflow template slug. */
    fun workflowTemplate(name: String): String {
        val slug = slug(name)
        if (slug.isBlank()) return ""
        return short("wt:", slug)
    }

    /** "pt:" + hash of the prompt template slug. */
    fun promptTemplate(name: String): String {
        val slug = slug(name)
        if (slug.isBlank()) return ""
        return short("pt:", slug)
    }

    /** "pr:" + hash of the principle title slug. */
    fun principle(title: String): String {
        val slug = slug(title)
        if (slug.isBlank()) return ""
        return short("pr:", slug)
    }

    /** "cp:" + hash of the lowercased scope ("global" or a channel). */
    fun preference(scope: String): String {
        val scopeNorm = scope.trim().lowercase()
        if (scopeNorm.isBlank()) return ""
        return short("cp:", scopeNorm)
    }

    /** "ca:" + hash of (platform|handle), both lowercased; a leading @ is not part of a handle. */
    fun channelAccount(platform: String, handle: String): String {
        val platformNorm = platform.trim().lowercase()
        val handleNorm = handle.trim().removePrefix("@").lowercase()
        if (platformNorm.isBlank() || handleNorm.isBlank()) return ""
        return short("ca:", platformNorm + "|" + handleNorm)
    }

    /** "ad:" + hash of (channel|targetId), both lowercased. */
    fun adapter(channel: String, targetId: String): String {
        val channelNorm = channel.trim().lowercase()
        val targetNorm = targetId.trim().lowercase()
        if (channelNorm.isBlank() || targetNorm.isBlank()) return ""
        return short("ad:", channelNorm + "|" + targetNorm)
    }

    fun short(prefix: String, key: String): String = prefix + Sha256Hex.hex(key).take(16)

    fun digitsOnly(contact: String): String = contact.filter { it.isDigit() }

    /**
     * Human-readable key part: NFKC, lowercase with Locale.ROOT (so Turkish locales
     * do not change it), dotted-i sequences folded to "i", then only letters and
     * decimal digits kept (any Unicode script). Whitespace becomes "-". At most 48
     * code points. ASCII input yields the same slug as before this rule set.
     */
    fun slug(text: String): String {
        val folded = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace("i\u0307", "i")
        val kept = StringBuilder()
        folded.codePoints().forEach { cp ->
            when {
                Character.isWhitespace(cp) -> kept.append(' ')
                Character.isLetter(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() ->
                    kept.appendCodePoint(cp)
            }
        }
        val dashed = kept.toString().replace(Regex(" +"), "-").codePoints().toArray()
        val cut = dashed.copyOf(minOf(dashed.size, 48))
        return String(cut, 0, cut.size).trim('-')
    }

    fun normalizeName(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Lowercase, strip leading www. and trailing slash; no path-case mangling. */
    fun normalizeDomain(raw: String): String =
        raw.trim().lowercase()
            .removePrefix("www.")
            .trimEnd('/')

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        // Drop fragments and trailing slashes so re-encounters match.
        val noFragment = trimmed.substringBefore('#')
        return noFragment.trimEnd('/')
    }

    /** Midnight UTC bucket (post capture day). */
    fun dayBucket(ts: Long): Long = if (ts <= 0) 0 else ts / DAY_MS * DAY_MS
}
