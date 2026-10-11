package io.github.daddymean.agentickeyboard.twin

import io.github.daddymean.agentickeyboard.util.CloudTextSanitizer
import io.github.daddymean.agentickeyboard.util.SecretTokenPatterns

/**
 * KEYBOARD-024: what may enter the twin store.
 *
 * - Any credential (the #131 secret rules: tokens, private keys, `password=` style
 *   values, bearer tokens) drops the **whole** message. A secret is never stored, even
 *   redacted, so its surrounding words cannot hint at it either.
 * - A message that **names a credential** in plain words ("my password is ...", "the
 *   PIN is ...", "code: 482913", a seed phrase, an OTP) is dropped whole as well
 *   (Codex security review on 43a7b02, r4239700720). The value itself can't be told
 *   apart from ordinary words, so the label decides, and when in doubt the message
 *   is not kept.
 * - **Code- or config-like text** (JSON-ish structures, key=value or key: value lines,
 *   env-style UPPER_SNAKE=..., YAML-ish blocks, URLs with credential query parameters,
 *   code fences) drops the whole message (Keith's security decision, chat 2026-10-11
 *   10:09 PT); see [CodeLikeText]. Rare credential formats beyond the covered patterns
 *   are best effort, an accepted residual risk.
 * - **Identifiers are blanked, best effort** (Keith's security decision, chat
 *   2026-10-11 08:09 PT; Codex reviews on da29f67 and 6b12a27, r4239989012 /
 *   r4240024848): a recognised value after an identifier label (ID, passport, account,
 *   member, booking, reference, policy, ticket, ...), with or without digits, and any
 *   letters-and-digits token of 6+ characters become `[ID]`, and the rest of the message
 *   is still learned. Some identifiers will be missed; that is the accepted residual
 *   risk. Exports are scrubbed again, more strictly, by [TwinExportScrubber].
 * - Everything else the cloud redactor masks (e-mail addresses, card and ID numbers,
 *   SSNs, phone numbers, IP addresses, URLs) is replaced by its marker, so the
 *   value is never stored but the shape of the sentence is.
 * - Overlong text is cut at [MAX_CHARS]; text that is only markers is dropped (emoji-only messages are kept).
 */
object TwinTextFilter {
    const val MAX_CHARS = 4_000
    private const val SECRET_MARKER = "[REDACTED_SECRET]"
    private val markers = Regex("\\[REDACTED_[A-Z_]+]|\\[ID]|\\[NUM]")

    fun prepare(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (SecretTokenPatterns.containsToken(trimmed) ||
            SecretTokenPatterns.privateKeyHeader.containsMatchIn(trimmed)) return null
        val sanitized = CloudTextSanitizer.sanitize(trimmed).text
        if (sanitized.contains(SECRET_MARKER)) return null
        if (CredentialMentions.matches(trimmed)) return null
        if (CodeLikeText.matches(trimmed)) return null
        val blanked = IdentifierBlanker.blank(sanitized)
        if (markers.replace(blanked, "").isBlank()) return null
        return if (blanked.length <= MAX_CHARS) blanked else blanked.take(MAX_CHARS)
    }

    /** True when [text] is only redaction markers and whitespace. */
    internal fun onlyMarkers(text: String): Boolean = markers.replace(text, "").isBlank()
}

/**
 * KEYBOARD-024: plain-language credential mentions. Deliberately broad; a false
 * positive only means one message isn't learned.
 */
