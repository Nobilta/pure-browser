package com.mybrowser.filter

import com.mybrowser.core.UrlUtils
import java.net.URI
import java.util.Locale

/**
 * Turns what a user pointed at into a filter rule.
 *
 * Kept apart from the stores and the screen so the text this app writes can be tested as text. That
 * matters because the engine drops a rule it cannot parse without a word: an unparseable rule and
 * no rule at all look identical from the outside, and the user has no way to tell which one they
 * have. Every function here either returns a rule the engine accepts or returns null so the caller
 * can say why.
 */
internal object BlockRules {

    /** Longest rule this app writes; the same ceiling [FilterListFormat] counts as one rule. */
    const val MAX_RULE_LENGTH = 8_192

    /**
     * Whether a cosmetic selector may be hidden by a saved element rule.
     *
     * The picker writes descendant and child combinators (`A > B`), so the check cannot be about
     * the selector's shape; it is about whether the engine would keep it. A selector the native
     * cosmetic parser drops leaves a rule that claims to hide something and hides nothing.
     */
    fun isUsableElementSelector(selector: String): Boolean = isUsableSelector(selector.trim())

    /**
     * The exact address, without its query or fragment.
     *
     * Dropping the query is what makes the rule survive: ad URLs are often the same path with a
     * fresh cache-busting parameter every load, and a rule carrying the first one would match once
     * and never again.
     *
     * Null when there is nothing worth blocking precisely — a URL with no path is the whole host,
     * and the caller offers that as its own choice rather than writing a rule that would read as
     * "block everything on this site". A URL carrying EasyList's own meta-characters is also
     * refused, because `*`, `^` and `|` change what the pattern means and this app has no way to
     * escape them.
     */
    fun addressRule(url: String): String? {
        val parsed = parse(url) ?: return null
        val path = parsed.rawPath.orEmpty()
        if (path.isEmpty() || path == "/") return null
        val address = buildString {
            append(parsed.scheme.lowercase(Locale.ROOT))
            append("://")
            parsed.rawAuthority?.let(::append)
            append(path)
        }
        return address.takeIf { it.length <= MAX_RULE_LENGTH && isPlainPattern(it) }
    }

    /**
     * Every request to that host, as `||host^`.
     *
     * The `||` anchor matches the host and any subdomain below it, so a rule written for
     * `example.com` also covers `cdn.example.com` — which is usually the point: the same ad host
     * serves the same slot from several names.
     */
    fun hostRule(url: String): String? {
        // Validated the same way a cosmetic scope is, because "https://" reads as a host of nothing
        // and would otherwise write the rule `||^`, which is not a rule at all.
        val host = hostOf(url)?.let(::siteHost) ?: return null
        return "||$host^"
    }

    /**
     * Hides what [selector] matches, on one site or on every site.
     *
     * [allSites] writes the generic form, which the engine applies everywhere; otherwise the rule is
     * scoped to [host] and — because the engine matches a domain and everything below it — to that
     * site's subdomains as well.
     */
    fun elementRule(host: String?, selector: String?, allSites: Boolean): String? {
        val element = selector?.trim()?.takeIf(::isUsableSelector) ?: return null
        if (allSites) return "##$element"
        val site = host?.let(::siteHost) ?: return null
        return "$site##$element"
    }

