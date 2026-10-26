package app.memo.core.policy

import app.memo.core.policy.ActionClass.BLOCKED
import app.memo.core.policy.ActionClass.NEEDS_APPROVAL
import app.memo.core.policy.ActionClass.SAFE
import kotlin.test.Test
import kotlin.test.assertEquals

/** Second-review hardening: invisible characters, mixed scripts, and vocabulary gaps. */
class ActionPolicyHardeningTest {

    private fun assertClass(label: String, expected: ActionClass) {
        assertEquals(expected, classify(label).cls, "label \"$label\"")
    }

    @Test
    fun invisibleFillersAndControlCharactersCannotHideAWord() {
        assertClass("Paㅤy now", BLOCKED)          // Hangul filler U+3164
        assertClass("Paᅠy now", BLOCKED)          // Hangul jungseong filler
        assertClass("Paᅟy now", BLOCKED)          // Hangul choseong filler
        assertClass("Paﾠy now", BLOCKED)          // halfwidth Hangul filler
        assertClass("pa\u0001y", BLOCKED)              // control character, removed without a space
        assertClass("pa͏y now", BLOCKED)          // combining grapheme joiner
        assertClass("pa᠋y now", BLOCKED)          // Mongolian free variation selector
        assertClass("pa️y now", BLOCKED)          // variation selector-16
        assertClass("pa​y now", BLOCKED)          // zero-width space (Cf, unchanged behaviour)
    }

    @Test
    fun mixedScriptTokensFailClosed() {
        assertClass("Рay", BLOCKED)               // Cyrillic capital ER + "ay" reads as "Pay"
        assertClass("Ԁelete", NEEDS_APPROVAL)     // Cyrillic Komi DE + "elete" reads as "Delete"
        assertClass("Deleτe", NEEDS_APPROVAL)     // Greek tau inside "Delete"
    }

    @Test
    fun mixedScriptWithoutRuleHitStillNeedsApproval() {
        assertClass("Helоo", NEEDS_APPROVAL)      // Cyrillic o in an otherwise innocent word
    }

    @Test
    fun pureCyrillicAndGreekLabelsAreNotSpoofedByTheFold() {
        assertClass("привет", SAFE) // "привет"
    }

    @Test
    fun sensitiveInputSeesTheFoldedSpellingOfAMixedScriptLabel() {
        val c = classifyControl("pаssword", ControlRole.INPUT)  // Cyrillic a inside "password"
        assertEquals(BLOCKED, c.cls)
    }

    @Test
    fun vocabularyTableForPaymentPublishAndFalsePositives() {
        val table = listOf(
            // PAYMENT, BLOCKED
            "Donate now" to BLOCKED,
            "Donate" to BLOCKED,
            "Tip the author" to BLOCKED,
            "Place bid" to BLOCKED,
            "Bid now" to BLOCKED,
            "Redeem points" to BLOCKED,
            "Start free trial" to BLOCKED,
            "Confirm order" to BLOCKED,
            "Bağış yap" to BLOCKED,
            "Spenden" to BLOCKED,
            "Faire un don" to BLOCKED,
            "Donar ahora" to BLOCKED,
            "Napiwek" to BLOCKED,
            // PAYMENT, NEEDS_APPROVAL
            "Upgrade" to NEEDS_APPROVAL,
            "Try free" to NEEDS_APPROVAL,
            "Get premium" to NEEDS_APPROVAL,
            // PUBLISH, NEEDS_APPROVAL
            "Share" to NEEDS_APPROVAL,
            "Reply" to NEEDS_APPROVAL,
            "Comment" to NEEDS_APPROVAL,
            "Like" to NEEDS_APPROVAL,
            "Repost" to NEEDS_APPROVAL,
            "Retweet" to NEEDS_APPROVAL,
            "Send message" to NEEDS_APPROVAL,
            "Message" to NEEDS_APPROVAL,
            "Odpowiedz" to NEEDS_APPROVAL,
            "Yanıtla" to NEEDS_APPROVAL,
            // False-positive guards: whole-token matching
            "Tips and tricks" to SAFE,
            "Tips & tricks" to SAFE,
            "Tips" to SAFE,
            "Messages" to SAFE,
            "Likes received" to SAFE, // whole-token matching: "likes" is not "like"
            "Shared with me" to SAFE,
            "Tipping point" to SAFE,
            "Liked" to SAFE,
            "Settings" to SAFE,
        )
        val mismatches = table.mapNotNull { (label, expected) ->
            val actual = classify(label).cls
            if (actual == expected) null else "\"$label\": expected $expected, got $actual"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun tipAsAWholeTokenBlocksButItsPluralDoesNot() {
        assertEquals(BLOCKED, classify("Leave a tip").cls)
        assertEquals(SAFE, classify("Tips and tricks").cls)
    }
}
