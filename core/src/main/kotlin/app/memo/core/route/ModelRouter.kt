package app.memo.core.route

import app.memo.core.Limits
import app.memo.core.policy.ChannelSwitches
import java.time.Instant

/** A place a step can run: a provider id, an app package, "local:<modelId>" or "api:<name>". */
data class RouteTarget(
    val id: String,
    val channel: TargetChannel,
    val displayName: String,
    val capabilities: Set<StepRole>,
    val enabled: Boolean,
)

/** The chosen target, a rationale of at most [Limits.MAX_ROUTING_NOTE_CHARS] chars, and every rejected alternative with its reason. */
data class RouteDecision(
    val target: RouteTarget,
    val rationale: String,
    val alternatives: List<String>,
)

sealed interface RouteResult {
    data class Routed(val decision: RouteDecision) : RouteResult
    data class NoRoute(val reason: String) : RouteResult
}

/**
 * Role-affinity table: how well each channel suits each role, in 0..1. Score = affinity x health.
 * Heavy frontier roles (RESEARCH, CRITIQUE, SYNTHESIZE) rate LOCAL low, so the local model is only a
 * fallback there. The table is code, not data, so changes go through review and the engineering log.
 */
object RoleAffinity {
    private fun row(local: Double, web: Double, app: Double, api: Double): Map<TargetChannel, Double> = mapOf(
        TargetChannel.LOCAL to local,
        TargetChannel.WEB to web,
        TargetChannel.APP to app,
        TargetChannel.API to api,
    )

    val TABLE: Map<StepRole, Map<TargetChannel, Double>> = mapOf(
        StepRole.PLAN to row(local = 1.0, web = 0.7, app = 0.6, api = 0.8),
        StepRole.CHAT to row(local = 1.0, web = 0.8, app = 0.7, api = 0.8),
        StepRole.CAPTURE to row(local = 0.9, web = 0.9, app = 0.8, api = 0.7),
        StepRole.DRAFT to row(local = 0.8, web = 0.9, app = 0.7, api = 0.8),
        StepRole.IMAGE to row(local = 0.9, web = 0.8, app = 0.6, api = 0.8),
        StepRole.RESEARCH to row(local = 0.4, web = 1.0, app = 0.8, api = 0.9),
        StepRole.CRITIQUE to row(local = 0.3, web = 1.0, app = 0.8, api = 0.9),
        StepRole.SYNTHESIZE to row(local = 0.6, web = 1.0, app = 0.8, api = 0.9),
    )

    fun of(role: StepRole, channel: TargetChannel): Double = TABLE.getValue(role).getValue(channel)
}

/**
 * Pure routing. Time is supplied as [now]; nothing here reads the clock.
 *
 * Steps: (1) filter to targets that are enabled, list the role, pass the kill switch and channel and
 * provider switches (API needs an explicit `channels["api"] == true`; WEB, APP and API also need the
 * `frontier` channel switch, where a missing key means enabled), and are neither rate-limited nor
 * circuit-open; (2) apply the [RoutingMode] with the pinned target first if eligible and the mode allows
 * its channel (a pin the mode forbids is ignored and reported); (3) rank by
 * affinity x health, ties by id then channel; (4) explain the choice and every rejection; (5) return
 * [RouteResult.NoRoute] naming each blocker when nothing is eligible.
 */
object ModelRouter {

    private const val API_KEY = "api"
    private const val FRONTIER_KEY = "frontier"

    fun route(
        role: StepRole,
        targets: List<RouteTarget>,
        prefs: RoutingPreferences,
        health: ProviderHealth,
        limiter: RateLimiter,
        switches: ChannelSwitches,
        now: Long,
    ): RouteResult {
        val duplicates = targets.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            return RouteResult.NoRoute("Duplicate target ids: ${duplicates.sorted().joinToString()}")
        }

        val ordered = targets.sortedWith(compareBy<RouteTarget> { it.id }.thenBy { it.channel.ordinal })
        val evaluated: List<Pair<RouteTarget, List<String>>> = ordered.map { t ->
            t to blockers(role, t, health, limiter, switches, now)
        }
        val eligible = evaluated.filter { it.second.isEmpty() }.map { it.first }

        val mode = prefs.modeFor(role)
        val primaryChannels = primaryOf(mode)
        val secondaryChannels = secondaryOf(mode)
        val primaryLabel = if (TargetChannel.LOCAL in primaryChannels) "local" else "frontier"

        val pinId = prefs.pinFor(role)
        val pinned: RouteTarget? = pinId?.let { id -> ordered.firstOrNull { it.id == id } }
        // A pin may not leave the side the mode allows: LOCAL_ONLY never takes a frontier pin.
        val pinAllowedByMode = pinned != null && (pinned.channel in primaryChannels || pinned.channel in secondaryChannels)
        val pinnedOk: RouteTarget? = pinned?.takeIf { p -> pinAllowedByMode && eligible.any { it.id == p.id } }

        val ranked = eligible.sortedWith(
            compareByDescending<RouteTarget> { score(role, it, health, now) }
                .thenBy { it.id }
                .thenBy { it.channel.ordinal },
        )
        val primary = ranked.filter { it.channel in primaryChannels }
        val secondary = ranked.filter { it.channel in secondaryChannels }

