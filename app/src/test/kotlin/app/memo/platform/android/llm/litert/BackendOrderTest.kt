package app.memo.platform.android.llm.litert

import app.memo.core.platform.ComputePreference
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendOrderTest {

    @Test
    fun autoTriesGpuThenCpu() {
        assertEquals(listOf(GPU_LABEL, CPU_LABEL), backendOrder(ComputePreference.AUTO))
    }

    @Test
    fun gpuStillFallsBackToCpu() {
        assertEquals(listOf(GPU_LABEL, CPU_LABEL), backendOrder(ComputePreference.GPU))
    }

    @Test
    fun cpuNeverTriesGpu() {
        assertEquals(listOf(CPU_LABEL), backendOrder(ComputePreference.CPU))
    }
}
