package com.mybrowser.filter

import android.webkit.WebView
import org.json.JSONObject

/**
 * Hiding something in the page that is open, without writing a rule.
 *
 * Both ways of hiding by hand end up here — an image from a long press, an element from the
 * picker — so that what a user sees happen is one mechanism, not two that behave differently.
 *
 * The element is hidden with an inline `!important` declaration rather than a stylesheet rule. A
 * stylesheet rule is what the filter engine itself injects for saved rules, and it loses to any
 * `display` the page sets with its own `!important`; an inline one wins, and it dies with the
 * document, which is exactly the lifetime promised for a temporary hide.
 */
internal object PageHide {

    /** Longest selector this app will hand to the page; the picker's own ceiling is the same. */
    const val MAX_SELECTOR_LENGTH = 512

    /**
     * A selector for the image served from [url], so the same file goes away wherever it appears.
     *
     * The address is quoted as a CSS string, not as JSON or JavaScript: what gets built here is the
     * text of a CSS attribute value, and the escaping is not the same. `JSONObject.quote` writes
     * `\/` for a slash — valid in the other two languages, and valid CSS too, but not what the
     * selector should say. Null when the address holds a character a CSS string cannot carry.
     */
    fun forImage(url: String): String? {
        if (url.isEmpty() || url.length > MAX_SELECTOR_LENGTH) return null
        if (url.any(Char::isISOControl)) return null
        val quoted = url.replace("\\", "\\\\").replace("\"", "\\\"")
        // Matches the address the element is actually displaying, which is not always its `src`.
        // A responsive image declares the loaded candidate in `srcset`, and a lazy loader keeps
        // the real address in a data attribute until the image nears the viewport; in both cases
        // the address a long press reports and the attribute that produced it are different
        // strings. Every form a page can serve the image through is listed rather than only the
        // one this app happens to hold: an image hidden by nothing is the failure this exists to
        // avoid, and each extra form costs one attribute test in one `:is()`.
        val forms = listOf("src", "srcset", "data-src", "data-original", "data-lazy-src")
        val selector = "img:is(" + forms.joinToString(", ") { "[$it*=\"$quoted\"]" } + ")"
        // The ceiling belongs on the text that leaves this function, not on the address that came
        // in: the selector repeats the address once per attribute form, so an address well inside
        // the limit used to build a selector past it, which apply() then refused — a long signed
        // image URL would press "hide" and hide nothing.
        return selector.takeIf { it.length <= MAX_SELECTOR_LENGTH }
    }

    /**
     * Hides every image the document is displaying from [url], and reports how many that was.
     *
     * Attribute text alone is not enough to recognise an image: a long press reports the address the
     * element resolved to, which for `src="/a.png"` is absolute while the attribute is not. Each
     * image is checked twice — against the address it resolves to the same way the browser did
     * (`currentSrc` first, then `src` against the document's base) and against [forImage]'s selector,
     * which covers a lazy loader whose displayed address no current attribute spells — and is counted
     * once.
     *
     * Returns the number hidden, which is zero both when the address names no image and when it names
     * one that is already gone; the caller decides what to say about that.
     */
    fun hideMatchingImage(webView: WebView, url: String, onHidden: ((Int) -> Unit)? = null) {
        webView.evaluateJavascript(
            imageScript(url),
            onHidden?.let { report -> { raw -> report(raw?.toIntOrNull() ?: 0) } },
        )
    }

    /**
     * The script that hides the image at [url], and the only place its matching rules live.
     *
     * Split out so the text can be pinned by a test: it is JavaScript sent to a page, and a bug in
     * it looks exactly like an image the user asked to hide staying on screen.
     */
    internal fun imageScript(url: String): String {
        // A selector that repeats a long address past the ceiling is not usable, but the address
        // itself still identifies the image the browser resolved, which is how a long signed URL
        // gets hidden at all. Only when both are missing is there nothing to match.
        val selector = forImage(url)
        // The braces are the loop's: `continue` skips the hide, and the loop closes after the
        // increment, so `return count` is inside the function. A stray brace here once ended the
        // loop early and left `return` outside it \u2014 a syntax error the page never reports.
        val match = buildString {
            append("{var want=").append(JSONObject.quote(url)).append(",count=0;")
            append("function resolved(img){try{if(img.currentSrc)return img.currentSrc;")
                .append("var s=img.getAttribute('src');return s?new URL(s,document.baseURI).href:''}")
                .append("catch(e){return ''}}")
            append("var nodes=document.images;")
            append("for(var i=0;i<nodes.length;i++){var el=nodes[i];")
            if (selector != null) {
                append("var byAddress=resolved(el)===want;var byAttribute=el.matches(")
                    .append(JSONObject.quote(selector)).append(");")
                append("if(!byAddress&&!byAttribute)continue;")
            } else {
                append("if(resolved(el)!==want)continue;")
            }
            append("el.style.setProperty('display','none','important');count++)}")
            append("return count}")
        }
        return "(function()" + match + ")()"
    }

    /** Hides everything [selector] matches, and reports how many elements that was. */
    fun apply(webView: WebView, selector: String, onHidden: ((Int) -> Unit)? = null) {
        if (!isUsable(selector)) { onHidden?.invoke(0); return }
        webView.evaluateJavascript(
            "(function(){var nodes=document.querySelectorAll(" + JSONObject.quote(selector) + "),count=0;" +
                "for(var i=0;i<nodes.length;i++){nodes[i].style.setProperty('display','none','important');count++}" +
                "return count})()",
            onHidden?.let { report -> { raw -> report(raw?.toIntOrNull() ?: 0) } },
        )
    }

    /**
     * Whether this selector is safe to send as a hidden element's name.
     *
     * Braces are refused because they end the rule this text is being placed in: a selector
     * carrying one would not hide the element that was pointed at, it would write CSS out of the
     * selector's own text. Everything longer than the ceiling is refused for the same reason a rule
     * is — beyond it the text is not a name for an element any more.
     */
    fun isUsable(selector: String): Boolean {
        val text = selector.trim()
        if (text.isEmpty() || text.length > MAX_SELECTOR_LENGTH) return false
        if (text.any { it.isISOControl() }) return false
        return text.none { it == '{' || it == '}' }
    }
}
