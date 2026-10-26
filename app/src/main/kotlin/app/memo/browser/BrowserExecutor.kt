package app.memo.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import app.memo.core.domain.OutboundEvent
import app.memo.core.engine.WorkflowEngine
import java.net.URI

class BrowserExecutor(
    private val webView: WebView,
    private val engine: WorkflowEngine,
    private val onStepComplete: (String) -> Unit,
) {
    private val whitelist = setOf(
        "chatgpt.com", "chat.openai.com", "claude.ai", "grok.com",
        "gemini.google.com", "deepseek.com", "chat.deepseek.com",
        "perplexity.ai", "chat.mistral.ai", "copilot.microsoft.com",
    )
    @Volatile private var currentEvent: OutboundEvent? = null

    init {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { !hostInWhitelist(URI.create(it).host.orEmpty()) } ?: true
        }
        webView.addJavascriptInterface(Bridge(), "Memo")
    }

    fun load(event: OutboundEvent) {
        val target = event.target.orEmpty()
        if (!hostInWhitelist(target)) {
            android.util.Log.w("MemoBrowser", "Rejected non-whitelisted browser target: $target")
            return
        }
        currentEvent = event
        webView.loadUrl("https://$target")
    }

    fun onArtifactReady(hash: String) {
        val event = currentEvent ?: return
        // The engine performs the authoritative hash comparison at the gate.
        engine.deliverGate(event.runId, event.stepId, true, hash, "artifact returned by browser")
        onStepComplete(event.runId)
    }

    private inner class Bridge {
        @JavascriptInterface fun artifactReady(hash: String) = onArtifactReady(hash)
        @JavascriptInterface fun questAnswer(@Suppress("UNUSED_PARAMETER") question: String): String = ""
    }

    private fun hostInWhitelist(host: String): Boolean {
        val lowered = host.lowercase()
        return whitelist.any { lowered == it || lowered.endsWith(".$it") }
    }
}
