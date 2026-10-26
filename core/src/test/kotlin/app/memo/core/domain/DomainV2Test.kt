package app.memo.core.domain

import app.memo.core.Limits
import app.memo.core.registry.NaturalKeys
import app.memo.core.registry.Registry
import app.memo.core.registry.Schemes
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainV2Test {

    private val t0 = 1_700_000_000_000L
    private val plan = """{"title":"Weekly brief","steps":[{"id":"research"},{"id":"draft"}]}"""
    private val garbage = JSONObject("{\"unrelated\":true}")

    // ---- Project ---------------------------------------------------------

    @Test
    fun projectRoundTrips() {
        val p = Project(
            name = "Memo launch",
            summary = "Ship domain v2",
            status = ProjectStatus.PAUSED,
            tags = listOf("product", "q4"),
            principleKeys = listOf("pr:0123456789abcdef"),
            createdAt = t0,
            updatedAt = t0 + 1,
            version = 3,
        )
        assertEquals(p, Project.fromJson(p.toJson()))
    }

    @Test
    fun projectInvalidWhenNameBlankOrGarbageDecodesNull() {
        assertTrue(Project(name = "   ").isInvalid())
        assertNull(Project.fromJson(garbage))
        assertNull(Project.fromJson(JSONObject().put("name", "")))
    }

    @Test
    fun projectKeyNormalisesAndBlankIsEmpty() {
        val a = NaturalKeys.project("Memo  Launch!")
        assertEquals(a, NaturalKeys.project("  memo launch "))
        assertNotEquals(a, NaturalKeys.project("Memo Other"))
        assertTrue(a.startsWith("pj:"))
        assertEquals(19, a.length)
        assertEquals("", NaturalKeys.project("   "))
        assertEquals(a, Schemes.Projects.id(Project(name = "MEMO launch")))
    }

    @Test
    fun projectRegistryCreatesReusesThenBumps() {
        val reg = Registry("projects", Schemes.Projects)
        assertIs<Registry.Put.Created<Project>>(reg.upsert(Project(name = "Memo"), now = t0))
        assertIs<Registry.Put.Reused<Project>>(reg.upsert(Project(name = "Memo"), now = t0 + 1))
        val bumped = reg.upsert(Project(name = "Memo", status = ProjectStatus.DONE), now = t0 + 2)
        assertIs<Registry.Put.Bumped<Project>>(bumped)
        assertEquals(2, bumped.current.version)
        assertEquals(1, reg.size)
    }

    // ---- Note ------------------------------------------------------------

    @Test
    fun noteRoundTrips() {
        val n = Note(
            projectKey = "pj:0123456789abcdef",
            title = "Pricing insight",
            body = "Annual plans convert better.",
            kind = NoteKind.INSIGHT,
            sourceRef = "https://example.com/report",
            evidence = listOf(
                Evidence(kind = EvidenceKind.QUOTE, quote = "annual plans convert 2x", source = "report", capturedAt = t0),
            ),
            createdAt = t0,
            updatedAt = t0 + 1,
            version = 2,
        )
        assertEquals(n, Note.fromJson(n.toJson()))
    }

    @Test
    fun noteInvalidWhenTitleBlankOrGarbageDecodesNull() {
        assertTrue(Note(title = " ").isInvalid())
        assertTrue(Note(title = "ok", evidence = List(Limits.MAX_EVIDENCE_PER_STEP + 1) { Evidence(quote = "q") }).isInvalid())
        assertNull(Note.fromJson(garbage))
    }

    @Test
    fun noteKeyNormalisesAndBucketsByDay() {
        val a = NaturalKeys.note("PJ:Abc", "Hello  World", t0)
        assertEquals(a, NaturalKeys.note(" pj:abc ", "hello world", t0 + 1_000))
        assertNotEquals(a, NaturalKeys.note("pj:abc", "hello world", t0 + NaturalKeys.DAY_MS))
        assertNotEquals(a, NaturalKeys.note("pj:other", "hello world", t0))
        assertTrue(a.startsWith("nt:"))
        assertEquals("", NaturalKeys.note("pj:abc", "  ", t0))
    }

    // ---- WorkflowTemplate ------------------------------------------------

    @Test
    fun workflowTemplateRoundTrips() {
        val w = WorkflowTemplate(
            name = "Weekly brief",
            description = "Summarise the week",
            intentExamples = listOf("make my weekly brief", "summarise this week"),
            planJson = plan,
            useCount = 4,
            createdAt = t0,
            updatedAt = t0 + 1,
            version = 2,
        )
        assertEquals(w, WorkflowTemplate.fromJson(w.toJson()))
    }

    @Test
    fun workflowTemplateInvalidWhenRequiredBlankOrPlanMalformed() {
        assertTrue(WorkflowTemplate(name = "", planJson = plan).isInvalid())
        assertTrue(WorkflowTemplate(name = "x", planJson = "").isInvalid())
        assertTrue(WorkflowTemplate(name = "x", planJson = "not json").isInvalid())
        assertTrue(WorkflowTemplate(name = "x", planJson = """{"steps":[{"id":"a"}]}""").isInvalid())
        assertTrue(WorkflowTemplate(name = "x", planJson = """{"title":"t","steps":[]}""").isInvalid())
        assertNull(WorkflowTemplate.fromJson(garbage))
        assertNull(WorkflowTemplate.fromJson(JSONObject().put("name", "x").put("planJson", "[1]")))
    }

    @Test
    fun workflowTemplateKeyNormalisesAndBlankIsEmpty() {
        val a = NaturalKeys.workflowTemplate("Weekly Brief")
        assertEquals(a, NaturalKeys.workflowTemplate("  weekly   brief "))
        assertTrue(a.startsWith("wt:"))
        assertEquals("", NaturalKeys.workflowTemplate(""))
    }

    @Test
    fun workflowTemplateRegistryCreatesReusesThenBumps() {
        val reg = Registry("templates", Schemes.WorkflowTemplates)
        assertIs<Registry.Put.Created<WorkflowTemplate>>(
            reg.upsert(WorkflowTemplate(name = "Brief", planJson = plan), now = t0),
        )
        assertIs<Registry.Put.Reused<WorkflowTemplate>>(
            reg.upsert(WorkflowTemplate(name = "Brief", planJson = plan), now = t0 + 1),
        )
        val bumped = reg.upsert(WorkflowTemplate(name = "Brief", planJson = plan, useCount = 1), now = t0 + 2)
        assertIs<Registry.Put.Bumped<WorkflowTemplate>>(bumped)
        assertEquals(2, bumped.current.version)
        assertEquals(t0, bumped.current.createdAt)
        assertEquals(1, reg.size)
    }

    // ---- PromptTemplate --------------------------------------------------

    @Test
    fun promptTemplateRoundTrips() {
        val p = PromptTemplate(
            name = "Research brief",
            role = "RESEARCH",
            body = "Research {topic} for {audience}.",
            variables = listOf("topic", "audience"),
            createdAt = t0,
            updatedAt = t0,
            version = 1,
        )
        assertEquals(p, PromptTemplate.fromJson(p.toJson()))
    }

    @Test
    fun promptTemplateInvalidWhenNameOrBodyBlankOrGarbageDecodesNull() {
        assertTrue(PromptTemplate(name = "", body = "b").isInvalid())
        assertTrue(PromptTemplate(name = "n", body = " ").isInvalid())
        assertNull(PromptTemplate.fromJson(garbage))
        assertNull(PromptTemplate.fromJson(JSONObject().put("name", "n")))
    }

    @Test
    fun promptTemplateKeyNormalisesAndBlankIsEmpty() {
        val a = NaturalKeys.promptTemplate("Research Brief")
        assertEquals(a, NaturalKeys.promptTemplate(" research   brief"))
        assertTrue(a.startsWith("pt:"))
        assertEquals("", NaturalKeys.promptTemplate("!!!"))
    }

    // ---- ArchitecturalPrinciple ------------------------------------------

    @Test
    fun principleRoundTrips() {
        val p = ArchitecturalPrinciple(
            title = "Reuse first",
            statement = "Look up by natural key before creating.",
            rationale = "Duplicates corrupt history.",
            scope = "global",
            createdAt = t0,
            updatedAt = t0,
            version = 1,
        )
        assertEquals(p, ArchitecturalPrinciple.fromJson(p.toJson()))
    }

    @Test
    fun principleInvalidWhenTitleOrStatementBlankOrGarbageDecodesNull() {
        assertTrue(ArchitecturalPrinciple(title = "", statement = "s").isInvalid())
        assertTrue(ArchitecturalPrinciple(title = "t", statement = "").isInvalid())
        assertNull(ArchitecturalPrinciple.fromJson(garbage))
        assertNull(ArchitecturalPrinciple.fromJson(JSONObject().put("title", "t")))
    }

    @Test
    fun principleKeyNormalisesAndBlankIsEmpty() {
        val a = NaturalKeys.principle("Reuse First")
        assertEquals(a, NaturalKeys.principle("  reuse   first "))
        assertTrue(a.startsWith("pr:"))
        assertEquals("", NaturalKeys.principle("   "))
    }

    // ---- ContentPreference -----------------------------------------------

    @Test
    fun contentPreferenceRoundTrips() {
        val c = ContentPreference(
            scope = "linkedin",
            tone = "plain",
            audience = "founders",
            channels = listOf("linkedin", "newsletter"),
            cadence = "weekly",
            doNots = listOf("no emojis", "no hype"),
            createdAt = t0,
            updatedAt = t0,
            version = 1,
        )
        assertEquals(c, ContentPreference.fromJson(c.toJson()))
    }

    @Test
    fun contentPreferenceInvalidWhenScopeBlankOrGarbageDecodesNull() {
        assertTrue(ContentPreference(scope = " ").isInvalid())
        assertTrue(ContentPreference(scope = "global", doNots = listOf("")).isInvalid())
        assertNull(ContentPreference.fromJson(garbage))
    }

    @Test
    fun contentPreferenceKeyLowercasesScopeAndBlankIsEmpty() {
        val a = NaturalKeys.preference("Global")
        assertEquals(a, NaturalKeys.preference(" global "))
        assertNotEquals(a, NaturalKeys.preference("linkedin"))
        assertTrue(a.startsWith("cp:"))
        assertEquals("", NaturalKeys.preference("  "))
    }

    // ---- ChannelAccount --------------------------------------------------

    @Test
    fun channelAccountRoundTripsAndStartsDisabledByDefault() {
        val a = ChannelAccount(
            platform = "linkedin",
            handle = "ada",
            displayName = "Ada Lovelace",
            profileUrl = "https://www.linkedin.com/in/ada",
            enabled = true,
            createdAt = t0,
            updatedAt = t0,
            version = 1,
        )
        assertEquals(a, ChannelAccount.fromJson(a.toJson()))
        assertFalse(ChannelAccount(platform = "x", handle = "ada").enabled)
    }

    @Test
    fun channelAccountInvalidWhenPlatformOrHandleBlankOrGarbageDecodesNull() {
        assertTrue(ChannelAccount(platform = "", handle = "ada").isInvalid())
        assertTrue(ChannelAccount(platform = "x", handle = " ").isInvalid())
        assertNull(ChannelAccount.fromJson(garbage))
        assertNull(ChannelAccount.fromJson(JSONObject().put("platform", "x")))
    }

    @Test
    fun channelAccountKeyNormalisesPlatformAndHandle() {
        val a = NaturalKeys.channelAccount("LinkedIn", "@Ada")
        assertEquals(a, NaturalKeys.channelAccount(" linkedin ", " ada "))
        assertNotEquals(a, NaturalKeys.channelAccount("linkedin", "grace"))
        assertTrue(a.startsWith("ca:"))
        assertEquals("", NaturalKeys.channelAccount("linkedin", "  "))
        assertEquals("", NaturalKeys.channelAccount("", "ada"))
    }

    // ---- ProviderAdapter -------------------------------------------------

    @Test
    fun providerAdapterRoundTrips() {
        val p = ProviderAdapter(
            targetId = "gemini",
            channel = "web",
            adapterVersion = "1.2.0",
            payloadJson = """{"input":"textarea#prompt","send":"button[aria-label=Send]"}""",
            verifiedAt = t0,
            createdAt = t0,
            updatedAt = t0,
            version = 1,
        )
        assertEquals(p, ProviderAdapter.fromJson(p.toJson()))
    }

    @Test
    fun providerAdapterInvalidOnBadChannelVersionOrPayload() {
        val ok = ProviderAdapter(targetId = "gemini", channel = "web", adapterVersion = "1.2.0", payloadJson = "{}")
        assertFalse(ok.isInvalid())
        assertTrue(ok.copy(channel = "desktop").isInvalid())
        assertTrue(ok.copy(adapterVersion = "1.2").isInvalid())
        assertTrue(ok.copy(adapterVersion = "v1.2.0").isInvalid())
        assertTrue(ok.copy(payloadJson = "[1,2]").isInvalid())
        assertTrue(ok.copy(payloadJson = "not json").isInvalid())
        assertTrue(ok.copy(targetId = "").isInvalid())
    }

    @Test
    fun providerAdapterGarbageDecodesNull() {
        assertNull(ProviderAdapter.fromJson(garbage))
        assertNull(ProviderAdapter.fromJson(JSONObject().put("targetId", "gemini")))
    }

    @Test
    fun providerAdapterKeyNormalisesAndBlankIsEmpty() {
        val a = NaturalKeys.adapter("WEB", " Gemini ")
        assertEquals(a, NaturalKeys.adapter("web", "gemini"))
        assertNotEquals(a, NaturalKeys.adapter("app", "gemini"))
        assertTrue(a.startsWith("ad:"))
        assertEquals("", NaturalKeys.adapter("web", " "))
    }
}
