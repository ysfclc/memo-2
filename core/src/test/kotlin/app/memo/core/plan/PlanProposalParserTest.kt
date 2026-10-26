package app.memo.core.plan

import app.memo.core.Limits
import app.memo.core.domain.StepKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanProposalParserTest {

    private val now = 1_700_000_000_000L

    private val validPublishPlan = """
        {"title":"Publish the hiring post",
         "steps":[
           {"id":"draft","kind":"reason","title":"Draft the post","prompt":"write a calm, specific post about our open role"},
           {"id":"gate","kind":"human_approval","title":"Approve the post","artifactType":"draft"},
           {"id":"send","kind":"browser_automation","title":"Post to Grok","provider":"grok","artifactHint":"send: publish the approved draft","requiresGate":true}
         ]}
    """.trimIndent()

    @Test
    fun acceptsAValidGateBoundPublishPlan() {
        val result = PlanProposalParser.parse(validPublishPlan, now)
        assertEquals(emptyList(), result.violations)
        val spec = result.spec!!
        assertEquals("Publish the hiring post", spec.title)
        assertEquals(3, spec.steps.size)
        assertEquals("draft", spec.steps[0].id)
        val send = spec.steps[2]
        assertEquals(StepKind.BROWSER_AUTOMATION, send.kind)
        assertTrue(send.isSideEffect)
        assertTrue(send.requiresGate)
        // plan id is deterministic per (title, now)
        assertEquals(spec.id, PlanProposalParser.parse(validPublishPlan, now).spec?.id)
    }

    @Test
    fun rejectsPlanWithoutJsonObject() {
        val result = PlanProposalParser.parse("I would suggest posting something nice.", now)
        assertFalse(result.ok)
        assertTrue(result.violations.single().contains("no JSON plan"))
        assertNull(result.spec)
    }

    @Test
    fun rejectsUnknownKindAndUnknownFields() {
        val bad = """
            {"title":"t","steps":[
              {"id":"a","kind":"teleport","title":"x"},
              {"id":"b","kind":"reason","title":"y","prompt":"p","extraField":1}
            ]}
        """.trimIndent()
        val result = PlanProposalParser.parse(bad, now)
        assertFalse(result.ok)
        assertTrue(result.violations.any { it.contains("unknown kind 'teleport'") })
        assertTrue(result.violations.any { it.contains("unknown field(s) extraField") })
    }

    @Test
    fun rejectsGatelessSideEffects() {
        val noGateFlag = """
            {"title":"t","steps":[
              {"id":"g","kind":"human_approval","title":"gate","artifactType":"draft"},
              {"id":"send","kind":"browser_automation","title":"s","provider":"claude","artifactHint":"send:x"}
            ]}
        """.trimIndent()
        val result = PlanProposalParser.parse(noGateFlag, now)
        assertTrue(result.violations.any { it.contains("requires a gate") })

        val noGateStep = """
            {"title":"t","steps":[
              {"id":"send","kind":"app_automation","title":"s","appPackage":"com.openai.chatgpt","requiresGate":true}
            ]}
        """.trimIndent()
        val second = PlanProposalParser.parse(noGateStep, now)
        assertTrue(second.violations.any { it.contains("no preceding HUMAN_APPROVAL gate") })
    }

    @Test
    fun rejectsUnknownProviderAndHostileMetricUrls() {
        val badProvider = """
            {"title":"t","steps":[
              {"id":"g","kind":"human_approval","title":"gate","artifactType":"draft"},
              {"id":"s","kind":"browser_automation","title":"x","provider":"aol-instant-messenger","artifactHint":"send:y","requiresGate":true}
            ]}
        """.trimIndent()
        assertTrue(PlanProposalParser.parse(badProvider, now).violations.any { it.contains("unknown provider") })

        val httpMetric = """
            {"title":"t","steps":[
              {"id":"m","kind":"metric_collect","title":"capture","metricKey":"likes","metricUrl":"http://insecure.example/p"}
            ]}
        """.trimIndent()
        assertTrue(PlanProposalParser.parse(httpMetric, now).violations.any { it.contains("https") })
    }

    @Test
    fun foldsRetryDeclarationsOntoTargets() {
        val withRetry = """
            {"title":"t","steps":[
              {"id":"m","kind":"metric_collect","title":"capture","metricKey":"views","metricUrl":"https://x.example/p"},
              {"id":"r","kind":"retry","target":"m","attemptsMax":2}
            ]}
        """.trimIndent()
        val result = PlanProposalParser.parse(withRetry, now)
        assertEquals(emptyList(), result.violations)
        val spec = result.spec!!
        // The retry step VANISHED from the compiled plan; its policy landed on m.
        assertEquals(listOf("m"), spec.steps.map { it.id })
        assertEquals(2, spec.steps.single().retry?.attemptsMax)
    }

    @Test
    fun rejectsSelfReferencingAndUnknownBranchTargets() {
        val selfBranch = """
            {"title":"t","steps":[
              {"id":"b","kind":"branch","branches":[{"whenStep":"b","outcome":"SUCCEEDED","goto":"b"}]}
            ]}
        """.trimIndent()
        assertTrue(PlanProposalParser.parse(selfBranch, now).violations.any { it.contains("cannot target itself") })

        val danglingGoto = """
            {"title":"t","steps":[
              {"id":"b","kind":"branch","branches":[{"whenStep":"ghost","outcome":"SUCCEEDED","goto":"neverland"}]}
            ]}
        """.trimIndent()
        val second = PlanProposalParser.parse(danglingGoto, now)
        assertTrue(second.violations.any { it.contains("unknown step 'ghost'") })
        assertTrue(second.violations.any { it.contains("unknown step 'neverland'") })
    }

    @Test
    fun rejectsOversizedPlansAndDuplicateIds() {
        val steps = (0 until Limits.MAX_STEPS_PER_RUN + 1).joinToString(",") { index ->
            """{"id":"s$index","kind":"reason","title":"x $index","prompt":"do thing $index"}"""
        }
        val tooBig = """{"title":"t","steps":[$steps]}"""
        assertTrue(PlanProposalParser.parse(tooBig, now).violations.any { it.contains("exceed") })

        val dup = """
            {"title":"t","steps":[
              {"id":"same","kind":"reason","title":"x","prompt":"a"},
              {"id":"same","kind":"reason","title":"y","prompt":"b"}
            ]}
        """.trimIndent()
        assertTrue(PlanProposalParser.parse(dup, now).violations.any { it.contains("duplicate id") })
    }

    @Test
    fun policyRoundTripValidatesUserBuiltSpecs() {
        val parsed = PlanProposalParser.parse(validPublishPlan, now).spec!!
        assertEquals(emptyList(), app.memo.core.plan.WorkflowPolicy.validateSpec(parsed))
        // A mutated spec (gate removed) must fail validation.
        val mutated = parsed.copy(steps = parsed.steps.filter { it.kind != StepKind.HUMAN_APPROVAL })
        val violations = app.memo.core.plan.WorkflowPolicy.validateSpec(mutated)
        assertTrue(violations.isNotEmpty())
    }

    @Test
    fun readOnlyCaptureNeedsNoGate() {
        val capture = """
            {"title":"Read the thread","steps":[
              {"id":"peek","kind":"browser_automation","title":"Open thread","provider":"chatgpt","artifactHint":"capture: conversation text","requiresGate":false},
              {"id":"note","kind":"data_persist","title":"Save draft note","artifactType":"draft"}
            ]}
        """.trimIndent()
        val result = PlanProposalParser.parse(capture, now)
        assertEquals(emptyList(), result.violations)
        assertFalse(result.spec!!.steps[0].isSideEffect)
    }
}
