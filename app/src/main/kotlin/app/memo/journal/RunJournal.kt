package app.memo.journal

import app.memo.core.engine.WorkflowEngine
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/** Append-only recovery journal. Malformed entries are ignored safely. */
class RunJournal(private val file: File, private val engine: WorkflowEngine? = null) {
    init {
        file.parentFile?.mkdirs()
        readAndRestore()
    }

    fun recordInterrupted(runId: String, stepId: String, attempt: Int) {
        val line = JSONObject()
            .put("runId", runId)
            .put("stepId", stepId)
            .put("attempt", attempt)
            .put("status", "INTERRUPTED")
            .put("ts", System.currentTimeMillis())
            .toString()
        FileWriter(file, true).use { it.appendLine(line) }
    }

    fun readAndRestore() {
        if (!file.exists()) return
        file.forEachLine { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }
}
