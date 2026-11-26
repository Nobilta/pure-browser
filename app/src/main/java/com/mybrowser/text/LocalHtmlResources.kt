package com.mybrowser.text

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.mybrowser.data.commitConfirmed
import java.io.IOException

/** Explicit folder choices are saved preferences, shared by ordinary and private browsing. */
internal object LocalHtmlResources {
    private fun preferences(context: Context) = context.getSharedPreferences("local_html_resources", Context.MODE_PRIVATE)

    fun retainedTrees(context: Context): Set<String> = preferences(context).all
        .filterKeys { it.startsWith("tree:") }.values.filterIsInstance<String>().toSet()

    fun remember(context: Context, file: Uri, tree: Uri) {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { if (!it.moveToFirst()) throw IOException("Directory unavailable") }
            ?: throw IOException("Directory unavailable")
        // Keep provider queries outside the download cleanup lock. Only taking and saving
        // the grant must be atomic with cleanup.
        synchronized(this) {
            context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences(context).commitConfirmed(mapOf("file:$file" to tree.toString(), "tree:$tree" to tree.toString()))
        }
    }

    /** Reuse an explicit file binding, or an authorized folder containing this document. IO only. */
    fun find(context: Context, file: Uri): Uri? {
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        preferences(context).getString("file:$file", null)?.let { saved ->
            saved.toUri().takeIf { it in grants }?.let { return it }
        }
        if (!DocumentsContract.isDocumentUri(context, file)) return null
        val id = DocumentsContract.getDocumentId(file)
        for (tree in retainedTrees(context).map(String::toUri)) {
            if (tree !in grants || tree.authority != file.authority) continue
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) if (cursor.getString(0) == id) return tree
                }
            } catch (_: IOException) {
                // An unavailable provider must not prevent opening the separately granted HTML.
            } catch (_: SecurityException) {
                // The folder may have been revoked since the permission snapshot.
            }
        }
        return null
    }

    /** Resolve display names through the provider; document IDs are opaque, never filesystem paths. */
    fun resolve(context: Context, tree: Uri, path: String): Uri? {
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it }) return null
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (segment in segments) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            id = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                var found: String? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == segment) { found = cursor.getString(0); break }
                found
            } ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }
}
