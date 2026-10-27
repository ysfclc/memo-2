package app.memo.core.chat

/** How well a model is expected to run on this device, judged from total RAM. */
enum class Fit { FITS, TIGHT, TOO_LARGE, UNKNOWN }

object ModelFit {
    /** RAM headroom a model needs beyond its stated minimum before it counts as a comfortable fit. */
    const val HEADROOM_PERCENT: Int = 125

    /**
     * Required RAM: the catalog minimum when stated, otherwise twice the file size as a rough
     * estimate. Zero when neither is known.
     */
    fun requiredRamBytes(minRamBytes: Long, sizeBytes: Long): Long = when {
        minRamBytes > 0 -> minRamBytes
        sizeBytes > 0 -> sizeBytes * 2
        else -> 0L
    }

    /**
     * FITS when [deviceRamBytes] covers the requirement with [HEADROOM_PERCENT] headroom, TIGHT
     * when it covers the bare requirement, TOO_LARGE when it does not, and UNKNOWN when the
     * device RAM or the requirement is unknown.
     */
    fun judge(deviceRamBytes: Long, minRamBytes: Long, sizeBytes: Long): Fit {
        val required = requiredRamBytes(minRamBytes, sizeBytes)
        if (deviceRamBytes <= 0 || required <= 0) return Fit.UNKNOWN
        return when {
            deviceRamBytes * 100 >= required * HEADROOM_PERCENT -> Fit.FITS
            deviceRamBytes >= required -> Fit.TIGHT
            else -> Fit.TOO_LARGE
        }
    }
}
