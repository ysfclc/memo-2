package app.memo.hub

import app.memo.hub.RangeResume.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RangeResumeLogicTest {

    private val size = 1_000L

    @Test
    fun freshDownloadTakes200AsWholeFile() {
        assertEquals(Plan.Write(0L), RangeResume.plan(0L, size, 200, null))
    }

    @Test
    fun resumeAppendsOnMatchingContentRange() {
        assertEquals(Plan.Write(400L), RangeResume.plan(400L, size, 206, "bytes 400-999/1000"))
    }

    @Test
    fun serverIgnoringRangeRestartsFromZero() {
        // 200 with a partial part: the body is the whole file, so truncate and write it.
        assertEquals(Plan.Write(0L), RangeResume.plan(400L, size, 200, null))
    }

    @Test
    fun mismatchedContentRangeNeverAppends() {
        assertEquals(Plan.RestartRequest, RangeResume.plan(400L, size, 206, "bytes 0-999/1000"))
        assertEquals(Plan.RestartRequest, RangeResume.plan(400L, size, 206, null))
        assertEquals(Plan.RestartRequest, RangeResume.plan(400L, size, 206, "garbage"))
    }

    @Test
    fun rangeNotSatisfiableCompletesOnlyWhenPartIsWholeFile() {
        assertEquals(Plan.Complete, RangeResume.plan(size, size, 416, null))
        assertEquals(Plan.RestartRequest, RangeResume.plan(400L, size, 416, null))
        assertEquals(Plan.RestartRequest, RangeResume.plan(size + 5, size, 416, null))
    }

    @Test
    fun requestOnlyWhenPartIsShort() {
        assertTrue(RangeResume.needsRequest(0L, size))
        assertTrue(RangeResume.needsRequest(999L, size))
        assertFalse(RangeResume.needsRequest(size, size))
    }

    @Test
    fun contentRangeParsing() {
        assertEquals(42L, RangeResume.parseContentRangeStart("bytes 42-99/100"))
        assertEquals(0L, RangeResume.parseContentRangeStart("bytes 0-0/*"))
        assertNull(RangeResume.parseContentRangeStart("items 1-2/3"))
        assertNull(RangeResume.parseContentRangeStart(null))
    }

    @Test
    fun unexpectedStatusIsRejected() {
        assertFailsWith<IllegalArgumentException> { RangeResume.plan(0L, size, 500, null) }
    }
}
