package app.memo.core.domain

import app.memo.core.Limits
import org.json.JSONArray
import org.json.JSONObject

/**
 * The workflow DSL (schemaVersion 1). Exactly these eight step kinds exist;
 * a plan proposing anything else is rejected by the PlanProposalParser.
 */
enum class StepKind {
    REASON,             // model-only derivation; no side effects
    BROWSER_AUTOMATION, // frontier website interaction in Memo's hardened WebView
    APP_AUTOMATION,     // native app steps via accessibility executor
    HUMAN_APPROVAL,     // gate: filled artifact + evidence presented for binding
    DATA_PERSIST,       // registry upsert with natural key + version bump
    METRIC_COLLECT,     // read-only capture of one MetricSample
    BRANCH,             // deterministic switch on prior step outcomes
    RETRY,              // declarative; folded onto its target step at compile time
}

/** State of one step within a run. */
enum class StepStatus { PENDING, RUNNING, WAITING_USER, COMPLETED, FAILED, SKIPPED, NEEDS_REVIEW, INTERRUPTED }

/** State of the whole run. */
enum class RunStatus { PENDING, RUNNING, WAITING_USER, COMPLETED, FAILED, CANCELLED, INTERRUPTED, NEEDS_REVIEW }

/** What condition watches: the referenced step's outcome. */
enum class BranchOutcome { SUCCEEDED, FAILED }

/** Deterministic branch row: "when STEP shows OUTCOME, go to TARGET". */
data class BranchSpec(
    val whenStep: String = "",
    val outcome: BranchOutcome = BranchOutcome.SUCCEEDED,
    val goto: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("whenStep", whenStep)
        .put("outcome", outcome.name)
        .put("goto", goto)

    companion object {
        fun fromJson(raw: JSONObject): BranchSpec? {
            val whenStep = raw.optString("whenStep")
            val goto = raw.optString("goto")
            if (whenStep.isBlank() || goto.isBlank()) return null
            val outcome = runCatching {
                BranchOutcome.valueOf(raw.optString("outcome", "SUCCEEDED"))
            }.getOrDefault(BranchOutcome.SUCCEEDED)
            return BranchSpec(whenStep, outcome, goto)
        }
    }
}

