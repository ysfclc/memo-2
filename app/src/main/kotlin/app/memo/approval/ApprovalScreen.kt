package app.memo.approval

import app.memo.core.engine.WorkflowEngine
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

/** Approval surface that binds a decision to the displayed artifact hash. */
class ApprovalScreen(
    private val ctx: Context,
    private val engine: WorkflowEngine,
    private val runId: String,
    private val stepId: String,
    private val contentHash: String,
    private val onDecision: (Boolean) -> Unit,
) {
    fun show() {
        val expected = TextView(ctx).apply {
            text = "Expected artifact hash: $contentHash"
            setTextColor(Color.DKGRAY)
            setPadding(24, 24, 24, 12)
        }
        val summary = TextView(ctx).apply {
            text = "Review the completed artifact before allowing automation to continue."
            setTextColor(Color.DKGRAY)
            setPadding(24, 12, 24, 24)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(expected)
            addView(summary)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Gate approval")
            .setView(layout)
            .setPositiveButton("Approve") { _, _ -> onUserDecision(true) }
            .setNegativeButton("Veto") { _, _ -> onUserDecision(false) }
            .setNeutralButton("Cancel", null)
            .setCancelable(false)
            .create()
        dialog.show()
    }

    fun onUserDecision(approve: Boolean) {
        val note = if (approve) "approved artifact" else "vetoed by user"
        engine.deliverGate(runId, stepId, approve, if (approve) contentHash else "", note)
        onDecision(approve)
    }
}
