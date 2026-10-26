package app.memo.core.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.json.JSONArray
import org.json.JSONObject

class CanonicalJsonTest {

    @Test
    fun sortsObjectKeys() {
        val obj = JSONObject().put("zeta", 1).put("alpha", 2).put("mid", 3)
        assertEquals("""{"alpha":2,"mid":3,"zeta":1}""", CanonicalJson.canonical(obj))
    }

    @Test
    fun nestedStructures() {
        val obj = JSONObject()
            .put("list", JSONArray().put(2).put(1))
            .put("nested", JSONObject().put("b", JSONObject().put("y", 1).put("x", 2)))
        assertEquals(
            """{"list":[2,1],"nested":{"b":{"x":2,"y":1}}}""",
            CanonicalJson.canonical(obj),
        )
    }

    @Test
    fun scalarFormats() {
        val obj = JSONObject()
            .put("s", "he said \"hi\" \\ /")
            .put("b", true)
            .put("i", 42)
            .put("l", 9007199254740993L)
            .put("n", JSONObject.NULL)
        assertEquals(
            """{"b":true,"i":42,"l":9007199254740993,"n":null,"s":"he said \"hi\" \\ /"}""",
            CanonicalJson.canonical(obj),
        )
    }

    @Test
    fun doublesUsePlainDeterministicForm() {
        assertEquals("""{"d":0.1}""", CanonicalJson.canonical(JSONObject().put("d", 0.1)))
        assertEquals("""{"d":1}""", CanonicalJson.canonical(JSONObject().put("d", 1.0)))
        assertEquals("""{"d":-2.5}""", CanonicalJson.canonical(JSONObject().put("d", -2.5)))
    }

    @Test
    fun hashStableAcrossInsertionOrder() {
        val a = JSONObject().put("name", "Ada").put("role", "dev")
        val b = JSONObject().put("role", "dev").put("name", "Ada")
        assertEquals(CanonicalJson.contentHash(a), CanonicalJson.contentHash(b))
    }

    @Test
    fun hashChangesWithContent() {
        val a = JSONObject().put("name", "Ada")
        val b = JSONObject().put("name", "Grace")
        assertNotEquals(CanonicalJson.contentHash(a), CanonicalJson.contentHash(b))
    }
}
