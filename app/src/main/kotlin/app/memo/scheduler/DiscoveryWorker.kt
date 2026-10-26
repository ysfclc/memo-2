package app.memo.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.memo.journal.RunJournal
import app.memo.vault.MemoVault

/** Restores the append-only journal after boot without auto-running side effects. */
class DiscoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        RunJournal(MemoVault.ioFile(context, "journal.jsonl"))
        Log.i("MemoRecovery", "Boot recovery journal inspected")
    }
}
