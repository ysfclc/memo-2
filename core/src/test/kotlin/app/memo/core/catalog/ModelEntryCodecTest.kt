package app.memo.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelEntryCodecTest {
    @Test
    fun pinnedEntriesRoundTrip() {
        val entries = ModelCatalog.entries.take(3)

        assertEquals(entries, ModelEntryCodec.decode(ModelEntryCodec.encode(entries)))
    }

    @Test
    fun unknownEnvelopeFieldsAreRejected() {
        assertFailsWith<ModelEntryCodecException> {
            ModelEntryCodec.decode("""{"v":1,"entries":[],"extra":true}""")
        }
    }
}
