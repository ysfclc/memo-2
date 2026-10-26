package app.memo.core.engine

import app.memo.core.Limits
import app.memo.core.codec.CanonicalJson
import app.memo.core.domain.AgentRun
import app.memo.core.domain.BranchOutcome
import app.memo.core.domain.Evidence
import app.memo.core.domain.OutboundChannel
import app.memo.core.domain.OutboundEvent
import app.memo.core.domain.RunStatus
import app.memo.core.domain.StepKind
import app.memo.core.domain.StepSpec
import app.memo.core.domain.StepState
import app.memo.core.domain.StepStatus
import app.memo.core.domain.WorkflowSpec
import app.memo.core.plan.OutcomeParser
import app.memo.core.plan.WorkflowPolicy
import app.memo.core.prompts.Prompts
import app.memo.core.prompts.ContextPacket
import app.memo.core.registry.NaturalKeys
import org.json.JSONObject

/**
 * What one executor attempt produced. The engine maps these onto step and
 * run states; nothing else may invent step transitions.
 *
 * - [Completed] closes a step successfully (output + optional evidence).
 * - [Measured] is the ONLY acceptable metric_collect result; the engine
 *   persists the MetricSample (and a minimal SocialPost shell) itself.
 * - [Failed] is a normal attempt failure; retry policy decides what next.
 * - [Pending] means the executor started external work (browser tab, app
 *   flow, remote job) and will finish later. The step stays RUNNING with
 *   [ticket] until [WorkflowEngine.deliverExternal] delivers the final result.
 *   [pollAfterMs] is only a hint for the driver; the engine never polls.
 *   A blank ticket is treated as [Failed].
 */
sealed interface Attempt {
    data class Completed(val output: String = "", val evidence: List<Evidence> = emptyList()) : Attempt
    data class Measured(val number: OutcomeParser.ParsedNumber) : Attempt
    data class Failed(val reason: String) : Attempt
    data class Pending(val ticket: String, val pollAfterMs: Long = 0) : Attempt
}

/** Executors live in the app layer; `core` only defines the contract. */
fun interface ReasonExecutor {
    fun execute(ctx: ExecContext, step: StepSpec): Attempt
}

fun interface BrowserExecutor {
    fun execute(ctx: ExecContext, step: StepSpec): Attempt
}

fun interface AppAutomationExecutor {
    fun execute(ctx: ExecContext, step: StepSpec): Attempt
}

fun interface MetricExecutor {
    fun execute(ctx: ExecContext, step: StepSpec): Attempt
}

/**
 * Gate artifact filler (product law: fill before asking). Given the gate's
 * artifact kind and the prior step outputs, returns the filled artifact as
 * ONE JSON object (Completed.output) or explains why it cannot.
 */
fun interface ArtifactBuilder {
    fun build(ctx: ExecContext, artifactType: String, priorOutputs: Map<String, String>): Attempt
}

/**
 * Registry sink used by DATA_PERSIST and METRIC_COLLECT steps.
 * Returns "" on success or an error reason; the engine refuses unless the
 * answer is "" (no silent "probably stored").
 */
fun interface DataStore {
    fun upsert(kind: String, naturalId: String, payloadJson: String): String
}

/** Outbound audit hook; the app layer wires this to the HashChainAudit. */
fun interface OutboundAudit {
    fun record(event: OutboundEvent)
}

/** Route table. Defaults fail closed — an unregistered kind FAILS its step. */
class ExecutorRoutes(
    val reason: ReasonExecutor = { _, _ -> Attempt.Failed("no reason executor registered") },
    val browser: BrowserExecutor = { _, _ -> Attempt.Failed("no browser executor registered") },
    val app: AppAutomationExecutor = { _, _ -> Attempt.Failed("no app automation executor registered") },
    val metric: MetricExecutor = { _, _ -> Attempt.Failed("no metric executor registered") },
    val dataStore: DataStore = { kind, _, _ -> "no data store registered (kind $kind)" },
    val artifactBuilder: ArtifactBuilder = { _, type, _ -> Attempt.Failed("no artifact builder registered (type $type)") },
)

/**
 * Everything one attempt sees. `approvedArtifactHash` carries the content
 * hash bound at the most recent COMPLETED gate: TOCTOU duty of executors is
 * to re-derive the hash of the artifact they will act on and refuse on
 * mismatch. The engine refuses to RUN unbound side-effect steps at all.
 */
data class ExecContext(
    val runId: String,
    val stepId: String,
    val attempt: Int,
    val idempotencyKey: String,
    val now: Long,
    val packet: ContextPacket,
    val approvedArtifactHash: String,
    val priorOutputs: Map<String, String>,
    val collectedEvidence: List<Evidence>,
)

