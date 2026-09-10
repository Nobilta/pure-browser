package com.mybrowser.reading

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingListTest {
    @Test fun anArticleHeadingDoesNotDuplicateTheReaderTitle() {
        val article = ReadingArticle("https://example.com", "Article title", listOf(
            ReadingBlock("Article title", true), ReadingBlock("The actual body text.")))
        val parsed = ReadingArticle.parse(article.json())
        assertEquals("Article title", parsed.title)
        assertEquals(listOf(ReadingBlock("The actual body text.")), parsed.blocks)
    }

    private fun article(title: String = "Offline article") = ReadingArticle("https://example.com/article", title,
        listOf(ReadingBlock("Heading", true), ReadingBlock("Text <script> is plain text & stays offline.")))

    @Test fun savesReplacesAndDeletesAcrossRepositoryRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val list = ReadingList(context)
        list.save(article())
        list.save(article("Updated"))
        val restored = ReadingList(context)
        restored.initialize()
        assertEquals(listOf(article("Updated")), restored.articles.value)
        assertTrue(restored.articles.value.single().plainText().contains("<script>"))
        restored.remove(article().url)
        val empty = ReadingList(context)
        empty.initialize()
        assertTrue(empty.articles.value.isEmpty())
    }

    @Test fun invalidSavePreservesThePreviousAtomicFileAndSnapshot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val list = ReadingList(context)
        list.save(article())
        val file = File(context.filesDir, "reading-list.json")
        val before = file.readBytes()
        try { list.save(article().copy(blocks = List(16) { ReadingBlock("x".repeat(10000)) })); fail() }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf(article()), list.articles.value)
    }

    @Test fun malformedLibraryIsReportedWithoutOverwritingUserData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "reading-list.json").apply { writeText("broken-json") }
        try { ReadingList(context).initialize(); fail() } catch (_: org.json.JSONException) { }
        assertEquals("broken-json", file.readText())
    }

    @Test fun articleParserRejectsLocalUrlsAndExcessiveBlocks() {
        assertThrows(IllegalArgumentException::class.java) { ReadingArticle.parse(article().json(), "file:///data/local") }
        val json = JSONObject().put("url", "https://example.com").put("title", "Large")
            .put("blocks", JSONArray().apply { repeat(601) { put(JSONObject().put("text", "x")) } })
        assertThrows(IllegalArgumentException::class.java) { ReadingArticle.parse(json) }
    }
}
