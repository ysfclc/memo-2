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
 * Result of [classify] and [classifyControl]. [category] and [term] are null for
 * SAFE results and for fail-closed defaults that matched no rule.
 * [term] is the list entry as written (e.g. "satın al"), not the folded form.
 */
data class Classification(
    val cls: ActionClass,
    val category: PolicyCategory? = null,
    val term: String? = null,
)

/** True only for labels no automation may ever act on (human-only). */
fun isHumanOnly(c: Classification): Boolean = c.cls == ActionClass.BLOCKED

/** The kind of control the observer found. Drives [classifyControl]. */
enum class ControlRole { BUTTON, LINK, INPUT, SUBMIT, OTHER }

// ---------------------------------------------------------------------------
// Normalisation
//
// Order: NFKC (fullwidth "Ｐａｙ" becomes "Pay"), then strip Unicode format
// characters (category Cf: zero-width space/joiners, word joiner, BOM, soft
// hyphen) WITHOUT inserting a space, then the folding: lowercase (Locale.ROOT),
// dotted capital I folded to i, NFD with combining marks removed (ą ć ę ń ó ś
// ź ż ş ğ ö ü ç ...), explicit folds for letters that do not decompose (ı, ł, ß),
// and finally every run of non-letter/non-digit characters becomes one space.
// Terms go through the same function, so "one-time code" matches "one time code".
// Regexes and the rule tables are declared in dependency order: Kotlin runs
// top-level initialisers in textual order, so each constant must precede its users.
// ---------------------------------------------------------------------------