/**
 * The workflow engine (Architecture law, section 3):
 *
 * - strictly sequential; no parallel side effects;
 * - side-effect steps REQUIRE a preceding completed gate (approval binding);
 * - gates re-verify the artifact hash on deliver (mismatch -> NEEDS_REVIEW);
 * - a side effect binds to the NEAREST preceding HUMAN_APPROVAL step regardless
 *   of its status; that gate must be COMPLETED, or the side effect is refused
 *   (D-023). Older approvals never cover a later side effect;
 * - a veto skips side-effect steps up to the NEXT gate only;
 * - interruptions NEVER auto-resume side effects (resumeInterrupted);
 * - before a side-effect executor runs, the run is checkpointed with the step
 *   RUNNING and an "inflight:" ticket; a crash mid side effect therefore
 *   restores as NEEDS_REVIEW, never as PENDING (recoverAfterRestart, D-023);
 * - retry is bounded backoff (4s doubling -> 30s cap);
 * - every step attempt carries an idempotency key runId:stepId:attempt;
 * - the engine NEVER fabricates success: only executor results close steps.
 *
 * [checkpoint] is called synchronously with the run, immediately before a
 * side-effect executor is invoked. The app persists the run there. If it
 * throws, the executor is NOT invoked and the step fails.
 */
