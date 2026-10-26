package app.memo.core.registry

import app.memo.core.util.Sha256Hex

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

    fun short(prefix: String, key: String): String = prefix + Sha256Hex.hex(key).take(16)

    fun digitsOnly(contact: String): String = contact.filter { it.isDigit() }

    fun slug(text: String): String =
        text.trim().lowercase()
            .map { if (it.isWhitespace()) ' ' else it }
            .filter { it in 'a'..'z' || it in '0'..'9' || it == ' ' }
            .joinToString("")
            .replace(Regex(" +"), "-")
            .take(48)
            .trim('-')

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