internal object CredentialMentions {
    private val labels = Regex(
        "\\b(" +
            "pass(word|wd|code|phrase|key)s?|pwd|pw|" +
            "wi-?fi\\s+(key|code)|" +
            "otp|2fa|mfa|totp|one[- ]time\\s+(code|pass\\w*)|" +
            "(verification|security|auth(entication)?|login|access|recovery|backup|reset|confirmation)\\s+(code|answer|question|key|phrase|pin)s?|" +
            "seed\\s+phrase|recovery\\s+phrase|mnemonic|secret\\s+(key|phrase|code|answer)|private\\s+key|api\\s*key|" +
            "cvv2?|cvc|csc|" +
            "combo|combination|(lock|door|gate|garage|alarm|safe|entry|building|keypad)\\s+code" +
            ")\\b",
        RegexOption.IGNORE_CASE
    )
    /** PIN in capitals anywhere, or "pin" used as a credential. */
    private val pinUpper = Regex("\\bPINs?\\b")
    private val pinLower = Regex(
        "\\b(my|the|your|his|her|our|their|card|bank|debit|atm|phone|sim|door|gate|garage|alarm|new|old)\\s+pin\\b|" +
            "\\bpin\\s*(code|number|no\\.?|#|is|was|=|:)|" +
            // "pin for my card is 1234", "pin to the safe: 4471" (Codex review on 6de6407, r4239778219)
            "\\bpins?\\s+(for|to|of|on|at)\\b",
        RegexOption.IGNORE_CASE
    )
    /** "code is 482913", "code: 4471": a code followed by a value. */
    private val codeValue = Regex("\\bcode\\s*(is|was|=|:)?\\s*[A-Za-z]*\\d{3,}", RegexOption.IGNORE_CASE)
    /** "pin/code ... is 1234" within one clause: a PIN or code with a 3+ digit value nearby. */
    private val nearValue = Regex("\\b(pins?|codes?)\\b[^.!?\\n]{0,40}?\\d{3,}", RegexOption.IGNORE_CASE)
    /** The value first: "1234 is the code", "2580 = my pin" (Codex review on 2cfae3f, r4239826927). */
    private val valueThenLabel = Regex("\\d{3,}[^.!?\\n]{0,40}?\\b(pins?|codes?|passcodes?|passwords?|combo|combination)\\b", RegexOption.IGNORE_CASE)

    /**
     * The broadest rule (Codex reviews on a401124 and 7502783, r4239848795 / r4239941592;
     * Hax decisions 2026-10-10 18:37 PT): **any credential word drops the message, digits
     * or not**, in any case, singular, plural or possessive. Whole words by letters, so
     * "pin1234" matches but "Pinterest" does not. Many harmless messages ("I'll code it
     * tonight", "Pin the post") are not learned; dropping is the safe default. Token, key,
     * secret and credential words were added after the review on 8ff181a (r4242126136), so
     * "grab my keys" is not learned either.
     */
    private val credentialWord = Regex(
        "(?<!\\p{L})(" +
            "code|pin|password|passwd|pwd|pw|passcode|passphrase|passkey|" +
            "combination|combo|otp|2fa|mfa|totp|cvv2?|cvc|csc|" +
            "security\\s+(answer|question)|(seed|recovery|backup)\\s+(phrase|word|code|key)|mnemonic|" +
            "log-?in|logon|sign-?in|username|" +
            // Tokens, keys and secrets (Codex review on 8ff181a, r4242126136): "my access token is ...".
            "token|key|apikey|secret|credential|creds?|bearer|jwt|oauth|auth|cookie|session\\s+id" +
            ")(e?s|['\u2019]s|s['\u2019])?(?!\\p{L})",
        RegexOption.IGNORE_CASE
    )

    /** A word as typed, including joiners: "accessToken", "AWS_SECRET_ACCESS_KEY", "X-Auth-Token". */
    private val rawWord = Regex("[\\p{L}\\p{Nd}_.\\-]+")
    private val joiners = Regex("[_.\\-]+")
    private val caseOrDigitBoundary = Regex(
        "(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{Nd})|(?<=\\p{Nd})(?=\\p{L})"
    )

    /**
     * Credential stems checked as substrings of each lower-cased word, with and without its
     * joiners (Codex review on 6695c82, r4242165268). "pin" is deliberately absent: it only
     * counts as a split token, so "spinning" stays. Substrings also catch harmless words
     * ("author", "seedling", "footprint"); dropping is the safe default.
     */
    private val stems = listOf(
        "passw", "pwd", "passcode", "token", "secret", "apikey", "api_key", "privatekey", "credential", "bearer",
        "jwt", "oauth", "auth", "cookie", "session", "otp", "2fa", "mfa", "cvv", "cvc", "seed", "mnemonic",
        "login", "logon", "signin", "username", "passphrase", "passkey", "totp"
    )