class WorkflowEngine(
    val routes: ExecutorRoutes,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val packetProvider: () -> ContextPacket = { ContextPacket.Builder().build() },
    private val audit: OutboundAudit? = null,
    private val checkpoint: (AgentRun) -> Unit = {},
) {

    private val runs = LinkedHashMap<String, AgentRun>()
    private val attemptResults = HashMap<String, Attempt>()
    private var runCounter = 0

    // ------------------------------------------------------------------ API

    fun createRun(spec: WorkflowSpec): AgentRun {
        val violations = WorkflowPolicy.validateSpec(spec)
        check(violations.isEmpty()) { "refusing invalid plan: " + violations.joinToString(" · ") }
        val now = clock()
        runCounter += 1
        val run = AgentRun(
            id = "run-" + java.lang.Long.toHexString(now) + "-" + runCounter,
            spec = spec,
            status = RunStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
            steps = spec.steps.map { StepState(spec = it) },
            pointer = 0,
        )
        runs[run.id] = run
        return run
    }

    fun run(id: String): AgentRun? = runs[id]

    fun runs(): List<AgentRun> = runs.values.toList()

    /** Restore a persisted run (process-death resume path). */
    fun putRun(run: AgentRun) {
        runs[run.id] = run
    }

    /**
     * Executes ready work until the run blocks (gate, backoff, interruption,
     * review) or terminates. Safe to call again at any time.
     */
    fun advance(runId: String): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        if (existing.status !in RESUMABLE_STATUSES) return existing

        val steps = existing.steps.toMutableList()
        var pointer = existing.pointer
        var runStatus = RunStatus.RUNNING
        var runFailure = ""
        var routingNote = existing.routingNote
        var settled = false

        val safetyMax = steps.size * 3 + 8
        var iterations = 0

        while (iterations++ < safetyMax) {
            if (pointer >= steps.size) {
                runStatus = RunStatus.COMPLETED
                settled = true
                break
            }
            val state = steps[pointer]
            when (state.status) {
                StepStatus.COMPLETED, StepStatus.SKIPPED -> {
                    pointer += 1
                    continue
                }
                StepStatus.FAILED -> {
                    // Branch-on-failure rescue: if a later branch step explicitly
                    // watches this step's FAILED outcome, divert there instead of
                    // dying. Steps between the failure and the branch are skipped
                    // (the run diverged here). Otherwise the run fails as usual.
                    val failedId = state.spec.id
                    val rescueOffset = steps.drop(pointer + 1).indexOfFirst { s ->
                        s.spec.kind == StepKind.BRANCH &&
                            s.spec.branches.any {
                                it.whenStep == failedId && it.outcome == BranchOutcome.FAILED
                            }
                    }
                    if (rescueOffset >= 0) {
                        val branchIndex = pointer + 1 + rescueOffset
                        val diverted = steps.mapIndexed { i, s ->
                            if (i in pointer + 1 until branchIndex && s.status == StepStatus.PENDING) {
                                s.copy(
                                    status = StepStatus.SKIPPED,
                                    failure = "skipped: run diverged at failed step '$failedId'",
                                )
                            } else {
                                s
                            }
                        }
                        steps.clear()
                        steps.addAll(diverted)
                        pointer = branchIndex
                        continue
                    }
                    runStatus = RunStatus.FAILED
                    runFailure = "step '${state.spec.id}' failed: ${state.failure.take(Limits.MAX_GATE_SUMMARY_CHARS)}"
                    settled = true
                    break
                }
                StepStatus.WAITING_USER -> {
                    runStatus = RunStatus.WAITING_USER
                    settled = true
                    break
                }
                StepStatus.NEEDS_REVIEW -> {
                    runStatus = RunStatus.NEEDS_REVIEW
                    settled = true
                    break
                }
                StepStatus.INTERRUPTED -> {
                    runStatus = RunStatus.INTERRUPTED
                    runFailure = state.failure.take(Limits.MAX_GATE_SUMMARY_CHARS)
                    settled = true
                    break
                }
                StepStatus.RUNNING, StepStatus.PENDING -> {
                    if (state.status == StepStatus.RUNNING && state.externalTicket.isNotEmpty()) {
                        // External work is in flight. Nothing else runs, and the
                        // executor is NOT re-invoked, until deliverExternal lands.
                        runStatus = RunStatus.RUNNING
                        settled = true
                        break
                    }
                    val now = clock()
                    if (state.resumeAt > now) {
                        // Bounded backoff: hand back to the driver until resumeAt.
                        runStatus = RunStatus.RUNNING
                        settled = true
                        break
                    }
                    if (state.spec.kind == StepKind.BRANCH) {
                        val jump = evaluateBranch(steps, pointer)
                        steps.clear()
                        steps.addAll(jump.steps)
                        pointer = jump.nextPointer
                        if (jump.runStatus != null) {
                            runStatus = jump.runStatus
                            runFailure = jump.failure
                            settled = true
                            break
                        }
                        continue
                    }
                    val outcome = executeStepAt(existing, steps, pointer)
                    steps[outcome.stepIndex] = outcome.state
                    when (outcome) {
                        is StepOutcome.Done -> {
                            outcome.outbound?.let { audit?.record(it) }
                            pointer = if (outcome.advancePointer) pointer + 1 else pointer
                            continue
                        }
                        is StepOutcome.GateOpened -> {
                            runStatus = RunStatus.WAITING_USER
                            settled = true
                            break
                        }
                        is StepOutcome.Pending -> {
                            // External work started; the run waits for deliverExternal.
                            runStatus = RunStatus.RUNNING
                            settled = true
                            break
                        }
                        is StepOutcome.RetryLater -> {
                            runStatus = RunStatus.RUNNING
                            settled = true
                            break
                        }
                        is StepOutcome.HardFail -> {
                            // The failed state is already stored at steps[index]. Loop back so
                            // the FAILED branch above decides: a later branch watching this
                            // FAILED outcome rescues the run (D-013), otherwise the run fails.
                            continue
                        }
                        is StepOutcome.Interrupted -> {
                            runStatus = RunStatus.INTERRUPTED
                            runFailure = outcome.state.failure.take(Limits.MAX_GATE_SUMMARY_CHARS)
                            settled = true
                            break
                        }
                    }
                }
            }
        }
        if (!settled) {
            runStatus = RunStatus.FAILED
            runFailure = "engine guard: step budget exceeded before the plan settled"
        }
        val updated = AgentRun(
            id = existing.id,
            spec = existing.spec,
            status = runStatus,
            createdAt = existing.createdAt,
            updatedAt = clock(),
            routingNote = routingNote.take(Limits.MAX_ROUTING_NOTE_CHARS),
            failure = runFailure.take(Limits.MAX_GATE_SUMMARY_CHARS),
            steps = steps.toList(),
            pointer = pointer,
        )
        runs[runId] = updated
        return updated
    }

    /**
     * Human decision on an open gate. The delivered hash must equal the
     * artifact hash captured at gate-open (approval binds content):
     * mismatch -> NEEDS_REVIEW, veto -> gate skipped + side-effect steps up
     * to the NEXT gate skipped, approval -> gate completes (artifact kept in
     * output for downstream persist/binding) and the run continues.
     */
    fun deliverGate(
        runId: String,
        stepId: String,
        approved: Boolean,
        contentHashAtApproval: String,
        note: String,
    ): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        val gateIndex = existing.steps.indexOfFirst { it.spec.id == stepId }
        check(gateIndex >= 0) { "unknown gate step $stepId" }
        val state = existing.steps[gateIndex]
        check(state.spec.kind == StepKind.HUMAN_APPROVAL) { "step $stepId is not a gate" }
        check(state.status == StepStatus.WAITING_USER) { "gate $stepId is not waiting (status ${state.status})" }
        val now = clock()
        val trimmedNote = note.trim().take(Limits.MAX_GATE_SUMMARY_CHARS)

        val nextGate = nextGateIndexAfter(existing, gateIndex)
        val steps = existing.steps.toMutableList()
        var status = existing.status

        when {
            !approved -> {
                steps[gateIndex] = state.copy(
                    status = StepStatus.SKIPPED,
                    failure = "vetoed: $trimmedNote",
                    lastAttemptAt = now,
                )
                for (i in gateIndex + 1 until nextGate) {
                    if (steps[i].spec.isSideEffect && steps[i].status == StepStatus.PENDING) {
                        steps[i] = steps[i].copy(
                            status = StepStatus.SKIPPED,
                            failure = "vetoed by gate '$stepId'",
                        )
                    }
                }
                status = RunStatus.RUNNING
            }
            contentHashAtApproval != state.artifactContentHash -> {
                steps[gateIndex] = state.copy(
                    status = StepStatus.NEEDS_REVIEW,
                    failure = "approval binding mismatch: content changed between render and approval",
                    lastAttemptAt = now,
                )
                status = RunStatus.NEEDS_REVIEW
            }
            else -> {
                steps[gateIndex] = state.copy(
                    status = StepStatus.COMPLETED,
                    output = state.artifact,
                    approvedContentHash = contentHashAtApproval,
                    lastAttemptAt = now,
                    failure = "",
                )
                status = RunStatus.RUNNING
            }
        }
        val vetoed = !approved
        val routingNote = if (vetoed) {
            (existing.routingNote + " | vetoed at '$stepId'").take(Limits.MAX_ROUTING_NOTE_CHARS)
        } else {
            existing.routingNote
        }
        val updated = existing.copy(
            steps = steps.toList(),
            status = status,
            updatedAt = now,
            routingNote = routingNote,
            failure = "",
        )
        runs[runId] = updated
        return if (status == RunStatus.RUNNING) advance(runId) else updated
    }

    /**
     * Delivers the final result of external work started by an executor that
     * returned [Attempt.Pending]. The ticket must match the one the engine
     * stored; [attempt] may be Completed, Failed or Measured. It goes through
     * the same settlement path as a synchronous result (retry/backoff, branch
     * rescue, metric persist, outbound event), then the run advances.
     */
    fun deliverExternal(runId: String, stepId: String, ticket: String, attempt: Attempt): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        val index = existing.steps.indexOfFirst { it.spec.id == stepId }
        require(index >= 0) { "unknown step $stepId" }
        val state = existing.steps[index]
        check(state.status == StepStatus.RUNNING && state.externalTicket.isNotEmpty()) {
            "step $stepId has no external work in flight (status ${state.status})"
        }
        require(ticket == state.externalTicket) { "ticket does not match the one issued for step $stepId" }
        require(attempt !is Attempt.Pending) { "deliverExternal takes a final result, not Pending" }
        check(!state.externalTicket.startsWith(INFLIGHT_PREFIX)) {
            "step $stepId has an unconfirmed side-effect attempt (checkpoint only); it must be reviewed, not delivered"
        }
        if (state.spec.isSideEffect) {
            check(approvedHashBefore(existing.steps, index).isNotEmpty()) {
                "side-effect step $stepId has no approval binding; refusing to settle it"
            }
        }
        val now = clock()
        val outcome = settleAttempt(
            runId = runId,
            index = index,
            state = state.copy(externalTicket = ""),
            attempt = state.attempts,
            now = now,
            result = attempt,
        )
        (outcome as? StepOutcome.Done)?.outbound?.let { audit?.record(it) }
        val steps = existing.steps.toMutableList()
        steps[index] = outcome.state
        val updated = existing.copy(
            steps = steps.toList(),
            updatedAt = now,
            failure = "",
        )
        runs[runId] = updated
        return advance(runId)
    }

    /**
     * Process-death resume law: side-effect steps marked INTERRUPTED never
     * auto-resume — they land in NEEDS_REVIEW for a human decision; pure
     * steps (reason/branch/persist/metric) re-arm as PENDING. External work
     * in flight follows the same law: a side effect with a ticket goes to
     * NEEDS_REVIEW (the ticket is kept so a human can look it up); a pure
     * step with a ticket is re-armed and its ticket cleared.
     */
    fun resumeInterrupted(runId: String): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        val hasExternalWork = existing.steps.any {
            it.status == StepStatus.RUNNING && it.externalTicket.isNotEmpty()
        }
        check(existing.status == RunStatus.INTERRUPTED || hasExternalWork) {
            "run ${existing.id} is not interrupted (status ${existing.status})"
        }
        val (steps, needsReview) = recoverSteps(existing.steps)
        val status = if (needsReview) RunStatus.NEEDS_REVIEW else RunStatus.RUNNING
        val updated = existing.copy(steps = steps, status = status, updatedAt = clock(), failure = "")
        runs[runId] = updated
        return if (status == RunStatus.RUNNING) advance(runId) else updated
    }

    /**
     * Crash recovery for a run restored with [putRun]. Applies the same rules
     * as [resumeInterrupted]: a side effect that was RUNNING with a ticket
     * (including an "inflight:" checkpoint ticket) or INTERRUPTED becomes
     * NEEDS_REVIEW; pure steps re-arm as PENDING.
     *
     * Unlike [resumeInterrupted] this never calls [advance], so no executor is
     * invoked here. The app drives [advance] itself when the returned run is
     * RUNNING. Only RUNNING or INTERRUPTED runs are accepted.
     */
    fun recoverAfterRestart(runId: String): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        check(existing.status == RunStatus.RUNNING || existing.status == RunStatus.INTERRUPTED) {
            "run ${existing.id} cannot be recovered from status ${existing.status}"
        }
        val (steps, needsReview) = recoverSteps(existing.steps)
        val status = if (needsReview) RunStatus.NEEDS_REVIEW else RunStatus.RUNNING
        val updated = existing.copy(steps = steps, status = status, updatedAt = clock(), failure = "")
        runs[runId] = updated
        return updated
    }

    /** Shared step-recovery law for [resumeInterrupted] and [recoverAfterRestart]. */
    private fun recoverSteps(steps: List<StepState>): Pair<List<StepState>, Boolean> {
        var needsReview = false
        val recovered = steps.map { state ->
            if (state.status == StepStatus.RUNNING && state.externalTicket.isNotEmpty()) {
                if (state.spec.isSideEffect) {
                    needsReview = true
                    state.copy(
                        status = StepStatus.NEEDS_REVIEW,
                        failure = "external work in flight when the process stopped " +
                            "(ticket ${state.externalTicket.take(Limits.MAX_GATE_SUMMARY_CHARS)}); " +
                            "a human must check it before anything is retried",
                    )
                } else {
                    state.copy(status = StepStatus.PENDING, failure = "", resumeAt = 0, externalTicket = "")
                }
            } else if (state.status != StepStatus.INTERRUPTED) {
                state
            } else if (state.spec.isSideEffect) {
                needsReview = true
                state.copy(
                    status = StepStatus.NEEDS_REVIEW,
                    failure = "interrupted mid side effect; context must be re-verified by a human",
                )
            } else {
                state.copy(status = StepStatus.PENDING, failure = "", resumeAt = 0)
            }
        }
        return recovered to needsReview
    }

    /** Resolve a NEEDS_REVIEW step: retry=true re-arms it, false skips it. */
    fun resolveReview(runId: String, stepId: String, retry: Boolean): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        val index = existing.steps.indexOfFirst { it.spec.id == stepId }
        check(index >= 0) { "unknown step $stepId" }
        check(existing.steps[index].status == StepStatus.NEEDS_REVIEW) {
            "step $stepId is not in review (status ${existing.steps[index].status})"
        }
        val steps = existing.steps.toMutableList()
        steps[index] = if (retry) {
            existing.steps[index].copy(status = StepStatus.PENDING, failure = "", externalTicket = "")
        } else {
            existing.steps[index].copy(status = StepStatus.SKIPPED, failure = "skipped after review")
        }
        val updated = existing.copy(
            steps = steps.toList(),
            status = RunStatus.RUNNING,
            updatedAt = clock(),
            failure = "",
        )
        runs[runId] = updated
        return advance(runId)
    }

    fun cancel(runId: String, reason: String): AgentRun {
        val existing = runs[runId] ?: throw IllegalArgumentException("unknown run $runId")
        if (existing.status in TERMINAL_STATUSES) return existing
        val now = clock()
        val note = reason.trim().take(Limits.MAX_GATE_SUMMARY_CHARS).ifEmpty { "cancelled" }
        val steps = existing.steps.map { state ->
            if (state.status in CANCELLABLE_STEP_STATUSES) {
                state.copy(status = StepStatus.SKIPPED, failure = "run cancelled: $note", externalTicket = "")
            } else {
                state
            }
        }
        val updated = existing.copy(
            steps = steps,
            status = RunStatus.CANCELLED,
            updatedAt = now,
            failure = note,
        )
        runs[runId] = updated
        return updated
    }

    // -------------------------------------------------- step execution core

    private sealed interface StepOutcome {
        val stepIndex: Int
        val state: StepState

        data class Done(
            override val stepIndex: Int,
            override val state: StepState,
            val advancePointer: Boolean,
            val outbound: OutboundEvent? = null,
        ) : StepOutcome

        data class GateOpened(override val stepIndex: Int, override val state: StepState) : StepOutcome
        data class Pending(override val stepIndex: Int, override val state: StepState) : StepOutcome
        data class RetryLater(override val stepIndex: Int, override val state: StepState) : StepOutcome
        data class HardFail(override val stepIndex: Int, override val state: StepState) : StepOutcome
        data class Interrupted(override val stepIndex: Int, override val state: StepState) : StepOutcome
    }

    private class BranchJump(
        val steps: List<StepState>,
        val nextPointer: Int,
        val runStatus: RunStatus?,
        val failure: String = "",
    )

    private fun evaluateBranch(steps: List<StepState>, index: Int): BranchJump {
        val state = steps[index]
        val branch = state.spec.branches.firstOrNull { b ->
            val ref = steps.firstOrNull { it.spec.id == b.whenStep } ?: return@firstOrNull false
            when (b.outcome) {
                BranchOutcome.SUCCEEDED -> ref.status == StepStatus.COMPLETED
                BranchOutcome.FAILED -> ref.status == StepStatus.FAILED
            }
        }
        return if (branch != null) {
            val targetIndex = steps.indexOfFirst { it.spec.id == branch.goto }
            if (targetIndex < 0) {
                // Policy validated this; defensive fallthrough keeps determinism.
                val done = state.copy(
                    status = StepStatus.COMPLETED,
                    output = "no condition matched; fallthrough",
                    lastAttemptAt = clock(),
                )
                BranchJump(steps.withBranchDone(index, done), index + 1, null)
            } else {
                val now = clock()
                val marked = steps.mapIndexed { i, s ->
                    if (i in index + 1 until targetIndex && s.status == StepStatus.PENDING) {
                        s.copy(status = StepStatus.SKIPPED, failure = "skipped by branch '${state.spec.id}'")
                    } else if (i == index) {
                        s.copy(
                            status = StepStatus.COMPLETED,
                            output = "branch -> '${branch.goto}'",
                            lastAttemptAt = now,
                        )
                    } else {
                        s
                    }
                }
                BranchJump(marked, targetIndex, null)
            }
        } else {
            val done = state.copy(
                status = StepStatus.COMPLETED,
                output = "no condition matched; fallthrough",
                lastAttemptAt = clock(),
            )
            BranchJump(steps.withBranchDone(index, done), index + 1, null)
        }
    }

    private fun List<StepState>.withBranchDone(index: Int, done: StepState): List<StepState> {
        val out = toMutableList()
        out[index] = done
        return out
    }

    private fun executeStepAt(existing: AgentRun, steps: List<StepState>, index: Int): StepOutcome {
        val runId = existing.id
        val state = steps[index]
        val step = state.spec
        val now = clock()
        val attempt = state.attempts + 1
        val idem = "$runId${Limits.IDEMPOTENCY_SEPARATOR}${step.id}${Limits.IDEMPOTENCY_SEPARATOR}$attempt"

        val priorOutputs = buildPriorOutputs(steps, index)
        val priorEvidence = steps.subList(0, index)
            .flatMap { it.evidence }
            .take(Limits.MAX_EVIDENCE_PER_STEP * 2)
        val approvedHash = approvedHashBefore(steps, index)

        val ctx = ExecContext(
            runId = runId,
            stepId = step.id,
            attempt = attempt,
            idempotencyKey = idem,
            now = now,
            packet = packetProvider(),
            approvedArtifactHash = approvedHash,
            priorOutputs = priorOutputs,
            collectedEvidence = priorEvidence,
        )

        if (step.isSideEffect && approvedHash.isEmpty()) {
            val gate = nearestGateBefore(steps, index)
            val why = if (gate == null) {
                "no preceding HUMAN_APPROVAL gate"
            } else {
                "nearest preceding gate '${gate.spec.id}' is ${gate.status}, not COMPLETED"
            }
            return StepOutcome.HardFail(
                index,
                failState(state, attempt, now, "side-effect step has no approval binding ($why); refusing to execute"),
            )
        }

        val cached = attemptResults[idem]
        if (step.isSideEffect && cached == null) {
            // Checkpoint BEFORE the executor: a crash after this point restores as
            // NEEDS_REVIEW (recoverAfterRestart), never as a re-runnable PENDING step.
            val inflight = state.copy(
                status = StepStatus.RUNNING,
                attempts = attempt,
                externalTicket = INFLIGHT_PREFIX + idem,
                failure = "",
                lastAttemptAt = now,
            )
            val checkpointError = checkpointInflight(existing, steps, index, inflight)
            if (checkpointError != null) {
                return StepOutcome.HardFail(
                    index,
                    failState(state, attempt, now, "could not checkpoint before side effect: $checkpointError"),
                )
            }
        }

        val result: Attempt = try {
            cached ?: runRoute(step, ctx)
        } catch (t: Throwable) {
            attemptResults[idem] = Attempt.Failed("executor threw: ${t.javaClass.simpleName}: ${t.message}")
            return StepOutcome.Interrupted(
                index,
                state.copy(
                    status = StepStatus.INTERRUPTED,
                    attempts = attempt,
                    lastAttemptAt = now,
                    failure = "executor threw: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}",
                ),
            )
        }
        if (attemptResults[idem] == null) {
            attemptResults[idem] = result
        }

        return settleAttempt(runId, index, state, attempt, now, result)
    }

    /**
     * The single settlement path for an executor result, shared by the
     * synchronous attempt and [deliverExternal]. [state] is the step as it
     * stood when the attempt was made; [attempt] is the attempt number it
     * counts as.
     */
    private fun settleAttempt(
        runId: String,
        index: Int,
        state: StepState,
        attempt: Int,
        now: Long,
        result: Attempt,
    ): StepOutcome {
        val step = state.spec
        return when (result) {
            is Attempt.Completed -> completeAttempt(runId, index, state, attempt, now, result)
            is Attempt.Measured -> persistMetric(index, state, attempt, step, result.number, now)
            is Attempt.Failed -> failOrRetry(index, state, attempt, result.reason, now)
            is Attempt.Pending -> when {
                result.ticket.isBlank() ->
                    failOrRetry(index, state, attempt, "executor returned a blank ticket", now)
                result.ticket.length > Limits.MAX_GATE_SUMMARY_CHARS ->
                    failOrRetry(index, state, attempt, "executor returned an oversized ticket", now)
                else -> StepOutcome.Pending(
                    index,
                    state.copy(
                        status = StepStatus.RUNNING,
                        attempts = attempt,
                        externalTicket = result.ticket,
                        failure = "",
                        lastAttemptAt = now,
                    ),
                )
            }
        }
    }

    private fun completeAttempt(
        runId: String,
        index: Int,
        state: StepState,
        attempt: Int,
        now: Long,
        result: Attempt.Completed,
    ): StepOutcome {
        val step = state.spec
        if (step.kind == StepKind.HUMAN_APPROVAL) {
            // A completed gate artifact OPENS the gate; it never closes the step.
            val parsed = runCatching { JSONObject(result.output) }.getOrNull()
            if (parsed == null) {
                return StepOutcome.HardFail(
                    index,
                    failState(state, attempt, now, "gate artifact is not valid JSON"),
                )
            }
            val opened = state.copy(
                status = StepStatus.WAITING_USER,
                attempts = attempt,
                artifact = result.output.take(Limits.MAX_OUTPUT_CHARS),
                artifactContentHash = CanonicalJson.contentHash(parsed),
                failure = "",
                lastAttemptAt = now,
            )
            return StepOutcome.GateOpened(index, opened)
        }
        val evidence = result.evidence
            .filterNot { it.isInvalid() }
            .take(Limits.MAX_EVIDENCE_PER_STEP)
        val completed = state.copy(
            status = StepStatus.COMPLETED,
            attempts = attempt,
            output = result.output.take(Limits.MAX_OUTPUT_CHARS),
            evidence = evidence,
            failure = "",
            lastAttemptAt = now,
        )
        val outbound = if (step.isSideEffect) outboundEventFor(runId, step, completed) else null
        return StepOutcome.Done(index, completed, advancePointer = true, outbound = outbound)
    }

    /** The nearest HUMAN_APPROVAL step before [index], whatever its status. */
    private fun nearestGateBefore(steps: List<StepState>, index: Int): StepState? =
        steps.subList(0, index).lastOrNull { it.spec.kind == StepKind.HUMAN_APPROVAL }

    /**
     * Content hash a side effect at [index] is bound to: the approval of the
     * NEAREST preceding gate, and only when that gate is COMPLETED. An older
     * COMPLETED gate never covers a later side effect when a newer gate was
     * skipped by a veto, a branch jump or a failure rescue (D-023). Returns ""
     * when there is no binding.
     */
    private fun approvedHashBefore(steps: List<StepState>, index: Int): String {
        val gate = nearestGateBefore(steps, index) ?: return ""
        return if (gate.status == StepStatus.COMPLETED) gate.approvedContentHash else ""
    }

    /**
     * Persists the run with [inflight] at [index] before a side-effect executor
     * runs. Returns null on success, or the failure text if the checkpoint
     * threw. The run is stored in memory first, so the caller's final store
     * stays consistent with it.
     */
    private fun checkpointInflight(
        existing: AgentRun,
        steps: List<StepState>,
        index: Int,
        inflight: StepState,
    ): String? {
        val snapshot = steps.toMutableList()
        snapshot[index] = inflight
        val run = existing.copy(
            status = RunStatus.RUNNING,
            steps = snapshot.toList(),
            pointer = index,
            updatedAt = clock(),
            failure = "",
        )
        runs[existing.id] = run
        return try {
            checkpoint(run)
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message.orEmpty().take(200)}"
        }
    }

    private fun runRoute(step: StepSpec, ctx: ExecContext): Attempt = when (step.kind) {
        StepKind.REASON -> routes.reason.execute(ctx, step)
        StepKind.BROWSER_AUTOMATION -> routes.browser.execute(ctx, step)
        StepKind.APP_AUTOMATION -> routes.app.execute(ctx, step)
        StepKind.METRIC_COLLECT -> routes.metric.execute(ctx, step)
        StepKind.DATA_PERSIST -> dataPersist(ctx, step)
        StepKind.HUMAN_APPROVAL -> gateOpen(ctx, step)
        StepKind.BRANCH, StepKind.RETRY ->
            Attempt.Failed("${step.kind.name} steps are engine-internal and must not reach executors")
    }

    private fun dataPersist(ctx: ExecContext, step: StepSpec): Attempt {
        if (step.artifactType !in Prompts.ARTIFACT_KINDS) {
            return Attempt.Failed("data_persist artifactType '${step.artifactType}' is not a registry kind")
        }
        val payloadJson = ctx.priorOutputs.values.lastOrNull { it.trim().startsWith("{") }
            ?: return Attempt.Failed("data_persist needs a JSON artifact from a prior step")
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()
            ?: return Attempt.Failed("data_persist prior output is not valid JSON")
        val id = rowNaturalId(step.artifactType, payload)
        if (id.isBlank()) {
            return Attempt.Failed("data_persist could not derive a natural key for '${step.artifactType}'")
        }
        val error = routes.dataStore.upsert(step.artifactType, id, payloadJson)
        if (error.isNotEmpty()) {
            return Attempt.Failed("data_persist rejected by store: $error")
        }
        return Attempt.Completed(output = "persisted:$id", evidence = emptyList())
    }

    private fun gateOpen(ctx: ExecContext, step: StepSpec): Attempt =
        when (val built = routes.artifactBuilder.build(ctx, step.artifactType, ctx.priorOutputs)) {
            is Attempt.Measured -> Attempt.Failed("gate artifact builder returned a measurement")
            else -> built
        }

    private fun persistMetric(
        index: Int,
        state: StepState,
        attempt: Int,
        step: StepSpec,
        number: OutcomeParser.ParsedNumber,
        now: Long,
    ): StepOutcome {
        val postKey = NaturalKeys.post(step.metricUrl)
        if (postKey.isBlank()) {
            return StepOutcome.HardFail(index, failState(state, attempt, now, "metric step has no usable URL"))
        }
        // Keep the graph connected: capture belongs to a post shell (url-only row).
        val postJson = JSONObject()
            .put("url", step.metricUrl)
            .put("platform", "")
            .put("excerpt", "")
        val postError = routes.dataStore.upsert("post", postKey, postJson.toString())
        if (postError.isNotEmpty()) {
            return StepOutcome.HardFail(
                index,
                failState(state, attempt, now, "metric post shell rejected by store: $postError"),
            )
        }
        val sampleJson = JSONObject()
            .put("postKey", postKey)
            .put("metric", step.metricKey)
            .put("value", number.value)
            .put("capturedAt", now)
        val sampleId = NaturalKeys.metric(postKey, step.metricKey, now)
        val sampleError = routes.dataStore.upsert("metric_sample", sampleId, sampleJson.toString())
        if (sampleError.isNotEmpty()) {
            return StepOutcome.HardFail(
                index,
                failState(state, attempt, now, "metric sample rejected by store: $sampleError"),
            )
        }
        val approx = if (number.approximated) " (approximated)" else ""
        return StepOutcome.Done(
            index,
            state.copy(
                status = StepStatus.COMPLETED,
                attempts = attempt,
                output = "measured ${step.metricKey}=${number.value}$approx",
                failure = "",
                lastAttemptAt = now,
            ),
            advancePointer = true,
        )
    }

    private fun failOrRetry(
        index: Int,
        state: StepState,
        attempt: Int,
        reason: String,
        now: Long,
    ): StepOutcome {
        val retry = state.spec.retry
        return if (retry != null && attempt < retry.attemptsMax) {
            val backoff = retry.backoffFor(attempt)
            StepOutcome.RetryLater(
                index,
                state.copy(
                    status = StepStatus.PENDING,
                    attempts = attempt,
                    resumeAt = now + backoff,
                    failure = reason.take(Limits.MAX_GATE_SUMMARY_CHARS),
                    lastAttemptAt = now,
                ),
            )
        } else {
            StepOutcome.HardFail(index, failState(state, attempt, now, reason))
        }
    }

    private fun failState(state: StepState, attempt: Int, now: Long, reason: String): StepState =
        state.copy(
            status = StepStatus.FAILED,
            attempts = attempt,
            failure = reason.take(Limits.MAX_GATE_SUMMARY_CHARS),
            lastAttemptAt = now,
        )

    private fun buildPriorOutputs(steps: List<StepState>, upTo: Int): Map<String, String> =
        steps.subList(0, upTo)
            .filter { it.status == StepStatus.COMPLETED && it.output.isNotBlank() }
            .associate { it.spec.id to it.output }

    private fun outboundEventFor(
        runId: String,
        step: StepSpec,
        completed: StepState,
    ): OutboundEvent {
        val channel = when (step.kind) {
            StepKind.BROWSER_AUTOMATION -> OutboundChannel.FRONTIER_SITE
            StepKind.APP_AUTOMATION -> OutboundChannel.AI_APP
            else -> OutboundChannel.API_OPTIN
        }
        val target = when (step.kind) {
            StepKind.BROWSER_AUTOMATION -> step.provider
            StepKind.APP_AUTOMATION -> step.appPackage
            else -> step.provider.ifEmpty { step.appPackage }
        }
        return OutboundEvent(
            runId = runId,
            stepId = step.id,
            channel = channel,
            target = target.take(Limits.AUDIT_TARGET_MAX_CHARS),
            action = "completed: ${step.title}".take(Limits.AUDIT_ACTION_MAX_CHARS),
            contentHash = CanonicalJson.contentHash(completed.output),
        )
    }

    /** Natural-key derivation for registry kinds (must mirror NaturalKeys rules). */
    private fun rowNaturalId(kind: String, payload: JSONObject): String = when (kind) {
        "person" -> NaturalKeys.person(
            payload.optString("fullName"),
            payload.optString("phone"),
        )
        "company" -> NaturalKeys.company(payload.optString("domain"))
        "job_posting" -> NaturalKeys.job(
            payload.optString("companyKey"),
            payload.optString("role"),
        )
        "application" -> NaturalKeys.application(payload.optString("jobKey"))
        "task" -> NaturalKeys.task(payload.optString("title"), payload.optLong("dueAt", 0L))
        "draft" -> NaturalKeys.draft(
            payload.optString("kind", "POST"),
            payload.optString("title"),
            payload.optString("body").take(48),
            NaturalKeys.dayBucket(payload.optLong("createdAt", 0L)),
        )
        "post" -> NaturalKeys.post(payload.optString("url"))
        else -> ""
    }

    private fun nextGateIndexAfter(run: AgentRun, gateIndex: Int): Int {
        for (i in gateIndex + 1 until run.steps.size) {
            if (run.steps[i].spec.kind == StepKind.HUMAN_APPROVAL) return i
        }
        return run.steps.size
    }

    companion object {
        /** Ticket prefix marking a side-effect attempt checkpointed before its executor ran. */
        const val INFLIGHT_PREFIX: String = "inflight:"

        private val RESUMABLE_STATUSES = setOf(RunStatus.PENDING, RunStatus.RUNNING)
        private val TERMINAL_STATUSES = setOf(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED,
        )
        private val CANCELLABLE_STEP_STATUSES = setOf(
            StepStatus.PENDING, StepStatus.RUNNING, StepStatus.WAITING_USER,
        )
    }
}