        val chosen: RouteTarget = pinnedOk
            ?: primary.firstOrNull()
            ?: secondary.firstOrNull()
            ?: return RouteResult.NoRoute(noRouteReason(role, evaluated, mode))

        val reasons = mutableListOf<String>()
        when {
            pinnedOk != null -> reasons.add("pinned by you")
            chosen.channel in primaryChannels ->
                reasons.add(if (chosen.channel == TargetChannel.LOCAL) "preferred local" else "preferred frontier")
            else -> reasons.add("fallback, no $primaryLabel target eligible")
        }
        val chosenHealth = health.get(chosen.id)
        reasons.add(
            when {
                health.circuitState(chosen.id, now) == CircuitState.HALF_OPEN -> "recovering after circuit break"
                chosenHealth.consecutiveFailures > 0 -> "${chosenHealth.consecutiveFailures} recent failure(s)"
                else -> "healthy"
            },
        )
        reasons.add("within limits")

        val alternatives = mutableListOf<String>()
        if (pinId != null && pinned == null) {
            alternatives.add("pinned $pinId: not registered")
        }
        if (pinned != null && !pinAllowedByMode) {
            alternatives.add("pinned $pinId ignored: not allowed in ${mode.name}")
        }
        for ((t, blk) in evaluated) {
            if (t.id == chosen.id) continue
            val why = when {
                blk.isNotEmpty() -> blk.joinToString(", ")
                pinnedOk != null -> "not chosen, pin takes precedence"
                t.channel !in primaryChannels && t.channel !in secondaryChannels -> "excluded by ${mode.name}"
                (t.channel in primaryChannels) == (chosen.channel in primaryChannels) -> "lower score"
                else -> "ranked after $primaryLabel targets under ${mode.name}"
            }
            alternatives.add("${t.displayName}: $why")
        }

        val rationaleBody = "${chosen.displayName} (${chosen.channel.name.lowercase()}) for ${role.name.lowercase()}: " +
            reasons.joinToString(", ")
        val rationale = clip(
            if (alternatives.isEmpty()) rationaleBody else rationaleBody + "; " + alternatives.joinToString("; "),
            Limits.MAX_ROUTING_NOTE_CHARS,
        )
        return RouteResult.Routed(RouteDecision(chosen, rationale, alternatives))
    }

    private fun blockers(
        role: StepRole,
        t: RouteTarget,
        health: ProviderHealth,
        limiter: RateLimiter,
        switches: ChannelSwitches,
        now: Long,
    ): List<String> {
        val out = mutableListOf<String>()
        if (!t.enabled) out.add("disabled")
        if (role !in t.capabilities) out.add("no ${role.name.lowercase()} capability")
        val key = t.channel.name.lowercase()
        if (switches.killSwitch) {
            out.add("kill switch")
        } else if (t.channel == TargetChannel.API && switches.channels[API_KEY] != true) {
            out.add("API not opted in")
        } else if (!switches.allows(key, t.id)) {
            out.add(if (switches.channels[key] == false) "$key channel off" else "provider not enabled")
        } else if (t.channel != TargetChannel.LOCAL && !switches.allows(FRONTIER_KEY, t.id)) {
            out.add(if (switches.channels[FRONTIER_KEY] == false) "frontier channel off" else "provider not enabled")
        }
        when (val decision = limiter.check(t.id, now)) {
            is RateDecision.Wait -> out.add("rate-limited until ${iso(decision.untilMs)}")
            is RateDecision.CapReached -> out.add("daily cap reached, resets ${iso(decision.resetAt)}")
            RateDecision.Allowed -> Unit
        }
        if (health.circuitState(t.id, now) == CircuitState.OPEN) {
            out.add("circuit open until ${iso(health.get(t.id).openUntil ?: now)}")
        }
        return out
    }

    private fun score(role: StepRole, t: RouteTarget, health: ProviderHealth, now: Long): Double =
        RoleAffinity.of(role, t.channel) * health.score(t.id, now)

    private fun primaryOf(mode: RoutingMode): Set<TargetChannel> = when (mode) {
        RoutingMode.LOCAL_FIRST, RoutingMode.LOCAL_ONLY -> setOf(TargetChannel.LOCAL)
        RoutingMode.FRONTIER_FIRST, RoutingMode.FRONTIER_ONLY ->
            setOf(TargetChannel.WEB, TargetChannel.APP, TargetChannel.API)
    }

    private fun secondaryOf(mode: RoutingMode): Set<TargetChannel> = when (mode) {
        RoutingMode.LOCAL_FIRST -> setOf(TargetChannel.WEB, TargetChannel.APP, TargetChannel.API)
        RoutingMode.FRONTIER_FIRST -> setOf(TargetChannel.LOCAL)
        RoutingMode.LOCAL_ONLY, RoutingMode.FRONTIER_ONLY -> emptySet()
    }

    private fun noRouteReason(
        role: StepRole,
        evaluated: List<Pair<RouteTarget, List<String>>>,
        mode: RoutingMode,
    ): String {
        if (evaluated.isEmpty()) return "No route for ${role.name.lowercase()}: no targets registered"
        val detail = evaluated.joinToString("; ") { (t, blk) ->
            "${t.displayName}: " + if (blk.isNotEmpty()) blk.joinToString(", ") else "excluded by ${mode.name}"
        }
        return "No route for ${role.name.lowercase()}: $detail"
    }

    private fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 3) + "..."
}