    /** "accessToken" -> "access Token"; "AWS_SECRET_ACCESS_KEY" -> "AWS SECRET ACCESS KEY"; "PIN-code" -> "PIN code". */
    internal fun splitWord(word: String): String =
        caseOrDigitBoundary.replace(joiners.replace(word, " "), " ").trim()

    /** True when any word, split into its parts or as a whole, names a credential. */
    private fun joinedCredential(text: String): Boolean = rawWord.findAll(text).any { m ->
        val word = m.value
        val lower = word.lowercase()
        val bare = joiners.replace(lower, "")
        stems.any { lower.contains(it) || bare.contains(it) } || credentialWord.containsMatchIn(splitWord(word)) ||
            joinedPin(bare.filter { it.isLetter() }) ||
            (splitWord(word).lowercase().split(' ') + bare.filter { it.isLetter() }).any(::joinedTail)
    }

    /**
     * Structural rule (a) (Codex review on 97339e1, r4242257950; Hax decision 2026-10-11
     * 09:18 PT): a word that **ends with a generic credential tail joined to something
     * else** ("securitycode", "accesskey", "recoveryphrase", "secretword", "userpin",
     * "mypass"; a plural s is allowed) is a credential label, unless it is one of the
     * common English words in [TAIL_ALLOWLIST]. "zipcode" is deliberately not exempt.
     */
    private val tails = listOf("code", "key", "phrase", "word", "pin", "answer", "pass")

    /** Kept small on purpose; each entry is pinned by a test. */
    internal val TAIL_ALLOWLIST = setOf(
        "monkey", "turkey", "donkey", "jockey", "hockey", "whiskey", "lowkey",
        "barcode", "encode", "decode", "unicode", "opcode", "postcode",
        "catchphrase", "paraphrase",
        "crossword", "buzzword", "foreword", "afterword", "sword", "byword", "keyword",
        "compass", "bypass", "overpass", "underpass", "trespass", "surpass",
        "spin", "hairpin", "linchpin", "kingpin",
        // Identifiers, not credentials: blanked by IdentifierBlanker instead.
        "passport"
    )

    private fun joinedTail(word: String): Boolean =
        listOf(word, word.removeSuffix("s")).any { w ->
            w !in TAIL_ALLOWLIST && tails.any { t -> w.length > t.length && w.endsWith(t) }
        }

    /**
     * Structural rule (b): an assignment ("label is value", "label=value", "label: value",
     * "label was value", "label -> value") whose label contains a credential stem or tail
     * (outside the allowlist) and whose value is one opaque token of 4+ characters: it has
     * a digit or symbol, mixed case, or is not a common English word.
     */
    private val assignment = Regex(
        // Optional quotes or backticks around the label and the value (Codex review on
        // 5f7fb93, r4242321005): {"pass":"hunter2"}, `pwd`='x'.
        "[\"'`\u201C\u2018]?([\\p{L}\\p{Nd}_.\\-]+)[\"'`\u201D\u2019]?" +
            "(?:\\s+(?i:is|was)\\s+|\\s*(?:=|:|->)\\s*)[\"'`\u201C\u2018]?([^\\s\"'`\u201D\u2019]+)"
    )
    private val commonWords = setOf(
        "about", "after", "again", "all", "also", "always", "back", "because", "been", "being", "best", "better",
        "both", "broken", "busy", "came", "cold", "come", "cool", "could", "cute", "delicious", "different", "done",
        "down", "easy", "even", "every", "fine", "first", "from", "full", "funny", "gone", "good", "great", "hard",
        "have", "here", "home", "hot", "into", "just", "late", "later", "less", "like", "little", "long", "lost",
        "made", "make", "many", "maybe", "more", "most", "much", "must", "never", "next", "nice", "none", "nothing",
        "okay", "only", "open", "over", "perfect", "probably", "ready", "real", "really", "right", "same", "simple",
        "some", "something", "soon", "still", "sure", "that", "then", "there", "these", "they", "this", "today",
        "together", "tomorrow", "tonight", "true", "very", "wait", "want", "well", "were", "what", "when", "where",
        "which", "while", "will", "with", "work", "working", "wrong", "yeah", "yesterday", "your"
    )

