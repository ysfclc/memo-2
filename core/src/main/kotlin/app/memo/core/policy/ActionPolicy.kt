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

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
private val WHITESPACE = Regex("\\s+")

/** ASCII control characters that separate words; every other Cc character is removed like Cf. */
private val ASCII_SPACE_CONTROLS: Set<Int> = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D)

/**
 * Default_Ignorable_Code_Point characters that are not category Cf: the Hangul
 * fillers (Lo, so \p{L} would keep them), the combining grapheme joiner, Khmer
 * vowel inherents, the Mongolian and general variation selectors, and the
 * invisible operators. A hostile label can hide a word behind them.
 */
private val IGNORABLE_CODE_POINTS: Set<Int> = setOf(
    0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x2065, 0x3164, 0xFFA0,
)
private val IGNORABLE_RANGES: List<IntRange> = listOf(
    0x180B..0x180F, 0xFE00..0xFE0F, 0xFFF0..0xFFF8, 0xE0000..0xE0FFF,
)

/** Removed without inserting a space: "pa\u0001y" and "Pa\u3164y" both read as "pay". */
private fun isIgnorable(cp: Int): Boolean {
    if (cp in IGNORABLE_CODE_POINTS || IGNORABLE_RANGES.any { cp in it }) return true
    val type = Character.getType(cp)
    if (type == Character.FORMAT.toInt()) return true
    return type == Character.CONTROL.toInt() && cp !in ASCII_SPACE_CONTROLS
}

private fun stripIgnorable(text: String): String {
    val out = StringBuilder(text.length)
    text.codePoints().forEach { if (!isIgnorable(it)) out.appendCodePoint(it) }
    return out.toString()
}

/**
 * Lowercase look-alikes from Cyrillic and Greek, mapped to the Latin letter they
 * imitate. Applied only to a label that already mixes Latin with one of those
 * scripts inside a token (see [classifyText]).
 */
private val CONFUSABLES: Map<Int, Char> = mapOf(
    // Cyrillic
    0x0430 to 'a', 0x0435 to 'e', 0x043E to 'o', 0x0440 to 'p', 0x0441 to 'c', 0x0443 to 'y',
    0x0445 to 'x', 0x0456 to 'i', 0x0458 to 'j', 0x0455 to 's', 0x0501 to 'd',
    // Greek
    0x03BF to 'o', 0x03B1 to 'a', 0x03C1 to 'p', 0x03BD to 'v', 0x03B9 to 'i', 0x03BA to 'k',
    0x03C4 to 't', 0x03C5 to 'u', 0x03C7 to 'x', 0x03B5 to 'e',
)

/** Token that no rule can match. Guard phrases are overwritten with it before matching. */
private const val MASKED: String = "\u0000"

