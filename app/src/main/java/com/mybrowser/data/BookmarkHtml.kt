package com.mybrowser.data

import com.mybrowser.core.UrlUtils
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class ImportedBookmark(val title: String, val url: String, val folderPath: List<String> = emptyList())
data class BookmarkImport(val entries: List<ImportedBookmark>, val skipped: Int, val folders: List<List<String>> = emptyList())

/** Bulk HTML parsing uses the existing URL JNI library; Android owns files and SQLite. */
object BookmarkHtml {
    const val MAX_BOOKMARKS = 5_000
    const val MAX_BYTES = 8 * 1024 * 1024
    private val available = runCatching { System.loadLibrary("mybrowser_url_utils") }.isSuccess

    fun parse(text: String): BookmarkImport {
        check(available) { "Bookmark parser unavailable" }
        require(text.length <= MAX_BYTES)
        val result = JSONObject(requireNotNull(nativeParse(text)))
        val array = result.getJSONArray("entries")
        require(array.length() <= MAX_BOOKMARKS)
        fun path(array: org.json.JSONArray?): List<String> {
            if (array == null) return emptyList()
            require(array.length() <= BookmarkFolders.MAX_DEPTH)
            return List(array.length()) { array.getString(it).also { name ->
                require(name.isNotBlank() && name.length <= BookmarkFolders.MAX_NAME)
            } }
        }
        val folders = result.optJSONArray("folders") ?: org.json.JSONArray()
        require(folders.length() <= BookmarkFolders.MAX_FOLDERS)
        return BookmarkImport(List(array.length()) { index ->
            val entry = array.getJSONArray(index)
            ImportedBookmark(entry.getString(0).take(512), entry.getString(1).also {
                require(it.length <= 8192 && UrlUtils.isHttpUrl(it))
            }, path(entry.optJSONArray(2)))
        }, result.optInt("skipped"), List(folders.length()) { path(folders.getJSONArray(it)) })
    }

    /** Validate the entire export before SAF creates or truncates the destination. */
    fun encode(bookmarks: List<Bookmark>, folders: List<BookmarkFolder> = emptyList()): ByteArray {
        require(bookmarks.size <= MAX_BOOKMARKS)
        BookmarkFolders.validate(folders)
        require(bookmarks.all { it.folderId == 0L || folders.any { folder -> folder.id == it.folderId } })
        val output = ByteArrayOutputStream()
        fun append(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(output.size() + bytes.size <= MAX_BYTES) { "Bookmark export is too large" }
            output.write(bytes)
        }
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        val childFolders = folders.groupBy { it.parentId }
        val childBookmarks = bookmarks.groupBy { it.folderId }
        fun appendFolder(id: Long) {
        childFolders[id].orEmpty().sortedWith(compareBy<BookmarkFolder> { it.position }.thenBy { it.id }).forEach { folder ->
            append("<DT><H3>${escape(folder.title)}</H3>\n<DL><p>\n")
            appendFolder(folder.id)
            append("</DL><p>\n")
        }
        childBookmarks[id].orEmpty().sortedWith(compareBy<Bookmark> { it.position }.thenBy { it.id }).forEach { bookmark ->
            if (UrlUtils.isHttpUrl(bookmark.url)) {
                require(bookmark.url.length <= 8192)
                append("<DT><A HREF=\"${escape(bookmark.url)}\" ADD_DATE=\"${bookmark.createdAt / 1000}\">${escape(bookmark.title.take(512))}</A>\n")
            }
        }
        }
        appendFolder(0)
        append("</DL><p>\n")
        return output.toByteArray()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    @JvmStatic private external fun nativeParse(text: String): String?
}
