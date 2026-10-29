package app.memo.platform.android.voice

import java.net.URL

/**
 * Which hosts a voice-model download may touch. Pure, so the policy is unit-tested on the JVM.
 *
 * Voice models come from the sherpa-onnx GitHub releases. The first hop is `github.com`, which
 * redirects to the object store. Only these exact hosts are allowed, over HTTPS on the default
 * port. Any other hop is refused, never followed.
 */
object VoiceDownloadPolicy {

    /** The hosts a release download may be redirected through. */
    val ALLOWED_HOSTS: Set<String> = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Redirect hops followed before a download is refused. */
    const val MAX_REDIRECTS = 8

    /**
     * Null when [url] may be fetched. Otherwise a reason. The reason never contains the query
     * string, which carries signed storage tokens.
     */
    fun refusal(url: URL): String? = when {
        !url.protocol.equals("https", ignoreCase = true) -> "refused non-HTTPS hop to ${url.host}"
        url.port != -1 && url.port != 443 -> "refused non-default port on ${url.host}"
        url.host.lowercase() !in ALLOWED_HOSTS -> "refused redirect to a host outside GitHub releases: ${url.host}"
        else -> null
    }
}
