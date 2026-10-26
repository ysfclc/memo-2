package app.memo.journal

import app.memo.core.domain.OutboundEvent
import app.memo.core.engine.WorkflowEngine
import java.io.*;
import java.time.Instant

/**
 * Simple JSONL journal that records INTERRUPTED steps so the engine can
 * re‑execute them after process death.
 * 
 * Idempotency key format: runId:stepId:attempt
 * Each line: {"runId":"...","stepId":"...","attempt":N,"status":"INTERRUPTED","ts":N}
 * On app start the journal is read and every INTERRUPTED entry with
 * resumeAt <= clock() is re‑queued for execution.
 */
class RunJournal(
    private val file: File,
    private val engine: WorkflowEngine,
) {

    companion object {
        private const val KEY_FORMAT = "runId:stepId:attempt"
    }

    init {
        file.parentFile.mkdirs()
        readAndRestore()
    }

    /** Append an INTERRUPTED entry for the given run/step/attempt. */
    fun recordInterrupted(runId: String, stepId: String, attempt: Int) {
        val line = JSONObject()
            .put("runId", runId)
            .put("stepId", stepId)
            .put("attempt", attempt)
            .put("status", "INTERRUPTED")
            .put("ts", Instant.now().toEpochMillis())
            .toString()
        FileWriter(file, true).use { writer ->
            writer.write(line)
            writer.newLine()
        }
        // Also re‑queue for immediate re‑execution if resumeAt has passed.
        engine.reQueueIfNeeded(runId, stepId)
    }

    /** Read the journal and re‑queue any INTERRUPTED steps whose
     * resume time has passed.  Called on app startup. */
    private fun readAndRestore() {
        if (!file.exists()) return
        File(file).readLines().forEach { line ->
            try {
                val json = JSONObject(line)
                val runId = json.getString("runId")
                val stepId = json.getString("stepId")
                val attempt = json.getInt("attempt")
                val status = json.getString("status")
                if (status == "INTERRUPTED") {
                    // Re‑execute if the clock has passed the recorded resumeAt.
                    // (The engine stores resumeAt; here we just re‑queue.)
                    engine.reQueueIfNeeded(runId, stepId)
                }
            } catch (_: Exception) {
                // Malformed line — skip, don't corrupt the chain.
            }
        }
    }
}