/** Retry policy attached to a step (or folded from a RETRY declaration). */
data class RetrySpec(
    val attemptsMax: Int = 3,
    val backoffFirstMs: Long = Limits.RETRY_BACKOFF_FIRST_MS,
    val backoffMaxMs: Long = Limits.RETRY_BACKOFF_MAX_MS,
) {
    fun backoffFor(attempt: Int): Long {
        // attempt counts completed failures (1-based): 4s, 8s, 16s ... capped
        val shift = (attempt - 1).coerceIn(0, 4)
        val raw = backoffFirstMs * (1L shl shift)
        return raw.coerceAtMost(backoffMaxMs)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("attemptsMax", attemptsMax)
        .put("backoffFirstMs", backoffFirstMs)
        .put("backoffMaxMs", backoffMaxMs)

    companion object {
        fun fromJson(raw: JSONObject): RetrySpec = RetrySpec(
            attemptsMax = raw.optInt("attemptsMax", 3).coerceIn(
                Limits.MIN_STEP_ATTEMPTS,
                Limits.MAX_STEP_ATTEMPTS,
            ),
            backoffFirstMs = raw.optLong("backoffFirstMs", Limits.RETRY_BACKOFF_FIRST_MS)
                .coerceIn(1L, 120_000L),
            backoffMaxMs = raw.optLong("backoffMaxMs", Limits.RETRY_BACKOFF_MAX_MS)
                .coerceAtLeast(raw.optLong("backoffFirstMs", Limits.RETRY_BACKOFF_FIRST_MS)),
        )
    }
}

/**
 * One plan step. Caps are enforced by WorkflowPolicy at compile time; the
 * runtime treats the compiled spec as law.
 */
data class StepSpec(
    val id: String = "",
    val kind: StepKind = StepKind.REASON,
    val title: String = "",
    val prompt: String = "",          // REASON input
    val provider: String = "",        // BROWSER_AUTOMATION provider id
    val appPackage: String = "",      // APP_AUTOMATION target package
    val artifactType: String = "",    // DATA_PERSIST / HUMAN_APPROVAL artifact kind
    val artifactHint: String = "",    // free text describing what to fill
    val metricKey: String = "",       // METRIC_COLLECT metric name
    val metricUrl: String = "",       // METRIC_COLLECT page (https only)
    val requiresGate: Boolean = false,// side-effect steps carry binding approval
    val target: String = "",          // RETRY declaration target
    val retry: RetrySpec? = null,
    val branches: List<BranchSpec> = emptyList(),
) {
    val isSideEffect: Boolean
        get() = kind == StepKind.BROWSER_AUTOMATION && !isBrowserCapture ||
            kind == StepKind.APP_AUTOMATION

    /** BROWSER_AUTOMATION steps may declare read-only capture via artifactHint prefix. */
    val isBrowserCapture: Boolean
        get() = kind == StepKind.BROWSER_AUTOMATION && artifactHint.startsWith("capture:")

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("kind", kind.name)
            .put("title", title)
        if (prompt.isNotEmpty()) o.put("prompt", prompt)
        if (provider.isNotEmpty()) o.put("provider", provider)
        if (appPackage.isNotEmpty()) o.put("appPackage", appPackage)
        if (artifactType.isNotEmpty()) o.put("artifactType", artifactType)
        if (artifactHint.isNotEmpty()) o.put("artifactHint", artifactHint)
        if (metricKey.isNotEmpty()) o.put("metricKey", metricKey)
        if (metricUrl.isNotEmpty()) o.put("metricUrl", metricUrl)
        if (requiresGate) o.put("requiresGate", true)
        if (target.isNotEmpty()) o.put("target", target)
        retry?.let { o.put("retry", it.toJson()) }
        if (branches.isNotEmpty()) {
            val arr = JSONArray()
            branches.forEach { arr.put(it.toJson()) }
            o.put("branches", arr)
        }
        return o
    }

    companion object {
        fun fromJson(raw: JSONObject): StepSpec? {
            val id = raw.optString("id")
            val kind = runCatching {
                StepKind.valueOf(raw.optString("kind"))
            }.getOrDefault(StepKind.REASON)
            if (id.isBlank()) return null
            val branches = raw.optJSONArray("branches")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        add(BranchSpec.fromJson(item) ?: continue)
                    }
                }
            } ?: emptyList()
            val retryRaw = raw.optJSONObject("retry")
            return StepSpec(
                id = id,
                kind = kind,
                title = raw.optString("title"),
                prompt = raw.optString("prompt"),
                provider = raw.optString("provider"),
                appPackage = raw.optString("appPackage"),
                artifactType = raw.optString("artifactType"),
                artifactHint = raw.optString("artifactHint"),
                metricKey = raw.optString("metricKey"),
                metricUrl = raw.optString("metricUrl"),
                requiresGate = raw.optBoolean("requiresGate", false),
                target = raw.optString("target"),
                retry = retryRaw?.let { RetrySpec.fromJson(it) },
                branches = branches,
            )
        }
    }
}

