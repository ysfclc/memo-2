package app.memo.hub

/**
 * Decides where a download writes, given the state of the `.part` file and the answer
 * of the server. Pure: no I/O.
 *
 * Callers send `Range: bytes=<partLength>-` when [partLength] is greater than zero and
 * the part is incomplete, then pass the status and `Content-Range` here.
 */
object RangeResume {

    sealed interface Plan {
        /** Write the response body into the part starting at [offset]. 0 truncates the part. */
        data class Write(val offset: Long) : Plan

        /** The part already holds the whole file; skip the body and go to verification. */
        data object Complete : Plan

        /** The part cannot be continued. Delete it and request the file again from byte 0. */
        data object RestartRequest : Plan
    }

    /** True when a request is needed at all: an empty or short part, or no part. */
    fun needsRequest(partLength: Long, expectedSize: Long): Boolean = partLength < expectedSize

    /**
     * @param partLength bytes already in the `.part` file
     * @param expectedSize the size the catalog or the tree API declares
     * @param status HTTP status of the response (200, 206 or 416)
     * @param contentRange the `Content-Range` header, if any
     */
    fun plan(partLength: Long, expectedSize: Long, status: Int, contentRange: String?): Plan {
        require(partLength >= 0 && expectedSize > 0) { "lengths must be non-negative and the size positive" }
        return when (status) {
            // The server sent the whole file. Truncate and write it, whatever the part held.
            200 -> Plan.Write(0L)

            206 -> {
                val start = parseContentRangeStart(contentRange)
                when {
                    start == null -> Plan.RestartRequest
                    start == partLength -> Plan.Write(partLength)
                    // The bytes do not join the part. Never append them.
                    else -> Plan.RestartRequest
                }
            }

            416 -> when {
                // Exactly the whole file: verify it. Longer than the file is inconsistent: restart.
                partLength == expectedSize -> Plan.Complete
                else -> Plan.RestartRequest
            }

            else -> throw IllegalArgumentException("status $status is not a resume outcome")
        }
    }

    /**
     * The first byte offset of a `Content-Range: bytes <start>-<end>/<total>` header,
     * or null when the header is absent or malformed.
     */
    fun parseContentRangeStart(header: String?): Long? {
        if (header == null) return null
        val match = CONTENT_RANGE.matchEntire(header.trim()) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
}
