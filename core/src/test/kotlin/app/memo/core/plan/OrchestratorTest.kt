package app.memo.core.plan

import app.memo.core.domain.StepKind
import app.memo.core.domain.WorkflowTemplate
import app.memo.core.prompts.ContextPacket
import app.memo.core.prompts.Prompts
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrchestratorTest {

    private val now = 1_700_000_000_000L
    private val zone = ZoneId.of("UTC")
    private val clock: () -> Long = { now }

    /** Scripted model: records every call, returns outputs in order, can explode. */
    private class FakeModel(
        private val script: List<String> = emptyList(),
        private val explode: Boolean = false,
    ) : PlanModel {
        val systemPrompts = mutableListOf<String>()
        val userPrompts = mutableListOf<String>()
        var callCount = 0
            private set

        override fun complete(system: String, user: String): String {
            systemPrompts += system
            userPrompts += user
            callCount++
            if (explode) throw IllegalStateException("model exploded")
            return script.getOrElse(callCount - 1) { script.last() }
        }
    }

    private fun template(name: String, examples: List<String>, planJson: String) = WorkflowTemplate(
        name = name,
        description = "",
        intentExamples = examples,
        planJson = planJson,
        createdAt = now,
        updatedAt = now,
    )

    private fun packet(vararg fields: Pair<String, String>): ContextPacket =
        ContextPacket.Builder().apply { fields.forEach { (key, value) -> put(key, value) } }.build()

    private fun orchestrate(
        model: PlanModel,
        text: String,
        packet: ContextPacket? = null,
        templates: List<WorkflowTemplate> = emptyList(),
    ): Orchestration = Orchestrator(model, clock, zone).orchestrate(text, packet, templates)

    // Shared plan JSONs.

    private val weeklyReviewPlan =
        """{"title":"Weekly review","steps":[
           {"id":"review","kind":"reason","title":"Weekly review","prompt":"Summarize the past week and flag one risk"}]}""".trimIndent()

    private val launchPlan =
        """{"title":"Draft the launch post","steps":[
           {"id":"draft","kind":"reason","title":"Draft the post","prompt":"Write a calm, specific launch post"},
           {"id":"gate","kind":"human_approval","title":"Approve the post","artifactType":"draft"},
           {"id":"send","kind":"browser_automation","title":"Publish","provider":"grok","artifactHint":"send: publish the approved post","requiresGate":true}]}""".trimIndent()

    /** Gate-less side effect: the canonical "invalid first try" output. */
    private val gatelessPlan =
        """{"title":"t","steps":[
           {"id":"send","kind":"browser_automation","title":"Publish","provider":"grok","artifactHint":"send: publish the post"}]}""".trimIndent()

    private val unknownKindPlan =
        """{"title":"t","steps":[{"id":"x","kind":"teleport","title":"teleport"}]}""".trimIndent()

    // 1. Command fast-path: zero model calls.

    @Test
    fun commandFastPathNeverCallsTheModel() {
        val model = FakeModel()
        val result = orchestrate(model, "remind me to call Ana in 10 minutes")
        val command = assertIs<Orchestration.Command>(result)
        val reminder = assertIs<CommandGrammar.VoiceCommand.Reminder>(command.command)
        assertEquals("call Ana", reminder.title)
        assertEquals(now + 10 * 60_000L, reminder.dueAt)
        assertEquals(0, model.callCount)
    }

    // 2. Plain chat: no plan intent, model untouched.

    @Test
    fun plainChatReplyDoesNotCallTheModel() {
        val model = FakeModel()
        assertEquals(Orchestration.Chat, orchestrate(model, "how are you?"))
        assertEquals(0, model.callCount)
    }

    // 3. Valid model plan accepted on the first try.

    @Test
    fun validModelPlanIsAcceptedOnFirstTry() {
        val model = FakeModel(listOf(launchPlan))
        val templates = listOf(template("Launch posts", listOf("post about our launch"), weeklyReviewPlan))
        val plan = assertIs<Orchestration.Plan>(
            orchestrate(model, "plan a post about our launch", templates = templates),
        )
        assertEquals(PlanSource.MODEL, plan.source)
        assertEquals(0, plan.repairs)
        assertEquals(emptyList(), plan.notes)
        assertEquals(1, model.callCount)
        assertEquals(Prompts.orchestratorSystem(), model.systemPrompts.single())
        assertEquals("Draft the launch post", plan.spec.title)
        assertEquals(3, plan.spec.steps.size)
    }

    // 4. Invalid output repaired once; the repair prompt names the violations.

    @Test
    fun invalidPlanIsRepairedOnceAndLabelled() {
        val model = FakeModel(listOf(gatelessPlan, launchPlan))
        val plan = assertIs<Orchestration.Plan>(orchestrate(model, "plan a post about our launch"))
        assertEquals(PlanSource.MODEL_REPAIRED, plan.source)
        assertEquals(1, plan.repairs)
        assertEquals(2, model.callCount)
        // Round 1 rejected: its violation summary is carried as a note.
        assertTrue(plan.notes.any { it.contains("no preceding HUMAN_APPROVAL gate") })
        // The 2nd call's user prompt contains "rejected for", the violation text
        // and the rejected answer.
        val repairPrompt = model.userPrompts[1]
        assertTrue(repairPrompt.contains("Your previous answer was rejected for:"))
        assertTrue(repairPrompt.contains("Answer again with ONE corrected JSON plan object only."))
        assertTrue(repairPrompt.contains("no preceding HUMAN_APPROVAL gate"))
        assertTrue(repairPrompt.contains(gatelessPlan.take(60)))
        // Prompt flow: original prompt first, previous answer last.
        assertTrue(repairPrompt.indexOf("Intent:") < repairPrompt.indexOf("Previous answer:"))
    }

    // 5. Two failed repairs: labelled fallback, valid by policy, exactly 3 calls.

    @Test
    fun twoFailedRepairsProduceValidLabelledFallback() {
        val model = FakeModel(listOf(gatelessPlan, unknownKindPlan, gatelessPlan))
        val plan = assertIs<Orchestration.Plan>(orchestrate(model, "plan a post about our launch"))
        assertEquals(PlanSource.FALLBACK, plan.source)
        assertEquals(2, plan.repairs)
        assertEquals(3, model.callCount)
        // Both failed rounds are noted.
        assertTrue(plan.notes.any { it.contains("no preceding HUMAN_APPROVAL gate") })
        assertTrue(plan.notes.any { it.contains("unknown kind 'teleport'") })
        // The fallback spec: exactly one REASON step, valid under full policy.
        assertEquals(1, plan.spec.steps.size)
        val step = plan.spec.steps.single()
        assertEquals(StepKind.REASON, step.kind)
        assertEquals("blocked", step.id)
        assertEquals(emptyList(), WorkflowPolicy.validateSpec(plan.spec))
    }

    // 6. A throwing model never escapes: labelled fallback with the error noted.

    @Test
    fun throwingModelFallsBackWithoutEscaping() {
        val model = FakeModel(explode = true)
        val plan = assertIs<Orchestration.Plan>(orchestrate(model, "plan a post about our launch"))
        assertEquals(PlanSource.FALLBACK, plan.source)
        assertEquals(0, plan.repairs)
        assertEquals(1, model.callCount)
        val step = plan.spec.steps.single()
        assertEquals(StepKind.REASON, step.kind)
        assertEquals("blocked", step.id)
        assertTrue(plan.notes.any { it.contains("model exploded") })
        assertTrue(step.prompt.contains("model exploded"))
        assertEquals(emptyList(), WorkflowPolicy.validateSpec(plan.spec))
    }

    // 7. Reuse-first: a stored example matching the intent replaces the model call.

    @Test
    fun matchingStoredTemplateIsReusedWithoutCallingTheModel() {
        val model = FakeModel()
        val templates = listOf(
            template("Unrelated", listOf("buy cheese"), weeklyReviewPlan),
            template("Weekly review", listOf("weekly review"), weeklyReviewPlan),
        )
        val plan = assertIs<Orchestration.Plan>(orchestrate(model, "plan weekly review", templates = templates))
        assertEquals(PlanSource.TEMPLATE_REUSE, plan.source)
        assertEquals(0, plan.repairs)
        assertEquals(listOf("reused template Weekly review"), plan.notes)
        assertEquals(0, model.callCount)
        assertEquals("Weekly review", plan.spec.title)
    }

    // 8. An invalid stored template with a matching example is skipped, never returned.

    @Test
    fun invalidStoredTemplateIsSkippedAndTheModelIsCalled() {
        val broken = """{"title":"Broken","steps":[{"id":"s1","kind":"reason","title":"x"}]}"""
        val model = FakeModel(listOf(weeklyReviewPlan))
        val templates = listOf(
            template("Broken weekly", listOf("weekly review"), broken),
            template("Not matching", listOf("something else"), weeklyReviewPlan),
        )
        val plan = assertIs<Orchestration.Plan>(orchestrate(model, "plan weekly review", templates = templates))
        // The broken template was tried, skipped and noted; the model produced the plan.
        assertEquals(PlanSource.MODEL, plan.source)
        assertEquals(1, model.callCount)
        assertTrue(plan.notes.any { it.contains("template Broken weekly skipped") })
    }

    @Test
    fun storedTemplateViolatingFullPolicyIsSkippedToo() {
        // A retried side effect parses cleanly against validationSideEffects but
        // fails the full WorkflowPolicy.validateSpec recheck — reuse must catch it.
        val retriedSend =
            """{"title":"t","steps":[
               {"id":"gate","kind":"human_approval","title":"g","artifactType":"draft"},
               {"id":"send","kind":"browser_automation","title":"s","provider":"grok","artifactHint":"send: publish the post","requiresGate":true},
               {"id":"r","kind":"retry","target":"send","attemptsMax":2}]}""".trimIndent()
        val model = FakeModel(listOf(weeklyReviewPlan))
        val templates = listOf(template("Retry weekly", listOf("weekly review"), retriedSend))
        val plan = assertIs<Orchestration.Plan>(orchestrate(model, "plan weekly review", templates = templates))
        assertEquals(PlanSource.MODEL, plan.source)
        assertTrue(plan.notes.any { it.contains("template Retry weekly skipped") && it.contains("must not retry") })
    }

    // 9. Few-shot: the user prompt carries the best-matching template's planJson and "Intent:".

    @Test
    fun userPromptCarriesFewShotExamplesAndIntent() {
        val model = FakeModel(listOf(launchPlan))
        val templates = listOf(
            template("Weekly review", listOf("weekly review"), weeklyReviewPlan), // shares no words
            template("Launch posts", listOf("post about our launch"), launchPlan),
        )
        val plan = assertIs<Orchestration.Plan>(
            orchestrate(model, "plan a post about our launch", templates = templates),
        )
        assertEquals(PlanSource.MODEL, plan.source)
        val prompt = model.userPrompts.single()
        assertTrue(prompt.contains("Example intent: post about our launch"))
        assertTrue(prompt.contains("Example plan: $launchPlan"))
        assertTrue(prompt.endsWith("Intent: plan a post about our launch"))
        // Zero-word-overlap templates never land in the few-shot slot.
        assertTrue(!prompt.contains("weekly review"))
    }

    // 10. Packet rendering lands in the prompt; empty packets don't.

    @Test
    fun packetRenderLandsOnlyWhenNotEmpty() {
        val model = FakeModel(listOf(launchPlan))
        assertIs<Orchestration.Plan>(
            orchestrate(
                model,
                "plan a post about our launch",
                packet = packet("contact" to "Alice met at the offsite"),
            ),
        )
        val prompt = model.userPrompts.single()
        val before = prompt.indexOf("Context:")
        val after = prompt.indexOf("Intent:")
        assertTrue(before in 0 until after)
        assertTrue(prompt.contains("contact: Alice met at the offsite"))

        val emptyModel = FakeModel(listOf(launchPlan))
        assertIs<Orchestration.Plan>(
            orchestrate(emptyModel, "plan a post about our launch", packet = packet()),
        )
        assertTrue(!emptyModel.userPrompts.single().contains("Context:"))
    }

    // 11. Blank text, non-plan text and the bare word "plan" stay Chat.

    @Test
    fun blankAndNonPlanTextStayChat() {
        assertEquals(Orchestration.Chat, orchestrate(FakeModel(), ""))
        assertEquals(Orchestration.Chat, orchestrate(FakeModel(), "   "))
        assertEquals(Orchestration.Chat, orchestrate(FakeModel(listOf(weeklyReviewPlan)), "how are you?"))
        assertEquals(Orchestration.Chat, orchestrate(FakeModel(listOf(weeklyReviewPlan)), "tell me a deep story"))
        // "plan " requires a following word: bare "plan" is a chat reply.
        assertEquals(Orchestration.Chat, orchestrate(FakeModel(), "plan"))
    }

    // 12. Every cue form reaches the model path; the contained cue is stripped for reuse.

    @Test
    fun planCuesRouteToModelPathAndStripForReuse() {
        for (text in listOf(
            "plan weekly review",
            "plan a weekly review",
            "/plan weekly review",
            "help me plan weekly review",
            "make a plan for weekly review",
            "weekly review step by step",
        )) {
            val model = FakeModel(listOf(weeklyReviewPlan))
            val plan = assertIs<Orchestration.Plan>(orchestrate(model, text))
            assertEquals(PlanSource.MODEL, plan.source, "cue text: $text")
            assertEquals(1, model.callCount, "cue text: $text")
        }
        // The contained cue is stripped before reuse lookup: "review this task
        // step by step" strips to "review this task"; no example equals it, so
        // the model is asked.
        val cueModel = FakeModel(listOf(weeklyReviewPlan))
        assertIs<Orchestration.Plan>(orchestrate(cueModel, "review this task step by step"))
        assertEquals(1, cueModel.callCount)
        // And reuse still fires under prefix cues:
        val reuseModel = FakeModel()
        val templates = listOf(template("Weekly review", listOf("weekly review"), weeklyReviewPlan))
        val reused = assertIs<Orchestration.Plan>(
            orchestrate(reuseModel, "HELP ME PLAN weekly review", templates = templates),
        )
        assertEquals(PlanSource.TEMPLATE_REUSE, reused.source)
        assertEquals(0, reuseModel.callCount)
    }
}