    private fun credentialLabel(label: String): Boolean {
        val lower = label.lowercase()
        val letters = lower.filter { it.isLetter() }
        if (letters in TAIL_ALLOWLIST || letters.removeSuffix("s") in TAIL_ALLOWLIST) return false
        return stems.any { lower.contains(it) } || tails.any { letters.contains(it) }
    }

    private fun opaque(value: String): Boolean {
        val v = value.trimEnd('.', ',', '!', '?', ';', ')')
        if (v.length < 4) return false
        val hasDigitOrSymbol = v.any { !it.isLetter() }
        val mixedCase = v.drop(1).any { it.isUpperCase() } && v.any { it.isLowerCase() }
        return hasDigitOrSymbol || mixedCase || v.lowercase() !in commonWords
    }

    private fun credentialAssignment(text: String): Boolean = assignment.findAll(text).any { m ->
        credentialLabel(m.groupValues[1]) && opaque(m.groupValues[2])
    }

    /** Lower-case joined PIN labels ("userpin", "atmpin", "pincode"), without "spin", "hairpin" or "pinch". */
    private val pinWords = setOf("spin", "hairpin", "linchpin", "lynchpin", "tiepin", "pippin", "bobbypin", "safetypin", "kingpin")
    private fun joinedPin(letters: String): Boolean =
        (letters.length > 3 && letters.endsWith("pin") && letters !in pinWords) ||
            letters.startsWith("pincode") || letters.startsWith("pinnumber") || letters.startsWith("pinno")

    fun matches(text: String): Boolean =
        joinedCredential(text) || credentialAssignment(text) ||
        credentialWord.containsMatchIn(text) ||
        labels.containsMatchIn(text) || pinUpper.containsMatchIn(text) ||
            pinLower.containsMatchIn(text) || codeValue.containsMatchIn(text) || nearValue.containsMatchIn(text) ||
            valueThenLabel.containsMatchIn(text)
}

/**
 * KEYBOARD-024: best-effort identifier blanking (Keith's security decision, chat
 * 2026-10-11 08:09 PT; Codex reviews on da29f67 and 6b12a27, r4239989012 / r4240024848).
 * Credentials are never handled here: they drop the whole message in [CredentialMentions].
 *
 * Replaced with [MARKER]:
 * - the value after an identifier label ("member ID is ABCDEF", "booking reference QWERTY",
 *   "passport number AB123456", "ref: qx-7z9p"): a token with a digit, an all-capitals
 *   token, or any token after `:`, `=` or `#`, plus following digit or capital groups
 *   ("QQ 12 34 56 C");
 * - a value named before its label ("445566 is my member number");
 * - any letters-and-digits token of 6+ characters, labeled or not ("AB123456").
 * Lower-case words after a plain label ("my booking is tomorrow") are kept, so a
 * lower-case identifier without `:` can be missed. That is the accepted residual risk;
 * [strict] mode, used by [TwinExportScrubber], blanks any value after a label.
 */
internal object IdentifierBlanker {
    const val MARKER = "[ID]"

    private const val LABELS =
        "social\\s+security|social\\s+insurance|national\\s+insurance|national\\s+id|medical\\s+records?|" +
            "tax\\s+(?:id|number|ref|reference)|green\\s+card|identification|identifiers?|identity|ids?|" +
            "passports?|licen[cs]es?|dl|ssns?|nhs|nino|tin|itin|ein|vat|iban|routing|swift|bic|acct|accounts?|" +
            "membership|members?|polic(?:y|ies)|customers?|patients?|mrn|students?|employees?|staff|badges?|" +
            "serials?|vins?|plates?|registrations?|reg|references?|refs?|bookings?|reservations?|confirmations?|" +
            "tracking|visas?|permits?|claims?|tickets?|orders?|invoices?"
    private const val FILLERS = "ids?|numbers?|nos?\\.?|nr\\.?|refs?|references?"
    private const val TOK = "[A-Za-z0-9](?:[A-Za-z0-9./-]*[A-Za-z0-9])?"

