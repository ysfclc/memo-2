package app.memo.core

/**
 * Hard bounds shared by every core module. The engine *refuses* work that
 * violates these rather than silently truncating: refusals surface as
 * `NEEDS_REVIEW`, never as hallucinated or clipped output. Values derived
 * from the approved Architecture & Design Document (D-011, fast-local-AI
 * token-budget addendum).
 */
object Limits {

    // ---- Workflow plans ------------------------------------------------
    const val MAX_STEPS_PER_RUN: Int = 12
    const val MAX_TITLE_CHARS: Int = 120
    const val MAX_STEP_TITLE_CHARS: Int = 120
    const val MAX_STEP_PROMPT_CHARS: Int = 4000
    const val MIN_STEP_ID_LENGTH: Int = 1
    const val MAX_STEP_ID_LENGTH: Int = 24
    const val MIN_STEP_ATTEMPTS: Int = 1
    const val MAX_STEP_ATTEMPTS: Int = 5
    const val RETRY_BACKOFF_FIRST_MS: Long = 4_000L
    const val RETRY_BACKOFF_MAX_MS: Long = 30_000L
    const val MAX_BRANCHES_PER_STEP: Int = 6

    // ---- Model / packet budgets ----------------------------------------
    // Token budgets are enforced as conservative char budgets (~4 chars/token).
    const val ORCHESTRATOR_INPUT_CHARS: Int = 8_000     // ~2k tokens in
    const val ORCHESTRATOR_OUTPUT_CHARS: Int = 2_800    // ~700 tokens out
    const val FRONTIER_INPUT_CHARS: Int = 16_000        // ~4k tokens in
    const val FRONTIER_OUTPUT_CHARS: Int = 4_800        // ~1.2k tokens out
    const val PACKET_FIELD_MAX_CHARS: Int = 2_000
    const val PACKET_MAX_FIELDS: Int = 24
    const val PACKET_TOTAL_MAX_CHARS: Int = 16_000

    // ---- Execution products --------------------------------------------
    const val MAX_OUTPUT_CHARS: Int = 6_000
    const val MAX_EVIDENCE_PER_STEP: Int = 5
    const val MAX_EVIDENCE_CHARS: Int = 1_200
    const val MAX_GATE_SUMMARY_CHARS: Int = 400
    const val MAX_ROUTING_NOTE_CHARS: Int = 200

    // ---- Artifacts ------------------------------------------------------
    const val MAX_NAME_CHARS: Int = 120
    const val MAX_CONTACT_FIELD_CHARS: Int = 200
    const val MAX_NOTES_CHARS: Int = 1_000
    const val MAX_DRAFT_BODY_CHARS: Int = 5_000
    const val MAX_TASK_TITLE_CHARS: Int = 140
    const val MAX_METRIC_TARGET_CHARS: Int = 300
    const val REMINDER_MIN_LEAD_MS: Long = 60_000L

    // ---- Knowledge & library (domain v2) --------------------------------
    const val MAX_LIST_ITEMS: Int = 32                  // tags, variables, channels, do-nots
    const val MAX_JSON_PAYLOAD_CHARS: Int = 64_000      // 12 steps x 4000-char prompts plus framing

    // ---- Audit ----------------------------------------------------------
    const val AUDIT_ACTION_MAX_CHARS: Int = 300
    const val AUDIT_TARGET_MAX_CHARS: Int = 200
    const val GENESIS_HASH: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    // ---- Codec / workspace ---------------------------------------------
    const val WORKSPACE_SCHEMA_VERSION: Int = 2     // v2: 16 registries + per-registry schema versions (D-020)
    const val MAX_WORKSPACE_BYTES: Int = 8_000_000
    const val MAX_ENTRIES_PER_REGISTRY: Int = 2_000

    // ---- Local models ----------------------------------------------------
    const val MIN_MODEL_BYTES: Long = 250_000_000L   // catalog sanity floor (threadline law)

    // ---- Idempotency id separator ---------------------------------------
    const val IDEMPOTENCY_SEPARATOR: String = ":"
}
