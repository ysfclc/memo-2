package app.memo.platform.android.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryPreflightTest {

    private val gib = 1L shl 30

    @Test
    fun enoughRamForModelWithHeadroomPasses() {
        // 1 GiB model needs about 1.3 GiB + 768 MiB.
        assertTrue(hasRamHeadroom(availableBytes = 3 * gib, modelBytes = gib))
    }

    @Test
    fun tooLittleRamFails() {
        assertFalse(hasRamHeadroom(availableBytes = gib, modelBytes = gib))
    }

    @Test
    fun unknownSizeNeedsOnlyHeadroom() {
        assertTrue(hasRamHeadroom(availableBytes = LlmDefaults.RAM_HEADROOM_BYTES, modelBytes = 0))
        assertFalse(hasRamHeadroom(availableBytes = LlmDefaults.RAM_HEADROOM_BYTES - 1, modelBytes = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeModelSizeIsRejected() {
        hasRamHeadroom(availableBytes = gib, modelBytes = -1)
    }
}
