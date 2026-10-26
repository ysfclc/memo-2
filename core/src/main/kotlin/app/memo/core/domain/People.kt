package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONObject

/**
 * A human in the personal work graph. Identity is by natural key
 * (name + phone digits) so re-meeting someone reuses their record
 * instead of duplicating it (AGENTS.md rule 2).
 */
data class Person(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val companyKey: String = "",
    val role: String = "",
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fullName", fullName)
        .put("email", email)
        .put("phone", phone)
        .put("companyKey", companyKey)
        .put("role", role)
        .put("notes", notes)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("version", version)

    /** Business-content edits (for the registry diff) exclude bookkeeping fields. */
    fun contentJson(): JSONObject {
        val o = toJson()
        o.remove("createdAt").also { o.remove("updatedAt") }
        o.remove("version")
        return o
    }

    fun isInvalid(): Boolean =
        fullName.isBlank() ||
            fullName.length > Limits.MAX_NAME_CHARS ||
            email.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            phone.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            role.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            notes.length > Limits.MAX_NOTES_CHARS

    companion object {
        fun fromJson(raw: JSONObject): Person? {
            val fullName = raw.optString("fullName")
            if (fullName.isBlank()) return null
            return Person(
                fullName = fullName,
                email = raw.optString("email"),
                phone = raw.optString("phone"),
                companyKey = raw.optString("companyKey"),
                role = raw.optString("role"),
                notes = raw.optString("notes"),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}

/**
 * An organization, identified by its normalized domain (`acme.com`),
 * which other records reference as `companyKey`.
 */
data class Company(
    val domain: String = "",
    val displayName: String = "",
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("domain", domain)
        .put("displayName", displayName)
        .put("notes", notes)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("version", version)

    fun contentJson(): JSONObject {
        val o = toJson()
        o.remove("createdAt").also { o.remove("updatedAt") }
        o.remove("version")
        return o
    }

    fun isInvalid(): Boolean =
        domain.isBlank() ||
            domain.length > Limits.MAX_CONTACT_FIELD_CHARS ||
            displayName.length > Limits.MAX_NAME_CHARS ||
            notes.length > Limits.MAX_NOTES_CHARS

    companion object {
        fun fromJson(raw: JSONObject): Company? {
            val domain = raw.optString("domain")
            if (domain.isBlank()) return null
            return Company(
                domain = domain,
                displayName = raw.optString("displayName"),
                notes = raw.optString("notes"),
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                version = raw.optInt("version", 1).coerceAtLeast(1),
            ).takeUnless { it.isInvalid() }
        }
    }
}
