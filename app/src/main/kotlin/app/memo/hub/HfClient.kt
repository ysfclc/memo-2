package app.memo.hub

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Which hosts a Hugging Face download may touch. Pure, so the policy is unit-tested on the JVM.
 *
 * - `huggingface.co` and its subdomains: the API and the first `resolve` hop. The bearer
 *   token is sent to these hosts only.
 * - `hf.co` and its subdomains: the storage CDN that `resolve` redirects to (observed:
 *   `us.aws.cdn.hf.co`; `cdn-lfs*.hf.co` and `cas-bridge.xethub.hf.co` are the same family).
 *   No token is sent here.
 *
 * Every hop must be `https` on the default port. Anything else is refused, never followed.
 */
object HfRedirectPolicy {

    private const val HUB = "huggingface.co"
    private const val CDN = "hf.co"

    /** True for `huggingface.co` and its subdomains; the only hosts that receive the token. */
    fun isApiHost(url: URL): Boolean = matches(url.host, HUB)

    /** True for any host in the Hugging Face allow-list (API or CDN). */
    fun isAllowedHost(host: String): Boolean = matches(host, HUB) || matches(host, CDN)

    /**
     * Null when [url] may be fetched. Otherwise a reason, which never contains a query
     * string or a credential.
     */
    fun refusal(url: URL): String? = when {
        !url.protocol.equals("https", ignoreCase = true) ->
            "refused non-HTTPS hop to ${url.host}"
        url.port != -1 && url.port != 443 ->
            "refused non-default port on ${url.host}"
        !isAllowedHost(url.host) ->
            "refused redirect to a host outside Hugging Face: ${url.host}"
        else -> null
    }

    private fun matches(host: String, base: String): Boolean {
        val h = host.lowercase()
        return h == base || h.endsWith(".$base")
    }
}

/** A transfer that was refused by [HfRedirectPolicy] or by the redirect limit. */
class HfTransferRefused(message: String) : IOException(message)

/** A non-success HTTP status from Hugging Face (other than the statuses resume logic accepts). */
class HfHttpError(val code: Int) : IOException("Hugging Face answered HTTP $code")

/**
 * HTTPS-only client for model downloads and the Hugging Face API. Redirects are followed by
 * hand so that every hop passes [HfRedirectPolicy] before it is opened.
 *
 * [tokenProvider] supplies the optional `hf_token` (read from the vault by the hub). It is
 * sent as `Authorization: Bearer` to `huggingface.co` hops only. It never appears in an
 * exception message, a log line or a URL.
 *
 * Blocking I/O: call from a background thread.
 */
class HfClient(private val tokenProvider: () -> String?) {

    /** One opened response. The caller must [close] it. */
    class Response internal constructor(
        val status: Int,
        val contentRange: String?,
        val body: InputStream,
        private val connection: HttpURLConnection,
    ) : Closeable {
        override fun close() {
            runCatching { body.close() }
            connection.disconnect()
        }
    }

    /**
     * Opens [url] with an optional `Range: bytes=[rangeFrom]-` header and returns the response
     * for status 200, 206 or 416. Any other status throws [HfHttpError].
     */
    fun open(url: String, rangeFrom: Long = 0L): Response {
        var current = parseAllowed(url)
        var hops = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeFrom > 0L) setRequestProperty("Range", "bytes=$rangeFrom-")
                if (HfRedirectPolicy.isApiHost(current)) {
                    tokenProvider()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
            }
            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw HfTransferRefused("redirect without a Location header")
                if (++hops > MAX_REDIRECTS) throw HfTransferRefused("too many redirects")
                current = parseAllowed(URL(current, location).toString())
                continue
            }
            if (code == HttpURLConnection.HTTP_OK ||
                code == HttpURLConnection.HTTP_PARTIAL ||
                code == HTTP_RANGE_NOT_SATISFIABLE
            ) {
                val stream = if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                    java.io.ByteArrayInputStream(ByteArray(0))
                } else {
                    connection.inputStream
                }
                return Response(code, connection.getHeaderField("Content-Range"), stream, connection)
            }
            connection.disconnect()
            throw HfHttpError(code)
        }
    }

    /** GET of a small text body (API JSON). Throws on any status other than 200. */
    fun getText(url: String): String {
        open(url).use { response ->
            if (response.status != HttpURLConnection.HTTP_OK) throw HfHttpError(response.status)
            return response.body.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun parseAllowed(url: String): URL {
        val parsed = try {
            URL(url)
        } catch (e: java.net.MalformedURLException) {
            throw HfTransferRefused("malformed URL")
        }
        HfRedirectPolicy.refusal(parsed)?.let { throw HfTransferRefused(it) }
        return parsed
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 8
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
