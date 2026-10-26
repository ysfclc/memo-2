package app.memo.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.memo.journal.RunJournal
import app.memo.vault.MemoVault
import app.memo.core.engine.ExecutorRoutes
import app.memo.core.engine.WorkflowEngine

/** Restores the append-only journal after boot without inventing executors. */
class DiscoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val engine = WorkflowEngine(
            routes = ExecutorRoutes(
                reason = { _, _ -> app.memo.core.engine.Attempt.Failed("reason executor unavailable during boot") },
                browser = { _, _ -> app.memo.core.engine.Attempt.Failed("browser executor unavailable during boot") },
                metric = { _, _ -> app.memo.core.engine.Attempt.Completed() },
                dataStore = { _, _ -> app.memo.core.engine.Attempt.Completed() },
                artifactBuilder = { _, _ -> app.memo.core.engine.Attempt.Failed("artifact builder unavailable during boot") },
            ),
        )
        RunJournal(MemoVault.ioFile(context, "journal.jsonl"), engine)
        Log.i("MemoRecovery", "Boot recovery journal inspected")
    }
}
