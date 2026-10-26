package app.memo.core.policy

import java.text.Normalizer
import java.util.Locale

/**
 * Gate class for one automation label. Ordered by severity: a higher ordinal
 * always wins when a label and its context disagree.
 */
enum class ActionClass { SAFE, NEEDS_APPROVAL, BLOCKED }

/** Why a label was classified. */
enum class PolicyCategory {
    PAYMENT,
    CREDENTIAL,
    ACCOUNT_SECURITY,
    PUBLISH,
    DESTRUCTIVE,
}

/**
 * Result of [classify]. [category] and [term] are null for SAFE results.
 * [term] is the list entry as written (e.g. "satın al"), not the folded form.
 */
data class Classification(
    val cls: ActionClass,
    val category: PolicyCategory? = null,
    val term: String? = null,
)

/** True only for labels no automation may ever act on (human-only). */
fun isHumanOnly(c: Classification): Boolean = c.cls == ActionClass.BLOCKED

// ---------------------------------------------------------------------------
// Normalisation
//
// Matching is on a folded form: lowercase (Locale.ROOT), dotted capital I
// folded to i, NFD with combining marks removed (ą ć ę ń ó ś ź ż ş ğ ö ü ç ...),
// then explicit folds for letters that do not decompose (ı, ł, ß), then every
// run of non-letter/non-digit characters becomes one space. Terms go through
// the same function, so "one-time code" matches "one time code".
// Regexes and the rule tables are declared in dependency order: Kotlin runs
// top-level initialisers in textual order, so each constant must precede its users.
// ---------------------------------------------------------------------------

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
private val WHITESPACE = Regex("\\s+")

internal fun normalise(raw: String): String {
    val lowered = raw.replace('İ', 'i').lowercase(Locale.ROOT)
    val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
    val unmarked = COMBINING_MARKS.replace(decomposed, "")
    val folded = unmarked
        .replace('ı', 'i')
        .replace('ł', 'l')
        .replace("ß", "ss")
    val words = NON_WORD.replace(folded, " ")
    return WHITESPACE.replace(words, " ").trim()
}

// ---------------------------------------------------------------------------
// Matching rules
//
// Whole-word phrase matching on token lists: "delete" does not match
// "deleted", and "sent" does not match "send". The one descriptive exception:
// a CREDENTIAL term followed by a read-only noun ("Password manager settings")
// does not match, so settings and help labels stay SAFE. The tail list is
// not applied to other categories, so "delete settings" still needs approval.
// ---------------------------------------------------------------------------

private val READ_ONLY_CREDENTIAL_TAIL: Set<String> = setOf(
    "manager", "settings", "policy", "policies", "requirements", "requirement",
    "strength", "tips", "rules", "guidelines", "help",
    "ustawienia", "menedzer", "polityka", "wymagania",
    "ayarlar", "ayarları", "politika", "gereksinimler",
    "einstellungen", "richtlinie", "anforderungen", "hilfe",
    "configuracion", "ajustes", "politica", "requisitos",
    "reglages", "politique", "exigences", "aide",
).map { normalise(it) }.toSet()

private class Rule(val cls: ActionClass, val category: PolicyCategory, val terms: List<String>)

