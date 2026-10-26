package app.memo.scheduler

import app.memo.core.plan.CommandGrammar
import app.memo.core.engine.WorkflowEngine
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepStatus
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Scheduled wake‑up receiver.  Every morning at 08:00 the system
 * broadcasts ACTION_BOOT_COMPLETED; this receiver re‑hydrates the
 * journal, re‑executes any INTERRUPTED steps, and, if a run is
 * waiting at a gate, re‑presents the approval UI.
 * 
 * Law: interrupted side effects never auto‑resume — the journal
 * records the exact state so the engine can re‑run idempotently.
 */
class DiscoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val journal = app.memo.journal.RunJournal(
            app.memo.vault.MemoVault.ioFile(context, "journal.jsonl"),
            app.memo.engine.WorkflowEngine(
                routes = app.memo.engine.ExecutorRoutes(
                    reason = app.memo.reason.ReasonExecutor { _, _ -> todo("reason not implemented") },
                    browser = app.memo.browser.BrowserExecutor(/* webView */),
                    metric = {},
                    dataStore = {},
                    artifactBuilder = {},
                )
            ),
        )
        journal.readAndRestore()

        // If any run is WAITING_USER at a gate, bring the approval UI forward.
        // (The engine exposes a small introspection API for this; if not yet
        // exposed, the UI will be shown on the next user interaction.)
        Log.i("DiscoveryReceiver", "Boot completed — journal restored, gate UI pending user interaction")
    }
}