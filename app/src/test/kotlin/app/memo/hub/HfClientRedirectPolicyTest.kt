package app.memo.hub

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HfClientRedirectPolicyTest {

    private fun url(s: String) = URL(s)

    @Test
    fun plainHttpHopIsRefused() {
        assertNotNull(HfRedirectPolicy.refusal(url("http://cdn-lfs.hf.co/file")))
        assertNotNull(HfRedirectPolicy.refusal(url("http://huggingface.co/api/models")))
    }

    @Test
    fun foreignHostIsRefused() {
        assertNotNull(HfRedirectPolicy.refusal(url("https://evil.example.com/model.gguf")))
        assertNotNull(HfRedirectPolicy.refusal(url("https://hf.co.evil.example.com/x")))
        assertNotNull(HfRedirectPolicy.refusal(url("https://evilhf.co/x")))
        assertNotNull(HfRedirectPolicy.refusal(url("https://huggingface.co.attacker.net/x")))
    }

    @Test
    fun nonDefaultPortIsRefused() {
        assertNotNull(HfRedirectPolicy.refusal(url("https://huggingface.co:8443/x")))
    }

    @Test
    fun allowedCdnAndHubHostsAreFine() {
        assertNull(HfRedirectPolicy.refusal(url("https://cdn-lfs.hf.co/repos/ab/cd/blob")))
        assertNull(HfRedirectPolicy.refusal(url("https://cdn-lfs-us-1.hf.co/repos/x")))
        assertNull(HfRedirectPolicy.refusal(url("https://us.aws.cdn.hf.co/xet-bridge-us/abc?sig=x")))
        assertNull(HfRedirectPolicy.refusal(url("https://cas-bridge.xethub.hf.co/xet-bridge/abc")))
        assertNull(HfRedirectPolicy.refusal(url("https://huggingface.co/api/models/x/y")))
        assertNull(HfRedirectPolicy.refusal(url("https://HUGGINGFACE.CO/api/models")))
    }

    @Test
    fun tokenGoesOnlyToTheHubNotTheCdn() {
        assertTrue(HfRedirectPolicy.isApiHost(url("https://huggingface.co/api/models/x")))
        assertTrue(HfRedirectPolicy.isApiHost(url("https://www.huggingface.co/x")))
        assertFalse(HfRedirectPolicy.isApiHost(url("https://cdn-lfs.hf.co/x")))
        assertFalse(HfRedirectPolicy.isApiHost(url("https://evil.example.com/x")))
    }
}