private val RULES: List<Rule> = listOf(
    // ---- BLOCKED: PAYMENT ----
    Rule(
        ActionClass.BLOCKED, PolicyCategory.PAYMENT,
        listOf(
            "pay", "payment", "purchase", "buy", "buy now", "checkout", "place order",
            "send money", "transfer money", "withdraw", "cash out", "top up", "add card",
            "add a card", "subscribe to premium", "subscribe and pay", "start subscription",
            "upgrade to premium",
            // Turkish
            "öde", "ödeme", "satın al", "satın alın", "ödemeyi tamamla",
            // German
            "bezahlen", "zahlen", "zahlung", "kaufen", "jetzt kaufen", "zur kasse",
            // Spanish
            "pagar", "pago", "comprar", "comprar ahora", "pagar ahora", "finalizar compra",
            // French
            "payer", "paiement", "acheter", "acheter maintenant", "payer maintenant",
            "passer la commande",
            // Polish
            "zapłać", "zapłacić", "płatność", "kup", "kup teraz", "zamów", "przelew",
            "wypłać", "wypłata",
        ),
    ),
    // ---- BLOCKED: ACCOUNT_SECURITY ----
    Rule(
        ActionClass.BLOCKED, PolicyCategory.ACCOUNT_SECURITY,
        listOf(
            "disable security", "disable 2fa", "turn off security", "factory reset",
            "delete account", "remove account", "change password", "reset password",
            "forgot password", "grant permission", "install unknown",
            // Turkish
            "hesabı sil", "hesabi sil", "hesabı kaldır", "hesabi kaldir",
            "şifreyi değiştir", "sifreyi degistir", "güvenliği devre dışı bırak",
            "guvenligi devre disi birak", "fabrika ayarlarına sıfırla",
            "fabrika ayarlarina sifirla", "izin ver", "bilinmeyen kaynaklar",
            // German
            "konto löschen", "konto loschen", "konto entfernen", "passwort ändern",
            "passwort andern", "sicherheit deaktivieren", "auf werkseinstellungen",
            "werkseinstellungen", "berechtigung erteilen", "unbekannte quellen",
            // Spanish
            "eliminar cuenta", "borrar cuenta", "cambiar contraseña", "cambiar contrasena",
            "restablecer de fábrica", "restablecer de fabrica", "desactivar seguridad",
            "conceder permiso", "fuentes desconocidas",
            // French
            "supprimer le compte", "supprimer mon compte", "changer le mot de passe",
            "réinitialiser aux paramètres d'usine", "reinitialiser aux parametres d usine",
            "désactiver la sécurité", "desactiver la securite", "accorder l'autorisation",
            "sources inconnues",
            // Polish
            "usuń konto", "usun konto", "skasuj konto", "zmień hasło", "zmien haslo",
            "wyłącz zabezpieczenia", "wylacz zabezpieczenia",
            "przywróć ustawienia fabryczne", "przywroc ustawienia fabryczne",
            "nadaj uprawnienia", "nieznane źródła", "nieznane zrodla",
        ),
    ),
    // ---- BLOCKED: CREDENTIAL ----
    Rule(
        ActionClass.BLOCKED, PolicyCategory.CREDENTIAL,
        listOf(
            "password", "passcode", "pin", "security code", "one-time code", "otp", "2fa",
            "verification code", "captcha", "recaptcha", "not a robot", "code", "two factor",
            // Turkish
            "şifre", "sifre", "şifreyi", "sifreyi", "şifrenizi", "sifrenizi", "parola",
            "kod", "kodu", "kodunu", "doğrulama kodu", "dogrulama kodu",
            "güvenlik kodu", "guvenlik kodu", "tek kullanımlık kod", "tek kullanimlik kod",
            // German
            "passwort", "kennwort", "bestätigungscode", "bestatigungscode",
            "verifizierungscode", "sicherheitscode", "einmalcode",
            // Spanish
            "contraseña", "contrasena", "clave de acceso", "código", "codigo",
            "código de verificación", "codigo de verificacion", "código de seguridad",
            "codigo de seguridad",
            // French
            "mot de passe", "mdp", "code de vérification", "code de verification",
            "code de sécurité", "code de securite", "code à usage unique",
            "code a usage unique", "code secret",
            // Polish
            "hasło", "haslo", "kod", "kodu", "kod weryfikacyjny", "kod bezpieczeństwa",
            "kod bezpieczenstwa", "kod jednorazowy",
        ),
    ),
    // ---- NEEDS_APPROVAL: PUBLISH ----
    Rule(
        ActionClass.NEEDS_APPROVAL, PolicyCategory.PUBLISH,
        listOf(
            "post", "publish", "share publicly", "tweet", "send", "submit", "apply", "book",
            "confirm", "accept", "connect", "follow", "invite", "subscribe",
            // Turkish
            "gönder", "gonder", "gönderin", "gonderin", "yayınla", "yayinla", "paylaş",
            "paylas", "başvur", "basvur", "başvuru yap", "basvuru yap", "onayla", "kabul et",
            "rezervasyon yap", "takip et", "davet et", "abone ol",
            // German
            "senden", "absenden", "veröffentlichen", "veroffentlichen", "bewerben",
            "bewerbung absenden", "buchen", "bestätigen", "bestatigen", "folgen",
            "einladen", "posten",
            // Spanish
            "enviar", "publicar", "solicitar", "enviar solicitud", "reservar", "confirmar",
            "aceptar", "seguir", "invitar", "compartir",
            // French
            "envoyer", "publier", "postuler", "soumettre", "envoyer la candidature",
            "réserver", "reserver", "confirmer", "accepter", "suivre", "inviter", "partager",
            // Polish
            "wyślij", "wyslij", "wysłać", "wyslac", "opublikuj", "udostępnij",
            "udostepnij", "aplikuj", "złóż wniosek", "zloz wniosek", "potwierdź",
            "potwierdz", "zarezerwuj",
        ),
    ),
    // ---- NEEDS_APPROVAL: DESTRUCTIVE ----
    Rule(
        ActionClass.NEEDS_APPROVAL, PolicyCategory.DESTRUCTIVE,
        listOf(
            "delete", "remove", "archive all", "unsubscribe all", "clear all", "discard",
            "empty trash",
            // Turkish
            "sil", "silin", "kaldır", "kaldir", "tümünü temizle", "tumunu temizle",
            "tümünü arşivle", "tumunu arsivle", "aboneliği iptal et", "aboneligi iptal et",
            "vazgeç", "vazgec",
            // German
            "löschen", "loschen", "entfernen", "verwerfen", "alle archivieren",
            "alle abbestellen",
            // Spanish
            "eliminar", "borrar", "quitar", "descartar",
            // French
            "supprimer", "effacer", "retirer", "tout effacer",
            // Polish
            "usuń", "usun", "skasuj", "wyczyść wszystko", "wyczysc wszystko",
        ),
    ),
)

