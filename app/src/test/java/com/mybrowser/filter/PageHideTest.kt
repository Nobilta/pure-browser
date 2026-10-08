package com.mybrowser.filter

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The selectors this app sends to the page to hide something now.
 *
 * A selector is handed to `querySelectorAll`, which throws on text that is not a selector and
 * matches nothing when it is a selector for the wrong thing. Neither failure is visible — the user
 * pressed a button and the element stayed — so the text is pinned down here, quoting included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageHideTest {

    @Test
    fun anImageIsSelectedByTheAddressItIsServedThrough() {
        // Every form a page can serve the same image through is matched, not only the `src` this
        // app happens to be holding: a responsive image declares its candidate in `srcset`, and a
        // lazy loader keeps the real address in a data attribute. Hiding nothing while reporting a
        // hide is the failure this pins down.
        val selector = PageHide.forImage("https://cdn.example.com/a/300x250.png").orEmpty()
        assertTrue(selector, selector.startsWith("img:is([src*="))
        for (attribute in listOf("src", "srcset", "data-src", "data-original", "data-lazy-src")) {
            assertTrue(selector, selector.contains("[$attribute*=\"https://cdn.example.com/a/300x250.png\"]"))
        }
        // The query is part of the address here: unlike a saved rule, this one only has to match
        // the element that is on screen now, whose address is exactly the one that was long-pressed.
        assertTrue(
            PageHide.forImage("https://cdn.example.com/a.png?t=1&u=2").orEmpty()
                .contains("[src*=\"https://cdn.example.com/a.png?t=1&u=2\"]"),
        )
    }

    @Test
    fun anAddressIsQuotedAsCss() {
        // A slash is written as itself. JSON quoting writes `\/`, which CSS reads correctly but which
        // makes the selector say something other than the address it is matching.
        assertEquals(
            "img:is([src*=\"https://a.example.com/q.png\"], [srcset*=\"https://a.example.com/q.png\"]," +
                " [data-src*=\"https://a.example.com/q.png\"], [data-original*=\"https://a.example.com/q.png\"]," +
                " [data-lazy-src*=\"https://a.example.com/q.png\"])",
            PageHide.forImage("https://a.example.com/q.png"),
        )
        // A quote or a backslash in the address would end the CSS string, so each is escaped once.
        assertTrue(
            PageHide.forImage("https://a.example.com/a\\b.png").orEmpty()
                .contains("[src*=\"https://a.example.com/a\\\\b.png\"]"),
        )
        assertTrue(
            PageHide.forImage("https://a.example.com/a\"b.png").orEmpty()
                .contains("[src*=\"https://a.example.com/a\\\"b.png\"]"),
        )
        // No address a page can serve an image from carries one of these, and a selector that cannot
        // be quoted is refused rather than written.
        assertNull(PageHide.forImage("https://a.example.com/a\nb.png"))
        assertNull(PageHide.forImage(""))
        assertNull(PageHide.forImage("a".repeat(PageHide.MAX_SELECTOR_LENGTH + 1)))
        // The ceiling is on the text that leaves here, not on the address that came in: the
        // selector repeats the address once per attribute form, so an address well inside the
        // limit once built a selector past it, which apply() then refused — pressing "hide" on a
        // long signed image URL hid nothing at all.
        val long = "https://cdn.example.com/a/300x250.png?token=" + "x".repeat(80)
        assertTrue(long.length < PageHide.MAX_SELECTOR_LENGTH)
        assertNull("an address that fits but whose selector does not is refused here", PageHide.forImage(long))
    }

    @Test
    fun aLongAddressIsStillMatchedByTheAddressResolvedInThePage() {
        // An address long enough that its selector is refused must still hide: the resolved address
        // comparison is the rule that identifies it, and the selector is only a second chance for a
        // lazy loader whose displayed candidate no attribute still spells.
        val long = "https://cdn.example.com/a/300x250.png?token=" + "x".repeat(80)
        assertNull("this fixture is only interesting while the selector is refused", PageHide.forImage(long))
        // Without a selector the only rule left is the resolved-address comparison, which is the
        // whole point: a long signed URL has to hide by the address the browser resolved.
        val script = PageHide.imageScript(long).replace("\\/", "/")
        assertTrue(script, script.contains("if(resolved(el)!==want)continue"))
        assertFalse("a refused selector must not be written into the script", script.contains("el.matches("))

        // A short address gets both rules, so a lazy loader is covered too.
        val short = "https://cdn.example.com/a/300x250.png"
        val shortScript = PageHide.imageScript(short).replace("\\/", "/")
        assertTrue(shortScript.contains("el.matches("))
        assertTrue(shortScript.contains("resolved(el)===want"))
    }

    @Test
    fun theImageScriptIsValidJavaScript() {
        // The script is text sent to a page. A brace out of place makes it a syntax error, and a page
        // reports nothing: the image simply stays. Both shapes are parsed here rather than inspected,
        // because what matters is that the engine accepts them.
        val short = "https://cdn.example.com/a/300x250.png"
        val long = "https://cdn.example.com/a/300x250.png?token=" + "x".repeat(80)
        assertNotNull("the short fixture needs a selector", PageHide.forImage(short))
        assertNull("the long fixture needs no selector", PageHide.forImage(long))
        // The balance check is engine-independent and must run first: no JVM here ships a
        // JavaScript engine, so a branch that returns when the engine is missing would skip it.
        val scripts = listOf("short" to PageHide.imageScript(short), "long" to PageHide.imageScript(long))
        for ((name, script) in scripts) {
            val opens = script.count { it == '{' }
            val closes = script.count { it == '}' }
            assertEquals(name + " script braces must balance: " + script, opens, closes)
        }
        val engine = javax.script.ScriptEngineManager().getEngineByName("js")
        if (engine == null) return
        for ((name, script) in scripts) {
            try {
                engine.eval("(" + script + ")")
            } catch (error: Exception) {
                throw AssertionError(name + " script is not valid JavaScript: " + script, error)
            }
        }
    }

    @Test
    fun aSelectorThatWouldEscapeTheRuleIsRefused() {
        // Braces end the declaration this text is placed in, so a selector carrying one would write
        // CSS out of its own name instead of hiding the element.
        assertFalse(PageHide.isUsable(".ad{display:none}"))
        assertFalse(PageHide.isUsable("div}"))
        assertFalse(PageHide.isUsable(""))
        assertFalse(PageHide.isUsable("   "))
        assertFalse(PageHide.isUsable("div\n.ad"))
        assertFalse(PageHide.isUsable("a".repeat(PageHide.MAX_SELECTOR_LENGTH + 1)))
        assertTrue(PageHide.isUsable("div.ad-slot > ins"))
        assertTrue(PageHide.isUsable(PageHide.forImage("https://cdn.example.com/a.png").orEmpty()))
    }
}
