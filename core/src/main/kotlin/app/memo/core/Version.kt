package app.memo.core

/**
 * Canonical product constants for the pure-Kotlin core. The Android shell
 * renders these; the repo keeps exactly one source of truth for versions
 * (see ENGINEERING_LOG §security flag 9 of M0: stale version strings are a
 * documented failure mode of the source apps).
 */
object Version {
    const val PRODUCT: String = "Memo"
    const val VERSION: String = "1.0.0"
    const val DSL_SCHEMA_VERSION: Int = 1
    const val WORKSPACE_SCHEMA_VERSION: Int = Limits.WORKSPACE_SCHEMA_VERSION
}
