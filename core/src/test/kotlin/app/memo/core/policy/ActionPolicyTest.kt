package app.memo.core.policy

import app.memo.core.policy.ActionClass.BLOCKED
import app.memo.core.policy.ActionClass.NEEDS_APPROVAL
import app.memo.core.policy.ActionClass.SAFE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionPolicyTest {

    /** label -> expected class. Covers every category and all five languages. */
    private val table: List<Pair<String, ActionClass>> = listOf(
        // PAYMENT (English)
        "Pay now" to BLOCKED,
        "Payment method" to BLOCKED,
        "Buy now" to BLOCKED,
        "Proceed to checkout" to BLOCKED,
        "Place order" to BLOCKED,
        "Transfer money" to BLOCKED,
        "Send money" to BLOCKED,
        "Withdraw" to BLOCKED,
        "Cash out" to BLOCKED,
        "Top up" to BLOCKED,
        "Add card" to BLOCKED,
        "Purchase" to BLOCKED,
        "Subscribe to premium" to BLOCKED,
        // ACCOUNT_SECURITY (English)
        "Disable security" to BLOCKED,
        "Factory reset" to BLOCKED,
        "Delete account" to BLOCKED,
        "Remove account" to BLOCKED,
        "Change password" to BLOCKED,
        "Grant permission" to BLOCKED,
        "Install unknown apps" to BLOCKED,
        // CREDENTIAL (English)
        "Password" to BLOCKED,
        "Enter passcode" to BLOCKED,
        "PIN" to BLOCKED,
        "Security code" to BLOCKED,
        "One-time code" to BLOCKED,
        "OTP" to BLOCKED,
        "2FA" to BLOCKED,
        "Verification code" to BLOCKED,
        "Solve the captcha" to BLOCKED,
        "Confirm reCAPTCHA" to BLOCKED,
        // PUBLISH (English)
        "Post" to NEEDS_APPROVAL,
        "Publish" to NEEDS_APPROVAL,
        "Share publicly" to NEEDS_APPROVAL,
        "Tweet" to NEEDS_APPROVAL,
        "Send" to NEEDS_APPROVAL,
        "Submit application" to NEEDS_APPROVAL,
        "Apply" to NEEDS_APPROVAL,
        "Book a table" to NEEDS_APPROVAL,
        "Confirm" to NEEDS_APPROVAL,
        "Accept" to NEEDS_APPROVAL,
        "Connect" to NEEDS_APPROVAL,
        "Follow" to NEEDS_APPROVAL,
        "Invite" to NEEDS_APPROVAL,
        // DESTRUCTIVE (English)
        "Delete" to NEEDS_APPROVAL,
        "Remove" to NEEDS_APPROVAL,
        "Archive all" to NEEDS_APPROVAL,
        "Unsubscribe all" to NEEDS_APPROVAL,
        "Clear all" to NEEDS_APPROVAL,
        "Discard" to NEEDS_APPROVAL,
        // SAFE (English)
        "Open" to SAFE,
        "Search" to SAFE,
        "Scroll down" to SAFE,
        "Read" to SAFE,
        "Back" to SAFE,
        "Next page" to SAFE,
        "Settings" to SAFE,
        // Turkish
        "Öde" to BLOCKED,
        "Satın al" to BLOCKED,
        "Satın alın" to BLOCKED,
        "Hesabı sil" to BLOCKED,
        "Şifre" to BLOCKED,
        "Doğrulama kodu" to BLOCKED,
        "Güvenlik kodu" to BLOCKED,
        "Kod" to BLOCKED,
        "Gönder" to NEEDS_APPROVAL,
        "Yayınla" to NEEDS_APPROVAL,
        "Başvur" to NEEDS_APPROVAL,
        "Sil" to NEEDS_APPROVAL,
        "Ayarlar" to SAFE,
        "Gönderilenler" to SAFE,
        "Silinmiş öğeler" to SAFE,
        // German
        "Bezahlen" to BLOCKED,
        "Jetzt kaufen" to BLOCKED,
        "Passwort" to BLOCKED,
        "Bestätigungscode" to BLOCKED,
        "Konto löschen" to BLOCKED,
        "Senden" to NEEDS_APPROVAL,
        "Veröffentlichen" to NEEDS_APPROVAL,
        "Bewerben" to NEEDS_APPROVAL,
        "Löschen" to NEEDS_APPROVAL,
        // Spanish
        "Pagar" to BLOCKED,
        "Comprar" to BLOCKED,
        "Contraseña" to BLOCKED,
        "Código de verificación" to BLOCKED,
        "Eliminar cuenta" to BLOCKED,
        "Enviar" to NEEDS_APPROVAL,
        "Publicar" to NEEDS_APPROVAL,
        "Solicitar" to NEEDS_APPROVAL,
        "Eliminar" to NEEDS_APPROVAL,
        // French
        "Payer" to BLOCKED,
        "Acheter" to BLOCKED,
        "Mot de passe" to BLOCKED,
        "Code de vérification" to BLOCKED,
        "Supprimer le compte" to BLOCKED,
        "Envoyer" to NEEDS_APPROVAL,
        "Publier" to NEEDS_APPROVAL,
        "Postuler" to NEEDS_APPROVAL,
        "Supprimer" to NEEDS_APPROVAL,
        // Polish
        "Zapłać" to BLOCKED,
        "Płatność" to BLOCKED,
        "Kup" to BLOCKED,
        "Kup teraz" to BLOCKED,
        "Zamów" to BLOCKED,
        "Przelew" to BLOCKED,
        "Wypłać" to BLOCKED,
        "Usuń konto" to BLOCKED,
        "Zmień hasło" to BLOCKED,
        "Hasło" to BLOCKED,
        "Kod" to BLOCKED,
        "Kod weryfikacyjny" to BLOCKED,
        "PIN" to BLOCKED,
        "Wyślij" to NEEDS_APPROVAL,
        "Wysłać" to NEEDS_APPROVAL,
        "Opublikuj" to NEEDS_APPROVAL,
        "Udostępnij" to NEEDS_APPROVAL,
        "Aplikuj" to NEEDS_APPROVAL,
        "Złóż wniosek" to NEEDS_APPROVAL,
        "Potwierdź" to NEEDS_APPROVAL,
        "Zarezerwuj" to NEEDS_APPROVAL,
        "Usuń" to NEEDS_APPROVAL,
        "Skasuj" to NEEDS_APPROVAL,
        // Polish false-positive guards
        "Wysłane" to SAFE,
        "Kupon" to SAFE,
        "Usunięte elementy" to SAFE,
        "Menedżer haseł" to SAFE,
        // False-positive guards (English)
        "Sent items" to SAFE,
        "Deleted items" to SAFE,
        "Password manager settings" to SAFE,
        "Pinned" to SAFE,
        "Following" to SAFE,
        "Bookmarks" to SAFE,
    )

    @Test
    fun tableDrivenLabelsClassifyAsExpected() {
        val mismatches = table.mapNotNull { (label, expected) ->
            val actual = classify(label).cls
            if (actual == expected) null else "\"$label\": expected $expected, got $actual"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun tableHasAtLeastSixtyRowsAcrossAllClasses() {
        assertTrue(table.size >= 60, "table has ${table.size} rows")
        assertTrue(table.any { it.second == BLOCKED })
        assertTrue(table.any { it.second == NEEDS_APPROVAL })
        assertTrue(table.any { it.second == SAFE })
    }

    @Test
    fun matchedCategoryAndTermAreReported() {
        val pay = classify("Pay now")
        assertEquals(BLOCKED, pay.cls)
        assertEquals(PolicyCategory.PAYMENT, pay.category)
        assertEquals("pay", pay.term)

        val password = classify("Verification code")
        assertEquals(PolicyCategory.CREDENTIAL, password.category)

        val delete = classify("Delete")
        assertEquals(NEEDS_APPROVAL, delete.cls)
        assertEquals(PolicyCategory.DESTRUCTIVE, delete.category)

        val safe = classify("Open")
        assertEquals(Classification(SAFE), safe)
    }

    @Test
    fun blockedBeatsNeedsApprovalAndSafe() {
        assertEquals(PolicyCategory.PAYMENT, classify("Send money").category)
        assertEquals(PolicyCategory.ACCOUNT_SECURITY, classify("Delete account").category)
        assertEquals(BLOCKED, classify("Submit payment").cls)
        assertEquals(PolicyCategory.CREDENTIAL, classify("Send password").category)
        assertEquals(BLOCKED, classify("Send", "Enter PIN").cls)
    }

    @Test
    fun longestPhraseWinsWithinAClass() {
        // "remove account" (BLOCKED) is reported as the matched phrase, not the bare
        // "remove" (NEEDS_APPROVAL) that also appears in it.
        assertEquals("remove account", classify("Remove account").term)
        assertEquals(NEEDS_APPROVAL, classify("Remove items").cls)
    }

    @Test
    fun contextRaisesTheClassification() {
        val typed = classify("Type here", "Enter your password")
        assertEquals(BLOCKED, typed.cls)
        assertEquals(PolicyCategory.CREDENTIAL, typed.category)

        assertEquals(BLOCKED, classify("Field", "OTP code").cls)
        assertEquals(BLOCKED, classify("Input", "password").cls)
        assertEquals(NEEDS_APPROVAL, classify("Continue", "Send message").cls)
    }

    @Test
    fun contextNeverLowersTheLabelClassification() {
        assertEquals(BLOCKED, classify("Password", "Email address").cls)
        assertEquals(SAFE, classify("Email", "Email address").cls)
        assertEquals(SAFE, classify("Search", "Password manager settings").cls)
    }

    @Test
    fun wholeWordMatchingRejectsInflectedAndCompoundForms() {
        assertEquals(SAFE, classify("Sent items").cls)
        assertEquals(SAFE, classify("Deleted items").cls)
        assertEquals(SAFE, classify("Pinned").cls)
        assertEquals(SAFE, classify("Following").cls)
        assertEquals(SAFE, classify("Bookmarks").cls)
        assertEquals(NEEDS_APPROVAL, classify("Send").cls)
        assertEquals(NEEDS_APPROVAL, classify("Delete").cls)
    }

    @Test
    fun passwordManagerSettingsIsDescriptiveNotCredential() {
        assertEquals(SAFE, classify("Password manager settings").cls)
        assertEquals(SAFE, classify("Password policy").cls)
        // A credential term with no read-only tail still blocks.
        assertEquals(BLOCKED, classify("Password").cls)
        assertEquals(BLOCKED, classify("Enter password").cls)
        // The tail exception applies to CREDENTIAL only; destructive labels keep their class.
        assertEquals(NEEDS_APPROVAL, classify("Delete settings").cls)
    }

    @Test
    fun normalisationTrimsCollapsesAndStripsPunctuation() {
        assertEquals("satin al", normalise("  Satın   AL!! "))
        assertEquals(BLOCKED, classify("one-time   code").cls)
        assertEquals(NEEDS_APPROVAL, classify("Sign-in: Submit!").cls)
        assertEquals(NEEDS_APPROVAL, classify("(Delete)").cls)
    }

    @Test
    fun diacriticsFoldForTurkishGermanSpanishFrenchAndPolish() {
        // Turkish: ş ö ü ç ğ fold; dotted capital İ and dotless ı fold to i.
        assertEquals("sifre", normalise("ŞİFRE"))
        assertEquals("satin al", normalise("SATIN AL"))
        assertEquals("istanbul", normalise("İSTANBUL"))
        assertEquals("ii", normalise("ıİ"))
        assertEquals(BLOCKED, classify("DOĞRULAMA KODU").cls)
        assertEquals(NEEDS_APPROVAL, classify("GÖNDER").cls)
        // German umlauts and sharp s.
        assertEquals("bestatigungscode", normalise("Bestätigungscode"))
        assertEquals("strasse", normalise("Straße"))
        assertEquals(NEEDS_APPROVAL, classify("Löschen").cls)
        // Spanish.
        assertEquals(BLOCKED, classify("CONTRASEÑA").cls)
        assertEquals(BLOCKED, classify("código").cls)
        // French.
        assertEquals("mot de passe", normalise("Mot de passe"))
        // Polish: ą ć ę ń ó ś ź ż fold under NFD; ł is folded explicitly.
        assertEquals("lodz ae", normalise("Łódź, Ąę"))
        assertEquals("zaplac", normalise("zapłać"))
        assertEquals(BLOCKED, classify("ZAPŁAĆ").cls)
        assertEquals(NEEDS_APPROVAL, classify("WYSŁAĆ").cls)
        assertEquals(NEEDS_APPROVAL, classify("Złóż wniosek").cls)
    }

    @Test
    fun isHumanOnlyIsTrueOnlyForBlocked() {
        assertTrue(isHumanOnly(classify("Password")))
        assertFalse(isHumanOnly(classify("Send")))
        assertFalse(isHumanOnly(classify("Open")))
    }
}
