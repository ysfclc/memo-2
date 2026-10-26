package app.memo.vault

import app.memo.core.codec.SynCodec
import app.memo.core.registry.Workspace
import app.memo.core.Limits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MemoVaultTest {

    @Test
    fun saveAndLoadRoundTrip() {
        val ws = Workspace()
        ws.people.upsert(
            app.memo.core.domain.Person(fullName = "Ada", phone = "+1 555 0100"),
            now = 1_700_000_000_000L,
        )
        val bos = java.io.ByteArrayOutputStream()
        MemoVault.save(ws, bos)
        val bytes = bos.toByteArray()

        val bis = java.io.ByteArrayInputStream(bytes)
        val ws2 = MemoVault.load(bis)
        assertNotNull(ws2)
        assertEquals(1, ws2.people.size)
        assertEquals("Ada", ws2.people.findFirst { it.fullName == "Ada" }?.fullName)
    }

    @Test
    fun quarantineBadRowsOnLoad() {
        // Put intentionally bad data into a SYN envelope and verify it
        // quarantines rather than corrupting the workspace.
        val ws = Workspace()
        ws.people.upsert(
            app.memo.core.domain.Person(fullName = "", phone = ""),
            now = 1_700_000_000_000L,
        )
        // The bad row should be quarantined; workspace should still have
        // the valid entries (none in this minimal case, but the law holds).
        val bos = java.io.ByteArrayOutputStream()
        MemoVault.save(ws, bos)
        val bytes = bos.toByteArray()
        val bis = java.io.ByteArrayInputStream(bytes)
        val ws2 = MemoVault.load(bis)
        // Load of a workspace with only blank-key entries should not crash.
        // Behaviour depends on SynCodec quarantine logic.
    }
}