/** A compiled plan. `schemaVersion` ties it to the DSL contract. */
data class WorkflowSpec(
    val id: String = "",
    val title: String = "",
    val createdAt: Long = 0,
    val steps: List<StepSpec> = emptyList(),
) {
    fun stepById(id: String): StepSpec? = steps.firstOrNull { it.id == id }

    fun toJson(): JSONObject {
        val stepsArr = JSONArray()
        steps.forEach { stepsArr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("createdAt", createdAt)
            .put("schemaVersion", app.memo.core.Version.DSL_SCHEMA_VERSION)
            .put("steps", stepsArr)
    }

    companion object {
        fun fromJson(raw: JSONObject): WorkflowSpec {
            val stepsArr = raw.optJSONArray("steps") ?: JSONArray()
            return WorkflowSpec(
                id = raw.optString("id"),
                title = raw.optString("title"),
                createdAt = raw.optLong("createdAt", 0L),
                steps = buildList {
                    for (i in 0 until stepsArr.length()) {
                        val item = stepsArr.optJSONObject(i) ?: continue
                        add(StepSpec.fromJson(item) ?: continue)
                    }
                },
            )
        }
    }
}

/** Runtime state of one step inside a run. */
data class StepState(
    val spec: StepSpec,
    val status: StepStatus = StepStatus.PENDING,
    val attempts: Int = 0,
    val output: String = "",
    val evidence: List<Evidence> = emptyList(),
    val failure: String = "",
    val artifact: String = "",            // gate artifact JSON (filled before approval)
    val artifactContentHash: String = "",  // CanonicalJson.contentHash of artifact JSON
    val approvedContentHash: String = "",  // hash the user actually bound at the gate
    val lastAttemptAt: Long = 0,
    val resumeAt: Long = 0,               // earliest next execution (backoff / deferral)
    val externalTicket: String = "",      // ticket of in-flight external work (async Attempt.Pending)
) {
    fun toJson(): JSONObject {
        val evd = JSONArray()
        evidence.forEach { evd.put(it.toJson()) }
        val o = JSONObject()
            .put("spec", spec.toJson())
            .put("status", status.name)
            .put("attempts", attempts)
            .put("output", output)
            .put("failure", failure)
            .put("artifact", artifact)
            .put("artifactContentHash", artifactContentHash)
            .put("approvedContentHash", approvedContentHash)
            .put("lastAttemptAt", lastAttemptAt)
            .put("resumeAt", resumeAt)
            .put("externalTicket", externalTicket)
            .put("evidence", evd)
        return o
    }

    companion object {
        fun fromJson(raw: JSONObject): StepState? {
            val specRaw = raw.optJSONObject("spec") ?: return null
            val spec = StepSpec.fromJson(specRaw) ?: return null
            val status = runCatching {
                StepStatus.valueOf(raw.optString("status", "PENDING"))
            }.getOrDefault(StepStatus.PENDING)
            val evd = raw.optJSONArray("evidence") ?: JSONArray()
            return StepState(
                spec = spec,
                status = status,
                attempts = raw.optInt("attempts", 0).coerceAtLeast(0),
                output = raw.optString("output"),
                failure = raw.optString("failure"),
                artifact = raw.optString("artifact"),
                artifactContentHash = raw.optString("artifactContentHash"),
                approvedContentHash = raw.optString("approvedContentHash"),
                lastAttemptAt = raw.optLong("lastAttemptAt", 0L),
                resumeAt = raw.optLong("resumeAt", 0L),
                externalTicket = raw.optString("externalTicket"),
                evidence = buildList {
                    for (i in 0 until evd.length()) {
                        val item = evd.optJSONObject(i) ?: continue
                        add(Evidence.fromJson(item))
                    }
                },
            )
        }
    }
}

/**
 * A live (or historic) engine run. Persistable so process death resumes with
 * law intact: side-effect steps marked INTERRUPTED do not auto-resume.
 */
data class AgentRun(
    val id: String,
    val spec: WorkflowSpec,
    val status: RunStatus = RunStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val routingNote: String = "",
    val failure: String = "",
    val steps: List<StepState> = emptyList(),
    val pointer: Int = 0,
) {
    fun stepById(id: String): StepState? = steps.firstOrNull { it.spec.id == id }

    fun toJson(): JSONObject {
        val stepsArr = JSONArray()
        steps.forEach { stepsArr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("spec", spec.toJson())
            .put("status", status.name)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("routingNote", routingNote)
            .put("failure", failure)
            .put("pointer", pointer)
            .put("steps", stepsArr)
    }

    companion object {
        fun fromJson(raw: JSONObject): AgentRun? {
            val id = raw.optString("id")
            val specRaw = raw.optJSONObject("spec") ?: return null
            val spec = WorkflowSpec.fromJson(specRaw)
            if (id.isBlank() || spec.steps.isEmpty()) return null
            val status = runCatching {
                RunStatus.valueOf(raw.optString("status", "PENDING"))
            }.getOrDefault(RunStatus.PENDING)
            val stepsArr = raw.optJSONArray("steps") ?: JSONArray()
            val rebuilt = buildList {
                for (i in 0 until stepsArr.length()) {
                    val item = stepsArr.optJSONObject(i) ?: continue
                    add(StepState.fromJson(item) ?: continue)
                }
            }
            if (rebuilt.size != spec.steps.size) return null
            return AgentRun(
                id = id,
                spec = spec,
                status = status,
                createdAt = raw.optLong("createdAt", 0L),
                updatedAt = raw.optLong("updatedAt", 0L),
                routingNote = raw.optString("routingNote"),
                failure = raw.optString("failure"),
                pointer = raw.optInt("pointer", 0).coerceIn(0, rebuilt.size),
                steps = rebuilt,
            )
        }
    }
}