internal fun normalise(raw: String): String {
    val compatible = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    val visible = stripIgnorable(compatible)
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
            "donate", "donation", "tip", "place bid", "place a bid", "bid now", "redeem*",
            "start free trial", "confirm order",
            // Turkish
            "öde", "ode", "ödeme*", "satın al", "satın alın", "satın almayı tamamla",
            "ödemeyi tamamla", "sipariş ver", "siparişi ver", "şimdi öde", "ödeme yöntemi ekle",
            "paypal ile devam et",
            "bağış yap", "bagis yap", "bağış", "bahşiş", "bahsis", "teklif ver", "şimdi teklif ver",
            "siparişi onayla", "ücretsiz denemeyi başlat", "ucretsiz denemeyi baslat",
            // German
            "bezahl*", "zahlen", "zahlung*", "kaufen*", "jetzt kaufen", "zur kasse",
            "jetzt bestellen", "bestellung aufgeben", "kauf abschließen",
            "mit paypal fortfahren", "zahlungsmethode hinzufügen",
            "spenden*", "trinkgeld", "gebot abgeben", "jetzt bieten", "einlos*",
            "kostenlose testversion starten", "bestellung bestätigen",
            // Spanish
            "pagar*", "pago*", "comprar*", "comprar ahora", "pagar ahora", "finalizar compra",
            "completar compra", "realizar pedido", "hacer pedido", "pedir ahora",
            "continuar con paypal", "añadir método de pago",
            "donar", "propina", "pujar", "pujar ahora", "canjear*", "empezar prueba gratuita",
            "confirmar pedido",
            // French
            "payer*", "paiement*", "acheter*", "achat*", "acheter maintenant", "payer maintenant",
            "passer la commande", "commander maintenant", "finaliser l achat",
            "continuer avec paypal", "ajouter un moyen de paiement", "ajouter un mode de paiement",
            "faire un don", "pourboire", "enchérir", "enchérir maintenant", "placer une enchère",
            "commencer l'essai gratuit", "confirmer la commande", "confirmer commande",
            // Polish
            "zaplac*", "zaplat*", "platnosc*", "kup", "kup teraz", "zamow", "zamow teraz",
            "złóż zamówienie", "przelew*", "wyplac*", "wyplat*", "zakończ zakup",
            "kontynuuj z paypal", "dodaj metodę płatności",
            "wesprzyj", "darowizn*", "napiwek", "licytuj*", "licytuj teraz", "zrealizuj*",
            "potwierdź zamówienie",
        ),
    ),
    // ---- NEEDS_APPROVAL: PAYMENT (upsell and trial entry points) ----
    Rule(
        ActionClass.NEEDS_APPROVAL, PolicyCategory.PAYMENT,
        listOf(
            "upgrade", "try free", "get premium",
            "ücretsiz dene", "ucretsiz dene", "premium al", "yükselt*", "yukselt*",
            "kostenlos testen", "premium holen",
            "probar gratis", "obtener premium",
            "essayer gratuitement", "obtenir premium",
            "ulepsz*", "wypróbuj za darmo", "wyprobuj za darmo",
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
            "share", "reply", "comment", "like", "repost", "retweet", "send message", "message",
            // Turkish
            "gönder", "gonder", "gönderin", "gonderin", "yayınla", "yayinla", "paylaş",
            "paylas", "başvur", "basvur", "başvuru yap", "basvuru yap", "onayla", "kabul et",
            "rezervasyon yap", "takip et", "davet et", "abone ol",
            "yanıtla", "yanitla", "yorum yap", "beğen*", "begen*", "mesaj gönder", "mesaj gonder", "mesaj",
            // German
            "senden", "absenden", "veröffentlichen", "veroffentlichen", "bewerben",
            "bewerbung absenden", "buchen", "bestätigen", "bestatigen", "folgen",
            "einladen", "posten",
            "antworten", "kommentieren*", "gefällt mir", "gefallt mir", "teilen", "retweeten",
            "nachricht senden", "nachricht",
            // Spanish
            "enviar", "publicar", "solicitar", "enviar solicitud", "reservar", "confirmar",
            "aceptar", "seguir", "invitar", "compartir",
            "responder", "comentar*", "me gusta", "republicar", "retuitear*", "enviar mensaje", "mensaje",
            // French
            "envoyer", "publier", "postuler", "soumettre", "envoyer la candidature",
            "réserver", "reserver", "confirmer", "accepter", "suivre", "inviter", "partager",
            "répondre", "repondre", "commenter*", "j'aime", "republier", "retweeter",
            "envoyer un message", "message",
            // Polish
            "wyślij", "wyslij", "wysłać", "wyslac", "opublikuj", "udostępnij",
            "udostepnij", "aplikuj", "złóż wniosek", "zloz wniosek", "potwierdź",
            "potwierdz", "zarezerwuj",
            "odpowiedz*", "skomentuj*", "polub*", "lubię to", "lubie to", "retweetuj*",
            "wyślij wiadomość", "wyslij wiadomosc", "wiadomość", "wiadomosc",
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
    val fromLabel = classifyText(label)
    val fromContext = classifyText(context)
    return if (fromContext.cls.ordinal > fromLabel.cls.ordinal) fromContext else fromLabel
}

/**
 * Classifies one text. A token that mixes Latin letters with Cyrillic or Greek
 * letters is a likely spoof ("Рay", "Ԁelete"): the text is also classified with
 * the look-alikes folded to Latin, the more severe result wins, and a spoof that
 * matches nothing is still NEEDS_APPROVAL.
 */
private fun classifyText(raw: String): Classification {
    val folded = normalise(raw)
    val direct = classifyFolded(folded)
    if (!hasMixedScriptToken(folded)) return direct
    val spoofed = classifyFolded(foldConfusables(folded))
    val worst = if (spoofed.cls.ordinal > direct.cls.ordinal) spoofed else direct
    return if (worst.cls == ActionClass.SAFE) Classification(ActionClass.NEEDS_APPROVAL) else worst
}

/** True when some token holds both a Latin letter and a Cyrillic or Greek letter. */
private fun hasMixedScriptToken(folded: String): Boolean = folded.split(' ').any { token ->
    var latin = false
    var other = false
    token.codePoints().forEach { cp ->
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN -> latin = true
            Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK -> other = true
            else -> Unit
        }
    }
    latin && other
}

private fun foldConfusables(folded: String): String {
    val out = StringBuilder(folded.length)
    folded.codePoints().forEach { cp ->
        val latin = CONFUSABLES[cp]
        if (latin != null) out.append(latin) else out.appendCodePoint(cp)
    }
    return out.toString()
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
    for (raw in listOf(label, context)) {
        val folded = normalise(raw)
        val spellings = if (hasMixedScriptToken(folded)) listOf(folded, foldConfusables(folded)) else listOf(folded)
        for (text in spellings) {
            val tokens = maskGuards(text.split(' '))
            for (term in SENSITIVE_INPUT) {
                val n = term.tokens.size
                for (start in 0..tokens.size - n) {
                    if (termAt(tokens, start, term)) {
                        return Classification(ActionClass.BLOCKED, term.category, term.raw)
                    }
                }
            }
        }
    }
    return null
}
