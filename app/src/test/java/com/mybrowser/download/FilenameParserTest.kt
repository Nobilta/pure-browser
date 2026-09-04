package com.mybrowser.download

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FilenameParser] decides what a download is called, from headers that routinely
 * disagree with the spec. Every branch here is one a real server has produced.
 *
 * Robolectric for Uri.parse and MimeTypeMap. sdk = 34 and a plain Application for the
 * same reasons as UrlUtilsTest — the real App class initialises WebView, which
 * Robolectric's shadow rejects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FilenameParserTest {

    private fun resolve(
        disposition: String? = null,
        url: String = "https://example.com/",
        mime: String? = null,
    ) = FilenameParser.resolve(disposition, url, mime)

    // --- RFC 6266 plain form ---

    @Test
    fun `quoted filename is taken as-is`() {
        assertEquals("report.pdf", resolve("attachment; filename=\"report.pdf\""))
    }

    @Test
    fun `unquoted filename is accepted`() {
        assertEquals("report.pdf", resolve("attachment; filename=report.pdf"))
    }

    @Test
    fun `whitespace around the equals sign is tolerated`() {
        assertEquals("a.zip", resolve("attachment; filename = \"a.zip\""))
    }

    @Test
    fun `parameter name is case-insensitive`() {
        assertEquals("a.zip", resolve("attachment; FileName=\"a.zip\""))
    }

    // --- RFC 5987 extended form ---

    @Test
    fun `extended form is percent-decoded as utf-8`() {
        assertEquals("报告.pdf", resolve("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"))
    }

    @Test
    fun `extended form wins over the plain parameter`() {
        // RFC 6266: filename* takes precedence when both are present.
        val header = "attachment; filename=\"fallback.pdf\"; " +
            "filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"
        assertEquals("报告.pdf", resolve(header))
    }

    @Test
    fun `extended form honours a declared gbk charset`() {
        // 报告 in GBK is B1 A8 B8 E6.
        assertEquals("报告.pdf", resolve("attachment; filename*=GBK''%B1%A8%B8%E6.pdf"))
    }

    @Test
    fun `extended form with an empty charset defaults to utf-8`() {
        assertEquals("报告.pdf", resolve("attachment; filename*=''%E6%8A%A5%E5%91%8A.pdf"))
    }

    @Test
    fun `unknown charset in extended form falls back to the plain parameter`() {
        val header = "attachment; filename=\"plain.pdf\"; filename*=NOSUCH''%41.pdf"
        assertEquals("plain.pdf", resolve(header))
    }

    // --- RFC 2047 MIME encoded-word (invalid here, but sent anyway) ---

    @Test
    fun `base64 mime word is decoded`() {
        // =?UTF-8?B?5oql5ZGKLnBkZg==?= is "报告.pdf".
        val header = "attachment; filename=\"=?UTF-8?B?5oql5ZGKLnBkZg==?=\""
        assertEquals("报告.pdf", resolve(header))
    }

    @Test
    fun `quoted-printable mime word is decoded`() {
        val header = "attachment; filename=\"=?UTF-8?Q?report=5F1.pdf?=\""
        assertEquals("report_1.pdf", resolve(header))
    }

    @Test
    fun `quoted-printable underscore becomes a space`() {
        val header = "attachment; filename=\"=?UTF-8?Q?my_file.pdf?=\""
        assertEquals("my file.pdf", resolve(header))
    }

    // --- percent-encoding inside the plain parameter ---

    @Test
    fun `percent-encoded plain filename is decoded`() {
        assertEquals("报告.pdf", resolve("attachment; filename=\"%E6%8A%A5%E5%91%8A.pdf\""))
    }

    @Test
    fun `a literal percent that is not an escape survives`() {
        // "100%.txt" is not valid percent-encoding; the name must not be destroyed.
        val name = resolve("attachment; filename=\"100%.txt\"")
        assertTrue("got $name", name.endsWith(".txt"))
    }

    // --- mojibake repair ---

    @Test
    fun `latin1-read gbk bytes are repaired`() {
        // What HttpURLConnection hands over when a server sends raw GBK bytes: each
        // byte becomes the same-valued char.
        val gbkAsLatin1 = String(
            byteArrayOf(0xB1.toByte(), 0xA8.toByte(), 0xB8.toByte(), 0xE6.toByte()),
            Charsets.ISO_8859_1,
        )
        assertEquals("报告.pdf", resolve("attachment; filename=\"$gbkAsLatin1.pdf\""))
    }

    @Test
    fun `pure ascii is left untouched by the repair path`() {
        assertEquals("plain-name.pdf", resolve("attachment; filename=\"plain-name.pdf\""))
    }

    @Test
    fun `already decoded unicode header is not converted to mojibake`() {
        assertEquals("报告.pdf", resolve("attachment; filename=\"报告.pdf\""))
    }

    @Test
    fun `literal plus in an extended filename is preserved`() {
        assertEquals("a+b.pdf", resolve("attachment; filename*=UTF-8''a+b.pdf"))
    }

    // --- falling back to the URL ---

    @Test
    fun `null disposition falls back to the url filename`() {
        assertEquals("file.pdf", resolve(url = "https://example.com/a/b/file.pdf"))
    }

    @Test
    fun `url filename is percent-decoded`() {
        assertEquals("报告.pdf", resolve(url = "https://example.com/%E6%8A%A5%E5%91%8A.pdf"))
    }

    @Test
    fun `literal plus in a url filename is preserved`() {
        assertEquals("a+b.pdf", resolve(url = "https://example.com/a+b.pdf"))
    }

    @Test
    fun `query string does not leak into the url filename`() {
        assertEquals("file.pdf", resolve(url = "https://example.com/file.pdf?token=abc"))
    }

    @Test
    fun `header with no filename parameter falls back to the url`() {
        assertEquals("file.pdf", resolve("attachment", "https://example.com/file.pdf"))
    }

    @Test
    fun `blank filename falls back rather than yielding an empty name`() {
        assertEquals("file.pdf", resolve("attachment; filename=\"\"", "https://x.com/file.pdf"))
    }

    @Test
    fun `url with no usable path yields the fallback name`() {
        assertEquals("download", resolve(url = "https://example.com/"))
    }

    // --- extension from mime type ---

    @Test
    fun `extension is appended when the name has none`() {
        val name = resolve("attachment; filename=\"report\"", mime = "application/pdf")
        assertEquals("report.pdf", name)
    }

    @Test
    fun `mime parameters are ignored when deriving the extension`() {
        val name = resolve("attachment; filename=\"page\"", mime = "text/html; charset=utf-8")
        assertEquals("page.html", name)
    }

    @Test
    fun `existing extension is not replaced`() {
        assertEquals("a.pdf", resolve("attachment; filename=\"a.pdf\"", mime = "image/png"))
    }

    @Test
    fun `unknown mime type leaves the name extensionless`() {
        val name = resolve("attachment; filename=\"report\"", mime = "application/x-nope")
        assertEquals("report", name)
    }

    // --- sanitising ---

    @Test
    fun `path separators are replaced`() {
        val name = resolve("attachment; filename=\"a/b\\c.pdf\"")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertEquals("a_b_c.pdf", name)
    }

    @Test
    fun `control characters are replaced`() {
        val withTab = "a\tb.pdf"
        assertEquals("a_b.pdf", resolve("attachment; filename=\"$withTab\""))
    }

    @Test
    fun `spaces and hyphens are preserved`() {
        assertEquals("my report-final.pdf", resolve("attachment; filename=\"my report-final.pdf\""))
    }

    @Test
    fun `a leading dot is stripped so the file is not hidden`() {
        assertEquals("bashrc", resolve("attachment; filename=\".bashrc\""))
    }

    @Test
    fun `a name of only illegal characters stays usable`() {
        val name = resolve("attachment; filename=\"///\"")
        assertTrue("got $name", name.isNotBlank())
        assertFalse(name.contains('/'))
    }

    @Test
    fun `an over-long name keeps its extension`() {
        val name = resolve("attachment; filename=\"${"a".repeat(300)}.pdf\"")
        assertTrue("length was ${name.length}", name.length <= 127)
        assertTrue("got $name", name.endsWith(".pdf"))
    }

    @Test
    fun `an over-long name with no real extension is simply truncated`() {
        assertEquals(127, resolve("attachment; filename=\"${"a".repeat(300)}\"").length)
    }

    @Test
    fun `a long trailing segment is not treated as an extension to preserve`() {
        // ".{13 chars}" exceeds the 12-char extension window, so no extension is kept.
        val name = resolve("attachment; filename=\"${"a".repeat(300)}.${"b".repeat(13)}\"")
        assertTrue("length was ${name.length}", name.length <= 127)
        assertFalse("got $name", name.endsWith(".${"b".repeat(13)}"))
    }
}
