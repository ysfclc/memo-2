package app.memo.platform.android.llm.llama

import kotlin.test.Test
import kotlin.test.assertEquals

class CpuFeatureSelectTest {

    /** A real ARMv8.2 `Features` line, shortened. It lists asimddp and fphp. */
    private val armv82 =
        " fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"

    /** An ARMv8.0 Cortex-A53-class line. No dot product, no fphp. */
    private val armv80 = " fp asimd evtstrm aes pmull sha1 sha2 crc32"

    @Test
    fun armv82WithDotprodSelectsDotprodBuild() {
        assertEquals(LLAMA_LIB_DOTPROD, selectLlamaLibrary(isArm64 = true, features = armv82))
    }

    @Test
    fun armv80SelectsGenericBuild() {
        assertEquals(LLAMA_LIB_GENERIC, selectLlamaLibrary(isArm64 = true, features = armv80))
    }

    @Test
    fun dotprodWithoutScalarFp16SelectsGeneric() {
        val noFphp = armv82.replace(" fphp", "")
        assertEquals(LLAMA_LIB_GENERIC, selectLlamaLibrary(isArm64 = true, features = noFphp))
    }

    @Test
    fun fp16WithoutDotprodSelectsGeneric() {
        val noDotprod = armv82.replace(" asimddp", "")
        assertEquals(LLAMA_LIB_GENERIC, selectLlamaLibrary(isArm64 = true, features = noDotprod))
    }

    @Test
    fun tokenMatchIsWholeWordNotSubstring() {
        // "asimddpx" must not count as asimddp.
        assertEquals(
            LLAMA_LIB_GENERIC,
            selectLlamaLibrary(isArm64 = true, features = " fp asimddpx fphp"),
        )
    }

    @Test
    fun missingFeaturesLineSelectsGeneric() {
        assertEquals(LLAMA_LIB_GENERIC, selectLlamaLibrary(isArm64 = true, features = null))
    }

    @Test
    fun x86AlwaysSelectsGenericEvenIfTheTextMentionsDotprod() {
        assertEquals(LLAMA_LIB_GENERIC, selectLlamaLibrary(isArm64 = false, features = armv82))
    }

    @Test
    fun tabsAndRepeatedSpacesAreSeparators() {
        assertEquals(
            LLAMA_LIB_DOTPROD,
            selectLlamaLibrary(isArm64 = true, features = "fp\tasimd  fphp\t\tasimddp"),
        )
    }
}