    private val labeled = Regex(
        "(?<![\\p{L}\\p{Nd}])((?i:$LABELS)(?:['\u2019]s)?(?:\\s+(?i:$FILLERS))*)(?!\\p{L})" +
            "(\\s*[:=#]\\s*|\\s+(?i:is|was)\\s+|\\s+)" +
            // The value is never the filler or verb itself ("member ID is", "account number").
            "((?!(?i:is|was|$FILLERS)(?![A-Za-z0-9]))$TOK(?: $TOK){0,6})"
    )
    private val valueFirst = Regex(
        "(?<![\\p{L}\\p{Nd}\\[])($TOK)(\\s+(?i:is|was)\\s+(?i:my|the|your|his|her|our|their)\\s+" +
            "(?:[\\p{L}'-]+\\s+){0,2}(?i:$LABELS)(?!\\p{L}))"
    )
    private val mixedToken = Regex(
        "(?<![\\p{L}\\p{Nd}\\[_])(?=[\\p{L}\\p{Nd}]*\\p{L})(?=[\\p{L}\\p{Nd}]*\\p{Nd})[\\p{L}\\p{Nd}]{6,}(?![\\p{L}\\p{Nd}])"
    )
    private val hasDigit = Regex("\\d")
    private val caps = Regex("[A-Z]{2,}")
    private val shortCaps = Regex("[A-Z]{1,2}")

    fun blank(text: String, strict: Boolean = false): String {
        var out = labeled.replace(text) { m ->
            val (label, sep, value) = m.destructured
            val strongSep = sep.trim().let { it.isNotEmpty() && it[0] in ":=#" }
            val tokens = value.split(' ')
            if (!firstIsValue(tokens[0], strongSep || strict)) return@replace m.value
            var n = 1
            while (n < tokens.size && continuesValue(tokens[n])) n++
            val rest = tokens.drop(n).joinToString(" ")
            label + sep + MARKER + (if (rest.isEmpty()) "" else " $rest")
        }
        out = valueFirst.replace(out) { m ->
            if (firstIsValue(m.groupValues[1], strict)) MARKER + m.groupValues[2] else m.value
        }
        return mixedToken.replace(out, MARKER)
    }

    private fun firstIsValue(t: String, anyCase: Boolean): Boolean =
        t.length >= 2 && (hasDigit.containsMatchIn(t) || caps.matches(t) || anyCase)

    private fun continuesValue(t: String): Boolean =
        hasDigit.containsMatchIn(t) || caps.matches(t) || (shortCaps.matches(t) && t != "I" && t != "A")
}

/**
 * KEYBOARD-024 export-scrub contract (Keith's security decision, chat 2026-10-11 08:09 PT).
 * **Every** twin entry must pass through [scrub] before it leaves the phone in any export
 * (slice 3) or hand-off. It is stricter than capture:
 * - it re-applies everything [TwinTextFilter.prepare] does (secrets and credential words
 *   drop the entry; e-mail addresses, phones, cards, IDs, IPs and URLs are masked);
 * - it blanks **any** value after an identifier label, in any case ([IdentifierBlanker]
 *   strict mode);
 * - it masks any letters-and-digits token of 4+ characters as `[ID]` and any number of
 *   4+ digits (with spaces, dots or hyphens between groups) as `[NUM]`.
 * Returns null when the entry must not be exported at all.
 */
