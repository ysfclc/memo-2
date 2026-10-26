package app.memo.approval

import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepStatus
import app.memo.core.engine.WorkflowEngine
import android.app.AlertDialog
import android.content.Context
import android.widget.ProgressBar
import android.widget.TextView
import android.graphics.Color

/**
 * Approval screen shown when a gate step reaches WAITING_USER.
 * 
 * Three buttons:
 *   • Approve  → engine.deliverGate(approved=true, contentHash) → COMPLETED
 *   • Veto     → engine.deliverGate(approved=false, "", "not my words")
 *            → skip to next gate, run continues
 *   • Review   → show the contentHash comparison (expected vs. actual)
 *               and let the user decide.
 * 
 * Law: veto only skips side effects up to the NEXT gate; later gates
 * are unaffected.  The engine handles the state machine; this UI just
 * routes to the right engine call.
 */
class ApprovalScreen(
    private val ctx: Context,
    private val engine: WorkflowEngine,
    private val runId: String,
    private val stepId: String,
    private val contentHash: String,
    private val onDecision: (Boolean) -> Unit,  // true=approve, false=veto
) {

    private var currentHash: String = contentHash

    fun show() {
        val view = ProgressBar(ctx).apply {
            // We'll replace the progress bar with hash comparison UI.
        }

        // Hash comparison display.
        val txtExpected = TextView(ctx).apply {
            txtExpected.text = "Expected: $contentHash"
            txtExpected.setTextColor(Color.DKGRAY)
            txtExpected.setPadding(24, 24, 24, 8)
        }
        val txtActual = TextView(ctx).apply {
            txtActual.text = "Actual: — waiting —"
            txtActual.setTextColor(Color.DKGRAY)
            txtActual.setPadding(24, 8, 24, 24)
        }

        // Buttons.
        val btnApprove = android.widget.Button(ctx).apply {
            txt.setText("Approve")
            setOnClickListener {
                onDecision(true)
                dismiss()
            }
        }
        val btnVeto = android.widget.Button(ctx).apply {
            txt.setText("Veto")
            setOnClickListener {
                onDecision(false)
                dismiss()
            }
        }
        val btnCancel = android.widget.Button(ctx).apply {
            txt.setText("Cancel")
            setOnClickListener {
                dismiss()
            }
        }

        val layout = android.widget.LinearLayout(ctx).apply {
            setOrientation(android.widget.LinearLayout.VERTICAL)
            addView(txtExpected)
            addView(txtActual)
            addView(btnApprove)
            addView(btnVeto)
            addView(btnCancel)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Gate Approval")
            .setView(layout)
            .setCancelable(false)
            .create()
        dialog.show()
    }

    /** Called by the engine when the user has compared hashes and made a decision. */
    fun onUserDecision(approve: Boolean) {
        if (approve) {
            engine.deliverGate(runId, stepId, true, contentHash, "task ok")
        } else {
            engine.deliverGate(runId, stepId, false, "", "not my words")
        }
        dismiss()
    }
}