package app.memo.browser

import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.OutboundChannel
import app.memo.core.domain.OutboundEvent
import app.memo.core.engine.WorkflowEngine
import android.webkit.WebView
import android.content.Intent

/**
 * Bridge between the core engine and the Android hardened WebView.
 * 
 * Law: every side-effect step of kind BROWSER_AUTOMATION must go through
 * this executor.  The engine hands us an OutboundEvent (runId/stepId/
 * target/action/contentHash); we load the target URL inside the WebView,
 * bind the JS interface, and on completion we either:
 *   - contentHash matches → deliverGate → COMPLETED
 *   - contentHash mismatch  → NEEDS_REVIEW (user must inspect)
 *   - navigation off-whitelist → abort + FAILED
 * 
 * The engine re-verifies the hash on deliverGate; if the JS returns a
 * different contentHash the engine puts the run into NEEDS_REVIEW so the
 * user can approve or veto.
 */
class BrowserExecutor(
    private val webView: WebView,
    private val engine: WorkflowEngine,
    private val onStepComplete: (String) -> Unit,  // runId
) {

    // Whitelisted hosts from ProviderRegistry (8 frontier providers).
    private val whitelist = setOf(
        "chatgpt.com", "chat.openai.com",
        "claude.ai", "grok.com",
        "gemini.google.com", "deepseek.com",
        "perplexity.ai", "chat.mistral.ai",
        "copilot.microsoft.com"
    )

    init {
        // Basic WebView settings — no extra JS APIs beyond what we need.
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Prevent external tabs; keep everything in-frame.
        webView.setWebViewClient(object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                try {
                    val host = java.net.URL(url).host
                    if (!hostInWhitelist(host)) {
                        // Navigate outside whitelist → abort this step.
                        // Engine will see FAILED / NEEDS_REVIEW depending on context.
                        return@setWebViewClient true
                    }
                } catch _: Exception {
                    // Malformed URL → abort.
                    return@setWebViewClient true
                }
                return false
            }
        })
    }

    /** Load the provider URL inside the WebView.  The target string from
     * the OutboundEvent is expected to be a host that matches the whitelist. */
    fun load(event: OutboundEvent) {
        val target = event.target ?: return
        if (!hostInWhitelist(target)) {
            // Outside whitelist → engine will mark FAILED / NEEDS_REVIEW
            engine.markStepFailed(event.stepId, "browser target '${event.target}' not in whitelist")
            return
        }
        webView.loadUrl("https://$target")
    }

    /** Called by the JS interface when the page has produced an artifact
     * with a deterministic contentHash.  The engine already compared the
     * hash during deliverGate; if it matches we can advance, otherwise
     * the run goes into NEEDS_REVIEW. */
    fun onArtifactReady(hash: String, stepId: String) {
        if (engine.verifyContentHash(stepId, hash)) {
            // Hash matches → gate approved, advance.
            engine.deliverGate(event.runId, stepId, true, hash, "task ok")
        } else {
            // Hash mismatch → user must review.
            engine.deliverGate(event.runId, stepId, false, hash, "hash mismatch — review")
        }
    }

    /** JS interface so the WebPage can call back into native code. */
    @android.webkit.JavascriptInterface("Memo")
    internal object Bridge {
        /** Called from the page when the artifact is ready with its hash. */
        fun artifactReady(hash: String) {
            BrowserExecutor.this.onArtifactReady(hash, currentStepId.get() ?: "")
        }

        fun questAnswer(question: String): String {
            return "Answer from Memo bridge"
        }
    }

    // Lazily hold the current stepId so the JS bridge can refer to it.
    @Volatile
    private var currentStepId: String? = null

    fun setCurrentStepId(id: String) {
        currentStepId = id
    }

    private fun hostInWhitelist(host: String): Boolean {
        // Case‑insensitive host comparison; the whitelist entries are already lowered.
        val lowered = host.toLowerCase()
        return whitelist.any { it.equals(lowered) }
    }
}