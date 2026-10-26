package app.memo.core.registry

/**
 * Semantic version of one registry row: `schemaMajor.schemaMinor.version`.
 * The patch component is the entity's content revision counter (a content
 * change bumps it, identical content reuses it); major and minor describe the
 * schema the row was written under (see [RegScheme.schemaMajor]).
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "semver parts must be non-negative" }
    }

    override fun toString(): String = "$major.$minor.$patch"

    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        private val STRICT = Regex("""\d+\.\d+\.\d+""")

        /** Strict `MAJOR.MINOR.PATCH` with plain decimal digits; null for anything else. */
        fun parse(text: String): SemVer? {
            if (!STRICT.matches(text)) return null
            val parts = text.split('.').map { it.toIntOrNull() ?: return null }
            return SemVer(parts[0], parts[1], parts[2])
        }
    }
}
