package app.memo.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class FitBadgeTest {

    private val gb = 1_000_000_000L

    @Test
    fun comfortableRamIsFits() {
        // Stated minimum 3 GB: 8 GB covers it with headroom.
        assertEquals(Fit.FITS, ModelFit.judge(deviceRamBytes = 8 * gb, minRamBytes = 3 * gb, sizeBytes = 2 * gb))
    }

    @Test
    fun ramJustAboveMinimumIsTight() {
        // 3.5 GB device for a 3 GB minimum: covers it, but not with 25 percent headroom.
        assertEquals(Fit.TIGHT, ModelFit.judge(deviceRamBytes = 3_500_000_000L, minRamBytes = 3 * gb, sizeBytes = 2 * gb))
    }

    @Test
    fun ramBelowMinimumIsTooLarge() {
        assertEquals(Fit.TOO_LARGE, ModelFit.judge(deviceRamBytes = 2 * gb, minRamBytes = 3 * gb, sizeBytes = 2 * gb))
    }

    @Test
    fun missingMinimumUsesTwiceFileSize() {
        // 2 GB file, no stated minimum: estimate 4 GB required.
        assertEquals(4 * gb, ModelFit.requiredRamBytes(minRamBytes = 0, sizeBytes = 2 * gb))
        assertEquals(Fit.FITS, ModelFit.judge(deviceRamBytes = 8 * gb, minRamBytes = 0, sizeBytes = 2 * gb))
        assertEquals(Fit.TIGHT, ModelFit.judge(deviceRamBytes = 4 * gb, minRamBytes = 0, sizeBytes = 2 * gb))
    }

    @Test
    fun unknownRamOrRequirementIsUnknown() {
        assertEquals(Fit.UNKNOWN, ModelFit.judge(deviceRamBytes = 0, minRamBytes = 3 * gb, sizeBytes = 2 * gb))
        assertEquals(Fit.UNKNOWN, ModelFit.judge(deviceRamBytes = 8 * gb, minRamBytes = 0, sizeBytes = 0))
    }
}
