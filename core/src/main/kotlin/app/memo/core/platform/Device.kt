package app.memo.core.platform

/**
 * One installed application as seen by [PackageInspector]. [enabled] is false
 * for disabled or suspended packages. Automation never targets disabled apps.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val enabled: Boolean,
) {
    init {
        require(PACKAGE_PATTERN.matches(packageName)) { "invalid package name: $packageName" }
        require(label.isNotBlank()) { "label must not be blank" }
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}

/**
 * Read-only view of what is installed. Used by discovery and routing to
 * decide which app packages can be targeted. It never installs or launches
 * anything.
 */
interface PackageInspector {
    /** Lists installed packages visible to Memo. Results are a snapshot. */
    fun installedPackages(): List<InstalledApp>

    /**
     * Returns the package name of the system WebView provider, or null when it
     * cannot be determined. Used to decide whether the web runtime is available.
     */
    fun webViewProvider(): String?
}

/** Category of a [MemoNotice]. Drives channel and urgency on the device. */
enum class NoticeKind { APPROVAL, TAKEOVER, PROGRESS, INFO }

/**
 * A user-visible notification.
 *
 * [id] is stable, so a later notice with the same id replaces this one. [title]
 * is at most 120 characters and [body] at most 500. When [sensitive] is true,
 * the device must hide the body on the lock screen. The caller has already
 * redacted it.
 */
data class MemoNotice(
    val id: String,
    val title: String,
    val body: String,
    val kind: NoticeKind,
    val sensitive: Boolean,
) {
    init {
        require(ID_PATTERN.matches(id)) { "notice id must be 1..64 of [A-Za-z0-9_.:-]" }
        require(title.isNotBlank() && title.length <= 120) { "title must be 1..120 characters" }
        require(body.length <= 500) { "body must be at most 500 characters" }
    }

    private companion object {
        val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$")
    }
}

/**
 * Posts and withdraws user notifications. Calls are synchronous and must not
 * block on network I/O. [cancel] of an unknown id is a no-op.
 */
interface Notifier {
    /** Shows [n], replacing any notice with the same id. */
    fun post(n: MemoNotice)

    /** Withdraws the notice with [id], if it is showing. */
    fun cancel(id: String)
}

/**
 * Source of wall-clock time in epoch milliseconds. Injected everywhere a
 * timestamp is taken, so tests and replays control time.
 */
interface Clock {
    /** Current time in milliseconds since the Unix epoch. */
    fun now(): Long
}
