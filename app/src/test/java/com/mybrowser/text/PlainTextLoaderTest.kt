package com.mybrowser.text

import android.app.Application
import android.net.Uri
import com.mybrowser.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class PlainTextLoaderTest {
    private fun load(bytes: ByteArray): TextContent {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://text.test/sample.txt")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return PlainTextLoader.load(context, uri)
    }

    @Test fun acceptsEmptyFilesAndExactlyFiveMiB() {
        assertEquals("", (load(byteArrayOf()) as TextContent.Ready).body)
        val text = "a".repeat(PlainTextLoader.MAX_BYTES - 6) + "中文"
        assertEquals(text, (load(text.toByteArray()) as TextContent.Ready).body)
    }

    @Test fun rejectsOneExtraByteInsteadOfDecodingAPartialChineseCharacter() {
        val bytes = ("a".repeat(PlainTextLoader.MAX_BYTES - 2) + "中").toByteArray()
        assertEquals(PlainTextLoader.MAX_BYTES + 1, bytes.size)
        assertEquals(TextContent.Failed(R.string.plain_text_too_large), load(bytes))
    }

    @Test fun readsOnlyTheBoundPlusOneAndClosesAnOversizedUnknownLengthStream() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://text.test/unknown.txt")
        val input = object : ByteArrayInputStream(ByteArray(PlainTextLoader.MAX_BYTES + 500) { 65 }) {
            var closed = false
            override fun close() { closed = true }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        assertEquals(TextContent.Failed(R.string.plain_text_too_large), PlainTextLoader.load(context, uri))
        assertEquals(499, input.available())
        assertTrue(input.closed)
    }

    @Test fun rejectsBinaryAndFileScheme() {
        assertEquals(TextContent.Failed(R.string.plain_text_not_text), load(byteArrayOf(65, 0)))
        assertEquals(TextContent.Failed(R.string.plain_text_open_failed),
            PlainTextLoader.load(RuntimeEnvironment.getApplication(), Uri.parse("file:///sample.txt")))
    }
}