private val FORMAT_CHARS = Regex("\\p{Cf}+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
private val WHITESPACE = Regex("\\s+")

/** Token that no rule can match. Guard phrases are overwritten with it before matching. */
private const val MASKED: String = "\u0000"

internal fun normalise(raw: String): String {
    val compatible = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    val visible = FORMAT_CHARS.replace(compatible, "")
    val lowered = visible.replace('İ', 'i').lowercase(Locale.ROOT)
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
// Whole-token matching on token lists: "delete" does not match "undelete", and
// "sent" does not match "send". Two extensions:
//
// 1. STEMS. A term written with a trailing `*` matches its LAST token as a
//    prefix: "delet*" matches delete, deleted, deleting, deletes; "payment*"
//    matches payments; "haslo" and "hasl*" both cover the Polish hasło, hasła,
//    hasłem forms. Stems are used only for the high-risk categories (PAYMENT,
//    CREDENTIAL, ACCOUNT_SECURITY, DESTRUCTIVE) and are kept as short as the
//    inflections allow ("sil" is exact, "silin*" and "silme*" are stems) so that
//    unrelated words such as "silver" or "silent" do not match.
//
// 2. GUARDS. Descriptive labels that contain a risky word but are not actions
//    ("Sent items", "Deleted items" headers, "Password manager settings") are
//    listed in GUARD_PHRASES. A guard phrase is matched as whole tokens and
//    masked before the rules run, so only that phrase is excused; the rest of
//    the label is still classified. This is the exception list for the stems.
//
// The one descriptive exception on the rule side: a CREDENTIAL term followed by
// a read-only noun ("Password policy") does not match, so settings and help
// labels stay SAFE. The tail list is not applied to other categories, so
// "delete settings" still needs approval.
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

/**
 * Descriptive phrases excused from matching. Every language's header or
 * manager label for deleted, sent or password-manager items. Keep this list
 * short: each entry weakens the stems for that exact phrase only.
 */
private val GUARD_PHRASES: List<List<String>> = listOf(
    // English
    "Sent items", "Deleted items", "Password manager", "Password manager settings",
    // German
    "Gelöschte Elemente", "Passwort-Manager",
    // Spanish
    "Elementos eliminados", "Administrador de contraseñas",
    // French
    "Éléments supprimés", "Gestionnaire de mots de passe",
    // Polish
    "Usunięte elementy", "Menedżer haseł", "Ustawienia hasła",
    // Turkish
    "Silinmiş öğeler", "Silinenler", "Şifre yöneticisi", "Şifreleme",
).map { normalise(it).split(' ') }

private class Rule(val cls: ActionClass, val category: PolicyCategory, val terms: List<String>)

private val RULES: List<Rule> = listOf(
    // ---- BLOCKED: PAYMENT ----
    Rule(
        ActionClass.BLOCKED, PolicyCategory.PAYMENT,
        listOf(
            // English
            "pay", "pays", "paying", "payment*", "purchas*", "buy", "buys", "buying", "buy now",
            "checkout*", "check out", "place order", "place your order", "order now",
            "complete purchase", "send money", "transfer money", "withdraw*", "cash out",
            "top up", "add card", "add a card", "add payment method", "subscribe to premium",
            "subscribe and pay", "start subscription", "upgrade to premium", "paypal",
            "continue with paypal", "google pay", "gpay", "apple pay",
            // Turkish
            "öde", "ode", "ödeme*", "satın al", "satın alın", "satın almayı tamamla",
            "ödemeyi tamamla", "sipariş ver", "siparişi ver", "şimdi öde", "ödeme yöntemi ekle",
            "paypal ile devam et",
            // German
            "bezahl*", "zahlen", "zahlung*", "kaufen*", "jetzt kaufen", "zur kasse",
            "jetzt bestellen", "bestellung aufgeben", "kauf abschließen",
            "mit paypal fortfahren", "zahlungsmethode hinzufügen",
            // Spanish
            "pagar*", "pago*", "comprar*", "comprar ahora", "pagar ahora", "finalizar compra",
            "completar compra", "realizar pedido", "hacer pedido", "pedir ahora",
            "continuar con paypal", "añadir método de pago",
            // French
            "payer*", "paiement*", "acheter*", "achat*", "acheter maintenant", "payer maintenant",
            "passer la commande", "commander maintenant", "finaliser l achat",
            "continuer avec paypal", "ajouter un moyen de paiement", "ajouter un mode de paiement",
            // Polish
            "zaplac*", "zaplat*", "platnosc*", "kup", "kup teraz", "zamow", "zamow teraz",
            "złóż zamówienie", "przelew*", "wyplac*", "wyplat*", "zakończ zakup",
            "kontynuuj z paypal", "dodaj metodę płatności",
        ),
    ),
    // ---- BLOCKED: ACCOUNT_SECURITY ----
    Rule(
        ActionClass.BLOCKED, PolicyCategory.ACCOUNT_SECURITY,
        listOf(
            // English
            "disable security", "disable 2fa", "turn off security", "factory reset",
            "delete account", "delete your account", "remove account", "close account",
            "close your account", "deactivate account", "deactivate your account",
            "change password", "reset password", "forgot password", "grant permission",
            "install unknown",
            // Turkish
            "hesabı sil", "hesabi sil", "hesabı kaldır", "hesabi kaldir", "hesabı kapat",
            "hesabi kapat", "şifreyi değiştir", "sifreyi degistir",
            "güvenliği devre dışı bırak", "guvenligi devre disi birak",
            "fabrika ayarlarına sıfırla", "fabrika ayarlarina sifirla", "izin ver",
            "bilinmeyen kaynaklar",
            // German
            "konto löschen", "konto loschen", "konto entfernen", "konto deaktivieren",
            "konto schließen", "passwort ändern", "passwort andern", "sicherheit deaktivieren",
            "auf werkseinstellungen", "werkseinstellungen", "berechtigung erteilen",
            "unbekannte quellen",
            // Spanish
            "eliminar cuenta", "borrar cuenta", "cambiar contraseña", "cambiar contrasena",
            "restablecer de fábrica", "restablecer de fabrica", "desactivar seguridad",
            "desactivar cuenta", "cerrar cuenta", "conceder permiso", "fuentes desconocidas",
            // French
            "supprimer le compte", "supprimer mon compte", "changer le mot de passe",
            "réinitialiser aux paramètres d'usine", "reinitialiser aux parametres d usine",
            "désactiver la sécurité", "desactiver la securite", "accorder l'autorisation",
            "sources inconnues", "desactiver le compte", "fermer le compte",
            // Polish
            "usuń konto", "usun konto", "skasuj konto", "zmień hasło", "zmien haslo",
            "wyłącz zabezpieczenia", "wylacz zabezpieczenia",
            "przywróć ustawienia fabryczne", "przywroc ustawienia fabryczne",
            "nadaj uprawnienia", "nieznane źródła", "nieznane zrodla",
            "dezaktywuj konto", "zamknij konto",
        ),
    ),
    // ---- BLOCKED: CREDENTIAL ----
    Rule(
        ActionClass.BLOCKED, PolicyCategory.CREDENTIAL,
        listOf(
            // English
            "password*", "passcode*", "pin", "security code*", "one-time code*", "otp", "2fa",
            "verification code*", "captcha", "recaptcha", "not a robot", "code", "two factor",
            // Turkish
            "şifre*", "parola*", "kod", "kodu", "kodunu", "doğrulama kodu*",
            "güvenlik kodu*", "tek kullanımlık kod*",
            // German
            "passwort*", "kennwort*", "bestätigungscode*", "verifizierungscode*",
            "sicherheitscode*", "einmalcode*",
            // Spanish
            "contraseña*", "clave de acceso", "código de verificación*",
            "código de seguridad*", "código", "codigo",
            // French
            "mot de passe*", "mots de passe", "mdp", "code de vérification*",
            "code de sécurité*", "code à usage unique*", "code secret*",
            // Polish
            "hasl*", "hasel*", "kod", "kodu", "kod weryfikacyjny*", "kod bezpieczeństwa*",
            "kod jednorazowy*",
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
            // English
            "delet*", "remov*", "archive all", "unsubscribe all", "clear all", "discard*",
            "empty trash", "erase*", "wipe", "wiped", "wipes", "wiping", "uninstall*",
            "terminat*", "unlink*", "cancel subscription", "log out", "logout", "sign out",
            "signout",
            // Turkish
            "sil", "silin*", "silme*", "kaldır*", "tümünü temizle", "tumunu temizle",
            "tümünü arşivle", "tumunu arsivle", "aboneliği iptal et", "aboneligi iptal et",
            "vazgeç", "vazgec", "çıkış yap", "cikis yap", "oturumu kapat",
            // German
            "losch*", "geloscht*", "entfern*", "verwerf*", "alle archivieren",
            "alle abbestellen", "deinstallier*", "abmelden", "ausloggen",
            // Spanish
            "elimin*", "borrar*", "borrado", "borrada", "borrados", "quitar*", "descartar*",
            "desinstalar*", "cerrar sesion",
            // French
            "supprim*", "effac*", "retir*", "tout effacer", "desinstaller*",
            "se deconnecter",
            // Polish
            "usun*", "skasuj*", "skasow*", "wyczysc wszystko", "odinstaluj*", "wyloguj*",
        ),
    ),
)

/**
 * Sensitive-input terms for [classifyControl] on INPUT controls. Matched on the
 * label and the context without the CREDENTIAL tail exception: a password field
 * is human-only even when its hint reads "Password policy".
 */
private val SENSITIVE_INPUT_RULES: List<Rule> = listOf(
    Rule(
        ActionClass.BLOCKED, PolicyCategory.PAYMENT,
        listOf(
            "card", "cards", "cardholder*", "cvv", "cvc", "iban",
            "kart", "kredi karti", "kreditkarte*", "kartennummer*",
            "tarjeta*", "carte", "cartes", "karta", "karty",
        ),
    ),
    Rule(
        ActionClass.BLOCKED, PolicyCategory.CREDENTIAL,
        listOf("password*", "passcode*", "otp"),
    ),
)

/** One rule term, folded and split into tokens, ready for matching. */
private class CompiledTerm(
    val cls: ActionClass,
    val category: PolicyCategory,
    val raw: String,
    val folded: String,
    /** True when the last token is a stem: it matches any token that starts with it. */
    val prefix: Boolean,
) {
    val tokens: List<String> = folded.split(' ')
}

private fun compileTerm(cls: ActionClass, category: PolicyCategory, written: String): CompiledTerm {
    val prefix = written.endsWith('*')
    val text = written.removeSuffix("*")
    return CompiledTerm(cls, category, text, normalise(text), prefix)
}

private fun compileRules(rules: List<Rule>): List<CompiledTerm> = rules
    .flatMap { rule -> rule.terms.map { compileTerm(rule.cls, rule.category, it) } }
    .filter { it.folded.isNotEmpty() }

/** Per class, longest folded term first so the most specific phrase wins. */
private val COMPILED: Map<ActionClass, List<CompiledTerm>> = compileRules(RULES)
    .sortedByDescending { it.folded.length }
    .groupBy { it.cls }

private val SENSITIVE_INPUT: List<CompiledTerm> = compileRules(SENSITIVE_INPUT_RULES)

private val FORM_CONTEXT_TOKENS: Set<String> = setOf(
    "form", "forms", "formular", "formulaire", "formulario", "formularz",
)

/** Replaces every occurrence of a guard phrase with [MASKED] so no rule can match inside it. */
private fun maskGuards(tokens: List<String>): List<String> {
    val out = tokens.toMutableList()
    for (guard in GUARD_PHRASES) {
        var start = 0
        while (start + guard.size <= out.size) {
            if (out.subList(start, start + guard.size) == guard) {
                for (k in guard.indices) out[start + k] = MASKED
                start += guard.size
            } else {
                start++
            }
        }
    }
    return out
}

/** True when [term] matches the tokens starting at [start]; the last token of a stem matches as a prefix. */
private fun termAt(tokens: List<String>, start: Int, term: CompiledTerm): Boolean {
    val last = term.tokens.size - 1
    for (k in 0..last) {
        val got = tokens[start + k]
        val want = term.tokens[k]
        val same = if (term.prefix && k == last) got.startsWith(want) else got == want
        if (!same) return false
    }
    return true
}

private fun termOccurs(tokens: List<String>, term: CompiledTerm): Boolean {
    val n = term.tokens.size
    for (start in 0..tokens.size - n) {
        if (!termAt(tokens, start, term)) continue
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
    val tokens = maskGuards(folded.split(' '))
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

/**
 * Classifies a control by its role as well as its label. Fails closed where a
 * wrong guess would be costly:
 *
 * - SUBMIT, and a BUTTON whose [context] says it sits in a form, whose label is
 *   unrecognised or empty is NEEDS_APPROVAL, not SAFE.
 * - INPUT whose label or context mentions a card, CVV, IBAN, password or OTP is
 *   BLOCKED. These fields are never typed by automation.
 * - Every other case is the same as [classify].
 *
 * A label already BLOCKED by [classify] stays BLOCKED for every role.
 */
fun classifyControl(label: String, role: ControlRole, context: String = ""): Classification {
    val base = classify(label, context)
    if (base.cls == ActionClass.BLOCKED) return base
    return when (role) {
        ControlRole.INPUT -> sensitiveInput(label, context) ?: base
        ControlRole.SUBMIT -> failClosed(base)
        ControlRole.BUTTON -> if (inForm(context)) failClosed(base) else base
        ControlRole.LINK, ControlRole.OTHER -> base
    }
}

/** An unrecognised (SAFE) label on a submit control is treated as a commit: approval needed. */
private fun failClosed(base: Classification): Classification =
    if (base.cls == ActionClass.SAFE) Classification(ActionClass.NEEDS_APPROVAL) else base

private fun inForm(context: String): Boolean =
    normalise(context).split(' ').any { it in FORM_CONTEXT_TOKENS }

private fun sensitiveInput(label: String, context: String): Classification? {
    for (text in listOf(label, context)) {
        val tokens = maskGuards(normalise(text).split(' '))
        for (term in SENSITIVE_INPUT) {
            val n = term.tokens.size
            for (start in 0..tokens.size - n) {
                if (termAt(tokens, start, term)) {
                    return Classification(ActionClass.BLOCKED, term.category, term.raw)
                }
            }
        }
    }
    return null
}