/** One rule term, folded and split into tokens, ready for matching. */
private class CompiledTerm(
    val cls: ActionClass,
    val category: PolicyCategory,
    val raw: String,
    val folded: String,
) {
    val tokens: List<String> = folded.split(' ')
}

/** Per class, longest folded term first so the most specific phrase wins. */
private val COMPILED: Map<ActionClass, List<CompiledTerm>> = RULES
    .flatMap { rule ->
        rule.terms.map { term -> CompiledTerm(rule.cls, rule.category, term, normalise(term)) }
    }
    .filter { it.folded.isNotEmpty() }
    .sortedByDescending { it.folded.length }
    .groupBy { it.cls }

private fun termOccurs(tokens: List<String>, term: CompiledTerm): Boolean {
    val n = term.tokens.size
    for (start in 0..tokens.size - n) {
        if (tokens.subList(start, start + n) != term.tokens) continue
        if (term.category == PolicyCategory.CREDENTIAL) {
            val next = tokens.getOrNull(start + n)
            if (next != null && next in READ_ONLY_CREDENTIAL_TAIL) continue
        }
        return true
    }
    return false
}

private fun classifyFolded(folded: String): Classification {
    if (folded.isEmpty()) return Classification(ActionClass.SAFE)
    val tokens = folded.split(' ')
    for (cls in listOf(ActionClass.BLOCKED, ActionClass.NEEDS_APPROVAL)) {
        val hit = COMPILED[cls].orEmpty().firstOrNull { termOccurs(tokens, it) }
        if (hit != null) return Classification(cls, hit.category, hit.raw)
    }
    return Classification(ActionClass.SAFE)
}

/**
 * Classifies an automation label. [context] (field hint, input type, or
 * accessible description) is classified the same way; the more severe of the
 * two results wins, and on a tie the label's result is kept.
 */
fun classify(label: String, context: String = ""): Classification {
    val fromLabel = classifyFolded(normalise(label))
    val fromContext = classifyFolded(normalise(context))
    return if (fromContext.cls.ordinal > fromLabel.cls.ordinal) fromContext else fromLabel
}