object TwinExportScrubber {
    private const val NUM = "[NUM]"
    private val mixed4 = Regex(
        "(?<![\\p{L}\\p{Nd}\\[_])(?=[\\p{L}\\p{Nd}]*\\p{L})(?=[\\p{L}\\p{Nd}]*\\p{Nd})[\\p{L}\\p{Nd}]{4,}(?![\\p{L}\\p{Nd}])"
    )
    private val longNumber = Regex("(?<![\\p{L}\\p{Nd}])\\d(?:[ .-]?\\d){3,}(?![\\p{L}\\p{Nd}])")

    fun scrub(text: String): String? {
        val prepared = TwinTextFilter.prepare(text) ?: return null
        var out = IdentifierBlanker.blank(prepared, strict = true)
        out = mixed4.replace(out, IdentifierBlanker.MARKER)
        out = longNumber.replace(out, NUM)
        return if (TwinTextFilter.onlyMarkers(out)) null else out
    }
}

/**
 * KEYBOARD-024: code- or config-like text is never learned (Codex review on 5f7fb93,
 * r4242321005; Keith's security decision, chat 2026-10-11 10:09 PT). Such text is where
 * credentials hide in forms the word rules can't see, and it isn't how Keith writes to
 * people, so the whole message is dropped when it has:
 * - a code fence (``` or ~~~);
 * - a JSON-ish structure: `{` or `[` followed by a quoted key and a colon, or a quoted
 *   label assigned with `:` or `=`;
 * - an env-style `UPPER_SNAKE=...` anywhere;
 * - a URL query parameter that names a credential (token=, key=, sig=, password=, ...);
 * - a line that starts with an identifier and `=` ("host=db.local");
 * - a line that starts with a code-shaped identifier (snake, dotted, kebab or camelCase,
 *   or with digits) and `:` ("db.answer: x", "theAnswer: x"). A plain "label: value"
 *   line is left to the credential assignment rule, so "Passport: X1234567" is blanked,
 *   not dropped, while "answer: Tr0ub4dor" is dropped;
 * - two or more YAML-ish `key: value` lines.
 * Ordinary prose like "meet at 5: bring snacks", "Note: bring snacks" or "ratio is 3:1"
 * is kept.
 */
internal object CodeLikeText {
    private val fence = Regex("```|~~~")
    private val jsonKey = Regex("[{\\[]\\s*[\"'`][^\"'`\\n]{1,64}[\"'`]\\s*:")
    private val quotedAssign = Regex("[\"'`][\\p{L}\\p{Nd}_.\\- ]{1,64}[\"'`]\\s*[:=]\\s*[\"'`\\[{\\p{L}\\p{Nd}]")
    private val envVar = Regex("(?<![\\p{L}\\p{Nd}_])[A-Z][A-Z0-9]*_[A-Z0-9_]+\\s*=")
    private val urlCredential = Regex(
        "[?&;][\\w.\\-]*(?i:token|key|sig|signature|pass|pwd|secret|auth|session|code|otp|jwt|cred)[\\w.\\-]*="
    )
    private val lineAssign = Regex("(?m)^\\s*[\\p{L}_][\\p{L}\\p{Nd}_.\\-]*\\s*=\\s*\\S")
    private val lineColon = Regex("(?m)^\\s*([\\p{L}_][\\p{L}\\p{Nd}_.\\-]*)\\s*:[ \\t]*(\\S.*)?$")
    private val codeShaped = Regex("[_.\\-\\d]|\\p{Ll}\\p{Lu}")

    fun matches(text: String): Boolean {
        if (fence.containsMatchIn(text) || jsonKey.containsMatchIn(text) || quotedAssign.containsMatchIn(text) ||
            envVar.containsMatchIn(text) || urlCredential.containsMatchIn(text) || lineAssign.containsMatchIn(text)
        ) return true
        var yamlLines = 0
        for (m in lineColon.findAll(text)) {
            val key = m.groupValues[1]
            val value = m.groupValues[2].trim()
            if (codeShaped.containsMatchIn(key) && value.isNotEmpty()) return true
            if (value.isEmpty() || !value.contains(' ')) yamlLines++
        }
        return yamlLines >= 2
    }
}