    /**
     * Whether a rule this app wrote can be handed to the engine as-is.
     *
     * The test is deliberately the same one [FilterListFormat.validate] uses to count a rule, so a
     * rule this accepts can never be the line that makes a payload uncountable — which is what
     * raises "No filter rules" and leaves the engine holding the previous snapshot.
     */
    fun isValid(rule: String): Boolean {
        val line = rule.trim()
        // The count [FilterListFormat.validate] performs, mirrored statement for statement: a line
        // it does not count would make the payload it travels in uncountable, which raises "No
        // filter rules" and leaves the engine on its previous snapshot. Control characters (a
        // newline above all) would also end the rule and start another one, so they are refused
        // for every kind.
        if (line.isEmpty() || line.length > MAX_RULE_LENGTH || line.any { it.isISOControl() }) {
            return false
        }
        if (line.contains("##") || line.contains("#@#")) {
            val (site, element) = line.split("##", "#@#", limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            if (site.isNotEmpty() && siteHost(site) == null) return false
            // A cosmetic selector legitimately contains whitespace: it is the descendant
            // combinator, and the picker's path fallback (`A > B > C`) always produces one.
            return isUsableSelector(element)
        }
        // A plain host or address pattern. Whitespace is not meaningful in one, and braces and
        // parentheses are not part of one, so a rule carrying either is a kind this engine does
        // not implement and must not be stored. A `$` is refused for the same reason: the engine
        // reads it and everything after it as options, so a path that merely contains one comes back
        // from the parser as `Unsupported` and the rule would sit in the list doing nothing.
        if (line.any { it.isWhitespace() }) return false
        if (line.any { it == '{' || it == '}' || it == '(' || it == ')' }) return false
        if (line.contains('$')) return false
        return line.contains('.') || line.contains('/') || line.contains('|')
    }

    /** The host a rule or a cosmetic scope would name, or null when there is not one. */
    fun hostOf(url: String): String? = UrlUtils.hostOf(url)

    private fun parse(url: String): URI? {
        if (url.length > MAX_RULE_LENGTH) return null
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return null
        if (parsed.host.isNullOrBlank()) return null
        return parsed
    }

    /**
     * A rule is a plain substring pattern only while it holds none of EasyList's meta-characters.
     *
     * `$` is one of them here even though it is a legal character in a path: the engine splits a
     * rule at its last `$` and reads what follows as options, so `.../banner$1.png` becomes options
     * `1.png` and the whole rule is dropped as unsupported. Writing it would show a rule in the
     * user's list that blocks nothing.
     */
    private fun isPlainPattern(pattern: String): Boolean =
        pattern.none { it == '*' || it == '^' || it == '|' || it == '$' }

    private fun siteHost(host: String): String? {
        val site = host.trim().lowercase(Locale.ROOT)
        if (site.isEmpty() || site.length > 253) return null
        if (site.none { it == '.' }) return null
        if (site.startsWith('.') || site.endsWith('.')) return null
        // The engine refuses a domain pattern carrying its own syntax, so these are refused here
        // too rather than written and silently dropped.
        if (site.any { it == '*' || it == '^' || it == '|' || it == ',' || it == '~' || it == ' ' }) return null
        return site
    }

    /**
     * Whether the engine will keep this selector.
     *
     * Two kinds of otherwise plausible selector are refused because the cosmetic parser drops them
     * wholesale: a style declaration (`##.ad{display:none}`, which is a different rule kind this
     * engine does not implement) and the scripted forms (`##+js(...)`, `:has-text(...)`).
     *
     * A selector naming the document itself is refused too. `##body` hides the whole page, and a
     * blank page does not read as a rule the user chose — it reads as the filter having broken the
     * site, which is a thing they would have to find in a list of text, on a site they can no longer
     * see, in order to undo.
     */
    private fun isUsableSelector(selector: String): Boolean {
        if (selector.isEmpty() || selector.length > MAX_RULE_LENGTH) return false
        if (selector.count() > MAX_SELECTOR_CHARS) return false
        // A marker inside the selector would end the rule early and let whatever follows it be read
        // as another rule: `example.com##a##body` reaches the engine as a rule for `a` plus one
        // for the whole document. Nothing a picker produces contains one.
        if (selector.contains("##") || selector.contains("#@#")) return false
        if (selector.any { it.isISOControl() || it == '{' || it == '}' || it == '@' }) return false
        // Mirrors the cosmetic parser's own unsupported list (rust/adblock/src/cosmetic.rs): a
        // selector it would drop is a rule that looks stored and blocks nothing, which is worse
        // than refusing it.
        if (UNSUPPORTED_SELECTOR_FORMS.any(selector::contains)) return false
        return !targetsDocument(selector)
    }

    /** The forms the cosmetic parser skips wholesale; kept in step with the native engine. */
    private val UNSUPPORTED_SELECTOR_FORMS = listOf(
        "+js(", ":has-text(", ":matches-css", ":xpath(", ":remove(", ":style(", ":-abp-",
    )

    /** The selector length the engine counts in characters, in addition to [MAX_RULE_LENGTH]. */
    private const val MAX_SELECTOR_CHARS = 2_048

    /** The part of the document a page cannot be left without, whatever a selector asks for. */
    private val DOCUMENT_TAGS = setOf(
        "html", "body", "head", "script", "style", "link", "meta", "title", "base", "noscript", "template",
    )

    /**
     * Selectors that name the document itself rather than an element inside it.
     *
     * `*` and `:root` are the same outcome as `body` written another way, and a picker never needs
     * them: an element the user pointed at has a tag, an id or a class. They matter here because the
     * picker's channel is visible to the page, and a page that posts `*` while a pick is running
     * would otherwise have it accepted — the one string that hides everything, on every site if the
     * user then picks that scope. A universal selector reached through a combinator (`* > div`) is
     * refused for the same reason: its first step is still the whole document.
     */
    private val DOCUMENT_SELECTORS = listOf("*", ":root", ":scope")

    /**
     * Whether the selector's first compound names the document or everything in it.
     *
     * Only the first: `body > div.ad` hides one child of the body and is a legitimate rule, while a
     * selector that *starts* at the body begins by hiding everything. The comparison allows a longer
     * name to contain one of these — `bodywork.ad` is a different element — so it reads the whole
     * first word rather than searching the text.
     */
    private fun targetsDocument(selector: String): Boolean {
        val first = selector.trimStart().trimStart('>', '+', '~').trimStart()
        // The universal and root forms name no element: `*`, `* > div`, `:root div`, `:root>div`.
        if (DOCUMENT_SELECTORS.any { first.startsWith(it) }) return true
        val tag = first.takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase(Locale.ROOT)
        return tag in DOCUMENT_TAGS
    }
}
