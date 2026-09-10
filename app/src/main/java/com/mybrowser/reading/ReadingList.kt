package com.mybrowser.reading

import android.content.Context
import android.util.AtomicFile
import com.mybrowser.core.UrlUtils
import com.mybrowser.core.writeUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ReadingBlock(val text: String, val heading: Boolean = false)
data class ReadingArticle(val url: String, val title: String, val blocks: List<ReadingBlock>) {
    fun plainText() = title + "\n" + url + "\n\n" + blocks.joinToString("\n\n") { it.text }

    fun json() = JSONObject().put("url", url).put("title", title).put("blocks", JSONArray().apply {
        blocks.forEach { put(JSONObject().put("text", it.text).put("heading", it.heading)) }
    })

    companion object {
        fun parse(value: JSONObject, sourceUrl: String = value.optString("url")): ReadingArticle {
            require(sourceUrl.length <= 8192 && UrlUtils.isHttpUrl(sourceUrl))
            val array = value.getJSONArray("blocks")
            require(array.length() in 1..600)
            var characters = 0
            val blocks = List(array.length()) { index ->
                val block = array.getJSONObject(index)
                val text = block.getString("text")
                require(text.length <= 10000)
                characters += text.length
                require(characters <= 150000)
                ReadingBlock(text, block.optBoolean("heading"))
            }
            val title = value.optString("title").take(512)
            val body = if (blocks.first().heading && blocks.first().text == title) blocks.drop(1) else blocks
            require(body.isNotEmpty())
            return ReadingArticle(sourceUrl, title, body)
        }
    }
}

/** Explicitly saved text articles are readable offline; web video and scripts are not saved. */
class ReadingList(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "reading-list.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<ReadingArticle>>(emptyList())
    val articles = mutable.asStateFlow()
    private var initialized = false

    suspend fun initialize() = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun save(article: ReadingArticle) = withContext(Dispatchers.IO) {
        mutex.withLock {
            read()
            val checked = ReadingArticle.parse(article.json())
            val updated = listOf(checked) + mutable.value.filterNot { it.url == checked.url }
            require(updated.size <= 50)
            write(updated)
        }
    }

    suspend fun remove(url: String) = withContext(Dispatchers.IO) {
        mutex.withLock { read(); write(mutable.value.filterNot { it.url == url }) }
    }

    private fun read() {
        if (initialized) return
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            file.openRead().use { input ->
                val bytes = input.readBytesBounded()
                val array = JSONArray(String(bytes, Charsets.UTF_8))
                require(array.length() <= 50)
                mutable.value = List(array.length()) { ReadingArticle.parse(array.getJSONObject(it)) }.distinctBy { it.url }
            }
        }
        initialized = true
    }

    private fun write(articles: List<ReadingArticle>) {
        val json = JSONArray().apply { articles.forEach { put(it.json()) } }.toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        file.writeUtf8(json)
        mutable.value = articles
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
