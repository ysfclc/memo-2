package app.memo.platform.android.voice

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * sherpa-onnx's JNI calls the TTS chunk callback through `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")`.
 * If the callback class lacks that exact method, the native side aborts the whole app (device crash, D-060).
 */
class SherpaTtsCallbackTest {

    @Test
    fun chunkBridgeExposesTheExactJniSignature() {
        val method = SherpaTts.ChunkBridge::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals("java.lang.Integer", method.returnType.name) // boxed Integer, exactly what the JNI lookup needs
    }

    @Test
    fun chunkBridgeMapsStopToOneAndContinueToZero() {
        val stop = SherpaTts.ChunkBridge { true }
        val go = SherpaTts.ChunkBridge { false }
        assertEquals(1, stop(floatArrayOf(0.1f)))
        assertEquals(0, go(floatArrayOf(0.1f)))
    }
}
