package app.memo.core.route

import app.memo.core.policy.ChannelSwitches
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelRouterTest {

    private val now = 1_000_000_000L
    private val minute = 60_000L
    private val allRoles = StepRole.entries.toSet()

    private val local = RouteTarget("local:qwen3-1.7b", TargetChannel.LOCAL, "Qwen3 1.7B", allRoles, true)
    private val chatgpt = RouteTarget("chatgpt", TargetChannel.WEB, "ChatGPT", allRoles, true)
    private val claude = RouteTarget("claude", TargetChannel.WEB, "Claude", allRoles, true)
    private val chat = RouteTarget("com.example.chat", TargetChannel.APP, "Example Chat", allRoles, true)
    private val api = RouteTarget("api:openai-compat", TargetChannel.API, "Custom API", allRoles, true)

    /** Every target's provider is enabled; the api channel is NOT opted in by default. */
    private val switches = ChannelSwitches()
        .withProvider("local:qwen3-1.7b", true)
        .withProvider("chatgpt", true)
        .withProvider("claude", true)
        .withProvider("com.example.chat", true)
        .withProvider("api:openai-compat", true)

    private val defaultTargets = listOf(local, chatgpt, claude, chat)

    private fun route(
        role: StepRole,
        targets: List<RouteTarget> = defaultTargets,
        prefs: RoutingPreferences = RoutingPreferences(),
        health: ProviderHealth = ProviderHealth(),
        limiter: RateLimiter = RateLimiter(),
        sw: ChannelSwitches = switches,
        at: Long = now,
    ): RouteResult = ModelRouter.route(role, targets, prefs, health, limiter, sw, at)

    private fun decision(r: RouteResult): RouteDecision = assertIs<RouteResult.Routed>(r).decision

    private fun reason(r: RouteResult): String = assertIs<RouteResult.NoRoute>(r).reason

    // ---- modes -------------------------------------------------------------

    @Test
    fun localFirstPrefersLocalForDefaultLocalFirstRoles() {
        val d = decision(route(StepRole.DRAFT))
        assertEquals(local.id, d.target.id)
        assertTrue(d.rationale.contains("preferred local"))
    }

    @Test
    fun localFirstFallsBackToFrontierAndSaysSo() {
        val d = decision(route(StepRole.DRAFT, targets = listOf(local.copy(enabled = false), chatgpt)))
        assertEquals(chatgpt.id, d.target.id)
        assertTrue(d.rationale.contains("fallback, no local target eligible"), d.rationale)
    }

    @Test
    fun frontierFirstPrefersFrontierForDefaultFrontierRoles() {
        val d = decision(route(StepRole.CRITIQUE))
        assertEquals(chatgpt.id, d.target.id) // both WEB at affinity 1.0; ties go to the lower id
        assertTrue(d.rationale.contains("preferred frontier"), d.rationale)
    }

    @Test
    fun frontierFirstFallsBackToLocalForHeavyRoleAndSaysSo() {
        val targets = listOf(local, chatgpt.copy(enabled = false), claude.copy(enabled = false))
        val d = decision(route(StepRole.CRITIQUE, targets = targets))
        assertEquals(local.id, d.target.id)
        assertTrue(d.rationale.contains("fallback, no frontier target eligible"), d.rationale)
    }

    @Test
    fun localOnlyNeverChoosesFrontier() {
        val prefs = RoutingPreferences().withMode(StepRole.RESEARCH, RoutingMode.LOCAL_ONLY)
        val d = decision(route(StepRole.RESEARCH, prefs = prefs))
        assertEquals(local.id, d.target.id)
        val noLocal = reason(route(StepRole.RESEARCH, targets = listOf(chatgpt, claude), prefs = prefs))
        assertTrue(noLocal.contains("excluded by LOCAL_ONLY"), noLocal)
    }

    @Test
    fun frontierOnlyNeverChoosesLocal() {
        val prefs = RoutingPreferences().withMode(StepRole.PLAN, RoutingMode.FRONTIER_ONLY)
        val d = decision(route(StepRole.PLAN, prefs = prefs))
        assertEquals(chatgpt.id, d.target.id)
        assertTrue(d.alternatives.any { it.startsWith("Qwen3 1.7B: excluded by FRONTIER_ONLY") }, d.alternatives.toString())
    }

    @Test
    fun frontierOnlyWithNoFrontierIsNoRouteNamingEachBlocker() {
        val prefs = RoutingPreferences().withMode(StepRole.PLAN, RoutingMode.FRONTIER_ONLY)
        val targets = listOf(local, chatgpt.copy(enabled = false))
        val text = reason(route(StepRole.PLAN, targets = targets, prefs = prefs))
        assertTrue(text.contains("Qwen3 1.7B: excluded by FRONTIER_ONLY"), text)
        assertTrue(text.contains("ChatGPT: disabled"), text)
    }

    // ---- scoring -----------------------------------------------------------

    @Test
    fun affinityOrdersChannelsWithinAGroup() {
        val prefs = RoutingPreferences()
        val d = decision(route(StepRole.RESEARCH, targets = listOf(chat, chatgpt), prefs = prefs))
        assertEquals(chatgpt.id, d.target.id) // WEB 1.0 beats APP 0.8
    }

    @Test
    fun healthPenaltyCanReorderCandidates() {
        val health = ProviderHealth()
        health.record("chatgpt", false, -1L, now - 1_000L)
        health.record("chatgpt", false, -1L, now - 500L)
        val d = decision(route(StepRole.RESEARCH, targets = listOf(chat, chatgpt), health = health))
        assertEquals(chat.id, d.target.id)
        assertTrue(d.rationale.contains("ChatGPT: lower score"), d.rationale)
    }

    @Test
    fun tieBreakIsDeterministicAndIndependentOfListOrder() {
        val forward = route(StepRole.PLAN, targets = listOf(claude, chatgpt))
        val reverse = route(StepRole.PLAN, targets = listOf(chatgpt, claude))
        assertEquals(forward, reverse)
        assertEquals("chatgpt", decision(forward).target.id)
    }

    // ---- pins --------------------------------------------------------------

    @Test
    fun eligiblePinWins() {
        val prefs = RoutingPreferences().withPin(StepRole.CRITIQUE, "claude")
        val d = decision(route(StepRole.CRITIQUE, prefs = prefs))
        assertEquals(claude.id, d.target.id)
        assertTrue(d.rationale.contains("pinned by you"), d.rationale)
    }

    @Test
    fun pinOverridesModeEvenWhenItIsLocal() {
        val prefs = RoutingPreferences().withPin(StepRole.CRITIQUE, local.id)
        assertEquals(local.id, decision(route(StepRole.CRITIQUE, prefs = prefs)).target.id)
    }

    @Test
    fun ineligiblePinFallsBackAndNamesTheBlocker() {
        val prefs = RoutingPreferences().withPin(StepRole.CRITIQUE, "claude")
        val d = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude.copy(enabled = false)), prefs = prefs))
        assertEquals(chatgpt.id, d.target.id)
        assertTrue(d.alternatives.any { it == "Claude: disabled" }, d.alternatives.toString())
    }

    @Test
    fun rateLimitedPinFallsBack() {
        val limiter = RateLimiter()
        limiter.record("claude", now - 1_000L)
        val prefs = RoutingPreferences().withPin(StepRole.CRITIQUE, "claude")
        val d = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), prefs = prefs, limiter = limiter))
        assertEquals(chatgpt.id, d.target.id)
        assertTrue(d.alternatives.any { it.startsWith("Claude: rate-limited until") }, d.alternatives.toString())
    }

    @Test
    fun unregisteredPinIsReported() {
        val prefs = RoutingPreferences().withPin(StepRole.CRITIQUE, "missing-target")
        val d = decision(route(StepRole.CRITIQUE, prefs = prefs))
        assertEquals(chatgpt.id, d.target.id)
        assertTrue(d.alternatives.contains("pinned missing-target: not registered"), d.alternatives.toString())
    }

    // ---- API opt-in ----------------------------------------------------------

    @Test
    fun apiIsIneligibleUnlessChannelIsExplicitlyOn() {
        val prefs = RoutingPreferences().withMode(StepRole.RESEARCH, RoutingMode.FRONTIER_ONLY)
        val text = reason(route(StepRole.RESEARCH, targets = listOf(api), prefs = prefs))
        assertTrue(text.contains("Custom API: API not opted in"), text)
    }

    @Test
    fun apiNeedsItsProviderToo() {
        val prefs = RoutingPreferences().withMode(StepRole.RESEARCH, RoutingMode.FRONTIER_ONLY)
        val sw = switches.withChannel("api", true).withProvider("api:openai-compat", false)
        val text = reason(route(StepRole.RESEARCH, targets = listOf(api), prefs = prefs, sw = sw))
        assertTrue(text.contains("provider not enabled"), text)
    }

    @Test
    fun apiIsRoutedOnceOptedIn() {
        val prefs = RoutingPreferences().withMode(StepRole.RESEARCH, RoutingMode.FRONTIER_ONLY)
        val sw = switches.withChannel("api", true)
        val d = decision(route(StepRole.RESEARCH, targets = listOf(api), prefs = prefs, sw = sw))
        assertEquals(api.id, d.target.id)
    }

    // ---- kill switch, circuit, rate limits -----------------------------------

    @Test
    fun killSwitchMakesEverythingNoRoute() {
        val text = reason(route(StepRole.DRAFT, sw = switches.withKill(true)))
        assertTrue(text.contains("kill switch"), text)
        assertTrue(text.contains("Qwen3 1.7B: kill switch"), text)
    }

    @Test
    fun channelSwitchOffBlocksOnlyThatChannel() {
        val sw = switches.withChannel("web", false)
        val d = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, chat), sw = sw))
        assertEquals(chat.id, d.target.id)
        assertTrue(d.alternatives.contains("ChatGPT: web channel off"), d.alternatives.toString())
    }

    @Test
    fun openCircuitExcludesTargetUntilHalfOpen() {
        val health = ProviderHealth()
        repeat(3) { health.record("chatgpt", false, -1L, now) }
        val d = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), health = health))
        assertEquals(claude.id, d.target.id)
        assertTrue(d.alternatives.any { it.startsWith("ChatGPT: circuit open until") }, d.alternatives.toString())

        val afterCooldown = now + 5 * minute
        val half = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt), health = health, at = afterCooldown))
        assertEquals(chatgpt.id, half.target.id)
    }

    @Test
    fun rateLimitWaitIsReportedAndClears() {
        val limiter = RateLimiter()
        limiter.record("chatgpt", now - 1_000L)
        val blocked = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), limiter = limiter))
        assertEquals(claude.id, blocked.target.id)
        assertTrue(blocked.alternatives.any { it.startsWith("ChatGPT: rate-limited until") }, blocked.alternatives.toString())

        val cleared = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), limiter = limiter, at = now + minute))
        assertEquals(chatgpt.id, cleared.target.id)
    }

    @Test
    fun dailyCapIsReportedWithResetTime() {
        val limiter = RateLimiter(mapOf("chatgpt" to RateLimit(minIntervalMs = 0L, dailyCap = 1)))
        limiter.record("chatgpt", now - 3_000L)
        val d = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), limiter = limiter))
        assertEquals(claude.id, d.target.id)
        val expectedReset = java.time.Instant.ofEpochMilli(now - 3_000L + RateLimit.WINDOW_MS).toString()
        assertTrue(d.alternatives.contains("ChatGPT: daily cap reached, resets $expectedReset"), d.alternatives.toString())
    }

    @Test
    fun noRouteNamesEveryBlockerKind() {
        val health = ProviderHealth()
        repeat(3) { health.record("claude", false, -1L, now) }
        val targets = listOf(
            local.copy(enabled = false),
            chatgpt.copy(capabilities = setOf(StepRole.PLAN)),
            claude,
            chat,
        )
        val sw = switches.withProvider("com.example.chat", false)
        val text = reason(route(StepRole.CRITIQUE, targets = targets, health = health, sw = sw))
        assertTrue(text.contains("Qwen3 1.7B: disabled"), text)
        assertTrue(text.contains("ChatGPT: no critique capability"), text)
        assertTrue(text.contains("Claude: circuit open until"), text)
        assertTrue(text.contains("Example Chat: provider not enabled"), text)
    }

    @Test
    fun emptyTargetListIsNoRoute() {
        assertEquals("No route for draft: no targets registered", reason(route(StepRole.DRAFT, targets = emptyList())))
    }

    @Test
    fun duplicateTargetIdsAreRejected() {
        val text = reason(route(StepRole.DRAFT, targets = listOf(chatgpt, chatgpt.copy(displayName = "Other"))))
        assertTrue(text.contains("Duplicate target ids: chatgpt"), text)
    }

    // ---- rationale ---------------------------------------------------------

    @Test
    fun rationaleExplainsChoiceAndRejectionsWithinTheLimit() {
        val limiter = RateLimiter()
        limiter.record("chatgpt", now - 1_000L)
        val d = decision(route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), limiter = limiter))
        assertTrue(d.rationale.startsWith("Claude (web) for critique: preferred frontier, healthy, within limits"), d.rationale)
        assertTrue(d.rationale.contains("ChatGPT: rate-limited"), d.rationale)
        assertTrue(d.rationale.length <= 200, "rationale is ${d.rationale.length} chars")
    }

    @Test
    fun rationaleIsClippedToTheLimit() {
        val many = (1..20).map { RouteTarget("t$it", TargetChannel.WEB, "Provider number $it with a long name", allRoles, false) }
        val d = decision(route(StepRole.CRITIQUE, targets = many + claude))
        assertTrue(d.rationale.length <= app.memo.core.Limits.MAX_ROUTING_NOTE_CHARS)
    }

    // ---- JSON --------------------------------------------------------------

    @Test
    fun preferencesDefaultsMatchTheRoutingTable() {
        val p = RoutingPreferences()
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.PLAN))
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.CHAT))
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.CAPTURE))
        assertEquals(RoutingMode.FRONTIER_FIRST, p.modeFor(StepRole.RESEARCH))
        assertEquals(RoutingMode.FRONTIER_FIRST, p.modeFor(StepRole.CRITIQUE))
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.DRAFT))
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.SYNTHESIZE))
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.IMAGE))
        assertEquals(null, p.pinFor(StepRole.PLAN))
    }

    @Test
    fun preferencesRoundTripThroughJson() {
        val prefs = RoutingPreferences()
            .withMode(StepRole.PLAN, RoutingMode.FRONTIER_ONLY)
            .withPin(StepRole.CRITIQUE, "claude")
        val restored = RoutingPreferences.fromJson(JSONObject(prefs.toJson().toString()))
        assertEquals(prefs, restored)
        assertEquals(RoutingPreferences(), RoutingPreferences.fromJson(JSONObject(RoutingPreferences().toJson().toString())))
    }

    @Test
    fun preferencesReaderIsTolerant() {
        val raw = JSONObject("""{"modes":{"PLAN":"bogus","NOPE":"LOCAL_ONLY"},"pins":{"CHAT":""}}""")
        val p = RoutingPreferences.fromJson(raw)
        assertEquals(RoutingMode.LOCAL_FIRST, p.modeFor(StepRole.PLAN))
        assertEquals(null, p.pinFor(StepRole.CHAT))
    }

    @Test
    fun routerStateRoundTripsThroughJson() {
        val health = ProviderHealth()
        repeat(3) { health.record("chatgpt", false, -1L, now) }
        val limiter = RateLimiter()
        limiter.record("claude", now - 60_000L)
        val restoredHealth = ProviderHealth.fromJson(JSONObject(health.toJson().toString()))
        val restoredLimiter = RateLimiter.fromJson(JSONObject(limiter.toJson().toString()))
        val before = route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), health = health, limiter = limiter, at = now + 1_000L)
        val after = route(StepRole.CRITIQUE, targets = listOf(chatgpt, claude), health = restoredHealth, limiter = restoredLimiter, at = now + 1_000L)
        assertEquals(before, after)
    }
}
