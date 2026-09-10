package com.mybrowser.data

data class BookmarkFolder(val id: Long, val parentId: Long, val title: String, val position: Long = 0)

/** Root is virtual id 0. Paths are bounded so malformed imports cannot create cycles. */
object BookmarkFolders {
    const val MAX_FOLDERS = 256
    const val MAX_DEPTH = 16
    const val MAX_NAME = 128

    fun path(id: Long, folders: List<BookmarkFolder>): List<BookmarkFolder> {
        val byId = folders.associateBy { it.id }
        val seen = HashSet<Long>()
        val path = ArrayList<BookmarkFolder>()
        var current = id
        while (current != 0L) {
            require(seen.add(current) && path.size < MAX_DEPTH) { "Folder cycle or nesting limit" }
            val folder = requireNotNull(byId[current]) { "Unknown folder" }
            path.add(folder)
            current = folder.parentId
        }
        return path.asReversed()
    }

    fun validate(folders: List<BookmarkFolder>) {
        require(folders.size <= MAX_FOLDERS && folders.map { it.id }.distinct().size == folders.size)
        folders.forEach {
            require(it.id > 0 && it.title.isNotBlank() && it.title.length <= MAX_NAME)
            path(it.id, folders)
        }
    }
}
