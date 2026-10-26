package app.memo.core.redact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedactorTest {

    @Test
    fun replacesKnownSecretShapesWithVisibleMarkers() {
        assertEquals("[REDACTED_OPENAI_KEY]", Redactor.redact("sk-Abc123XyZ0123456789").text)
        assertEquals(
            "keep [REDACTED_GITHUB_TOKEN] keep",
            Redactor.redact("keep ghp_0123456789abCDEF keep").text,
        )
        assertEquals("[REDACTED_AWS_KEY]", Redactor.redact("AKIA1234567890ABCD").text)
        assertEquals(
            "[REDACTED_JWT]",
            Redactor.redact("eyJhbGciOiJIUzI1NiIs.aXNfdGhpcw.c2VjcmV0X3sxfQ").text,
        )
        assertEquals(
            "auth: [REDACTED_BEARER]",
            Redactor.redact("auth: Bearer 0123456789abcDEF").text,
        )
    }

    @Test
    fun passwordAssignmentsAreScrubbed() {
        // The whole "keyword=value" span is replaced, context around it kept.
        assertEquals("[REDACTED_SECRET_ASSIGN]", Redactor.redact("password=hunter2abc").text)
        assertEquals("[REDACTED_SECRET_ASSIGN]", Redactor.redact("api_key: 12345678").text)
        assertEquals(
            "config [REDACTED_SECRET_ASSIGN] ok",
            Redactor.redact("config password=hunter2abc ok").text,
        )
        // Prose without the assignment shape is left alone.
        assertEquals("my password is strong", Redactor.redact("my password is strong").text)
    }

    @Test
    fun cardLikeRunsAreFlaggedButPhonesAreNot() {
        val card = Redactor.redact("card 4111 1111 1111 1111 ok")
        assertEquals("card [REDACTED_CARD] ok", card.text)
        val untouched = "call Dana at 415-555-0123 about the invoice"
        assertEquals(0, Redactor.scan(untouched).size)
        assertEquals(untouched, Redactor.redact(untouched).text)
    }

    @Test
    fun privateKeyBlocksAreRedactedAcrossLines() {
        val pem =
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val result = Redactor.redact("header $pem footer")
        assertEquals("header [REDACTED_PRIVATE_KEY] footer", result.text)
    }

    @Test
    fun overlappingMatchesResolveByPriorityAndLength() {
        val redacted = Redactor.redact("bearer sk-Abc123XyZ0123456789")
        // The bearer rule starts earlier and its span swallows the sk- token.
        assertEquals("[REDACTED_BEARER]", redacted.text)
        assertEquals("BEARER", redacted.hits.single().kind)
    }

    @Test
    fun scanProducesSpansThatMatchReplacements() {
        val text = "a sk-Abc123XyZ0123456789 b"
        val hits = Redactor.scan(text)
        assertEquals(1, hits.size)
        assertEquals("OPENAI_KEY", hits.single().kind)
        assertEquals("sk-Abc123XyZ0123456789", text.substring(hits.single().start, hits.single().end))
    }
}
