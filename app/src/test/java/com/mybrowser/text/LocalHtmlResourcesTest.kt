package com.mybrowser.text

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.mybrowser.download.closeAndAwait
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import com.mybrowser.App
import com.mybrowser.tabs.BrowserSessionState
import com.mybrowser.tabs.BrowserSessionStateTest
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class LocalHtmlResourcesTest {
    @Test fun slowDirectoryQueryDoesNotBlockDownloadCleanup() {
        val context = RuntimeEnvironment.getApplication()
        val querying = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val provider = ResourcesProvider {
            querying.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "html.resources.test" })
        ShadowContentResolver.registerProviderInternal("html.resources.test", provider)
        shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val downloads = com.mybrowser.download.DownloadHandler(context)
        val executor = Executors.newFixedThreadPool(2)
        val tree = DocumentsContract.buildTreeDocumentUri("html.resources.test", "root")
        try {
            val saving = executor.submit { LocalHtmlResources.remember(context, Uri.parse("content://html.resources.test/main"), tree) }
            assertTrue(querying.await(5, TimeUnit.SECONDS))
            executor.submit { downloads.releaseUnusedDirectoryGrants() }.get(2, TimeUnit.SECONDS)
            assertFalse(saving.isDone)
            proceed.countDown()
            saving.get(5, TimeUnit.SECONDS)
            downloads.releaseUnusedDirectoryGrants()
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
        } finally {
            proceed.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            downloads.closeAndAwait()
        }
    }

    // Each test passes an explicit factory: the default factory caches the prior test's Application.
    class SessionHost : ComponentActivity()

    // Robolectric has no GeolocationPermissions provider; only its platform cleanup is stubbed.
    @Implements(android.webkit.GeolocationPermissions::class)
    class GeolocationPermissionsShadow {
        companion object {
            @JvmStatic @Implementation fun getInstance(): android.webkit.GeolocationPermissions =
                org.robolectric.shadow.api.Shadow.newInstanceOf(android.webkit.GeolocationPermissions::class.java)
        }
        @Implementation fun clearAll() = Unit
    }

    @Test
    @Config(application = App::class, shadows = [BrowserSessionStateTest.NoProfiles::class])
    fun initialOpenSurvivesRecreationAndRetainsMultipleResultsUntilConsumed() {
        val app = RuntimeEnvironment.getApplication() as App
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val opening = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val provider = ResourcesProvider(beforeOpen = {
            opening.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        })
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "html.resources.test" })
        ShadowContentResolver.registerProviderInternal("html.resources.test", provider)
        val controller = Robolectric.buildActivity(SessionHost::class.java).setup()
        val session = ViewModelProvider(controller.get(), ViewModelProvider.AndroidViewModelFactory(app))[BrowserSessionState::class.java]
        val uri = DocumentsContract.buildDocumentUri("html.resources.test", "main")
        try {
            session.openLocalHtml(uri)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            controller.recreate()
            val retained = ViewModelProvider(controller.get(), ViewModelProvider.AndroidViewModelFactory(app))[BrowserSessionState::class.java]
            assertSame(session, retained)
            assertTrue(retained.localHtmlOpenResults.value.isEmpty())
            proceed.countDown()
            awaitMain { retained.localHtmlOpenResults.value.size == 1 }
            retained.openLocalHtml(uri)
            awaitMain { retained.localHtmlOpenResults.value.size == 2 }
            val results = retained.localHtmlOpenResults.value
            assertEquals(2, results.map { it.document!!.url }.toSet().size)
            for (result in results) {
                assertSame(retained.normalTabs, result.owner)
                val document = requireNotNull(result.document)
                assertEquals(uri, document.uri)
                assertEquals("<p>original</p>", document.loader(app).shouldInterceptRequest(Uri.parse(document.url))!!
                    .data.bufferedReader().readText())
            }
            retained.consumeLocalHtmlOpenResults()
            assertTrue(retained.localHtmlOpenResults.value.isEmpty())
        } finally {
            proceed.countDown()
            controller.pause().stop().destroy()
            app.downloadHandler.closeAndAwait()
        }
    }

    @Test
    @Config(application = App::class, shadows = [BrowserSessionStateTest.NoProfiles::class, GeolocationPermissionsShadow::class])
    fun pendingPrivateHtmlOpenCannotReturnIntoANewSession() {
        val app = RuntimeEnvironment.getApplication() as App
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val opening = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val provider = ResourcesProvider(beforeOpen = {
            opening.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        })
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "html.resources.test" })
        ShadowContentResolver.registerProviderInternal("html.resources.test", provider)
        val controller = Robolectric.buildActivity(SessionHost::class.java).setup()
        val session = ViewModelProvider(controller.get(), ViewModelProvider.AndroidViewModelFactory(app))[BrowserSessionState::class.java]
        try {
            session.beginPrivateSession()
            session.openLocalHtml(DocumentsContract.buildDocumentUri("html.resources.test", "main"))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            val job = session.viewModelScope.coroutineContext[Job]!!.children.single()
            session.endPrivateSession()
            awaitMain { !session.privacy.isTransitioning }
            session.beginPrivateSession()
            proceed.countDown()
            awaitMain { job.isCompleted }
            assertTrue(session.localHtmlOpenResults.value.isEmpty())

            session.openLocalHtml(DocumentsContract.buildDocumentUri("html.resources.test", "main"))
            awaitMain { session.localHtmlOpenResults.value.size == 1 }
            assertSame(session.privateTabs, session.localHtmlOpenResults.value.single().owner)
            session.endPrivateSession()
            assertTrue(session.localHtmlOpenResults.value.isEmpty())
        } finally {
            proceed.countDown()
            controller.pause().stop().destroy()
            app.downloadHandler.closeAndAwait()
        }
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for the session", condition())
    }

    @Test
    @Config(application = App::class, shadows = [BrowserSessionStateTest.NoProfiles::class])
    fun directorySaveSurvivesActivityRecreationAndRetainsItsResult() {
        val app = RuntimeEnvironment.getApplication() as App
        shadowOf(app).grantPermissions(app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val querying = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val provider = ResourcesProvider {
            querying.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "html.resources.test" })
        ShadowContentResolver.registerProviderInternal("html.resources.test", provider)
        val controller = Robolectric.buildActivity(SessionHost::class.java).setup()
        val session = ViewModelProvider(controller.get(), ViewModelProvider.AndroidViewModelFactory(app))[BrowserSessionState::class.java]
        val tree = DocumentsContract.buildTreeDocumentUri("html.resources.test", "root")
        val document = LocalHtmlDocument.open(app, DocumentsContract.buildDocumentUri("html.resources.test", "main"))
        session.normalTabs.createTab(document.url)
        session.normalTabs.currentTab!!.localHtml = document
        val sourceTab = session.normalTabs.currentTab!!
        try {
            session.saveLocalHtmlDirectory(document, tree)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(querying.await(5, TimeUnit.SECONDS))
            controller.recreate()
            val retained = ViewModelProvider(controller.get(), ViewModelProvider.AndroidViewModelFactory(app))[BrowserSessionState::class.java]
            assertSame(session, retained)
            assertNull(document.resourceTree)
            session.normalTabs.createTab("https://example.com/other")
            proceed.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (retained.localHtmlDirectoryResult.value == null && System.nanoTime() < deadline) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val result = requireNotNull(retained.localHtmlDirectoryResult.value)
            assertTrue(result.saved)
            assertSame(document, result.document)
            assertEquals(tree, document.resourceTree)
            assertTrue(sourceTab.reloadOnSelect)
            assertFalse(session.normalTabs.currentTab!!.reloadOnSelect)
            assertEquals(tree, LocalHtmlResources.find(app, document.uri))
            val js = document.loader(app).shouldInterceptRequest(Uri.parse(document.url).buildUpon().path("/assets/app.js").build())!!
            assertEquals("window.ready=true", js.data.bufferedReader().readText())
            retained.consumeLocalHtmlDirectoryResult()
            assertNull(retained.localHtmlDirectoryResult.value)
        } finally {
            proceed.countDown()
            controller.pause().stop().destroy()
            app.downloadHandler.closeAndAwait()
        }
    }

    @Test fun explicitlyChosenFolderServesNestedResourcesAndPersistsWithoutWideningFileAccess() {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric's API 29 MIME map starts empty; real Android registers these types.
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("css", "text/css")
            addExtensionMimeTypeMapping("svg", "image/svg+xml")
        }
        val provider = ResourcesProvider()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "html.resources.test" })
        ShadowContentResolver.registerProviderInternal("html.resources.test", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("html.resources.test", "root")
        val main = DocumentsContract.buildDocumentUri("html.resources.test", "main")
        val document = LocalHtmlDocument.open(context, main)
        val loader = document.loader(context)
        fun response(path: String) = loader.shouldInterceptRequest(Uri.parse(document.url).buildUpon().encodedPath(path).build())!!
        assertEquals(404, response("/assets/theme.css").statusCode)
        LocalHtmlResources.remember(context, main, tree)
        document.resourceTree = tree
        val css = response("/assets/theme.css")
        assertEquals("text/css", css.mimeType)
        assertEquals("application/octet-stream", response("/css").mimeType)
        assertEquals("p{color:green}", css.data.bufferedReader().readText())
        assertEquals("window.ready=true", response("/assets/app.js").data.bufferedReader().readText())
        assertEquals("image/svg+xml", response("/image.svg").mimeType)
        assertEquals("space 中文", response("/assets/a%20b.txt").data.bufferedReader().readText())
        for (path in listOf("/../outside.txt", "/assets/%2e%2e/outside.txt", "/assets/missing.css", "/assets%5coutside.txt")) {
            assertEquals(path, 404, response(path).statusCode)
        }
        assertEquals(tree, LocalHtmlDocument.open(context, main).resourceTree)
        assertEquals(setOf(tree.toString()), LocalHtmlResources.retainedTrees(context))
        org.robolectric.Shadows.shadowOf(context).grantPermissions(context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val downloads = com.mybrowser.download.DownloadHandler(context)
        try {
            downloads.releaseUnusedDirectoryGrants()
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission })
        } finally { downloads.closeAndAwait() }
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val reopened = LocalHtmlDocument.open(context, main)
        assertNull(reopened.resourceTree)
        assertEquals(404, reopened.loader(context)
            .shouldInterceptRequest(Uri.parse(reopened.url).buildUpon().path("/assets/theme.css").build())!!.statusCode)
    }

    private class ResourcesProvider(private val beforeOpen: () -> Unit = {}, private val beforeRootQuery: () -> Unit = {}) : ContentProvider() {
        private val files = mapOf("main" to ("index.html" to "<p>original</p>"),
            "css" to ("theme.css" to "p{color:green}"), "js" to ("app.js" to "window.ready=true"),
            "image" to ("image.svg" to "<svg/>"), "space" to ("a b.txt" to "space 中文"),
            "extensionless" to ("css" to "plain data"))
        private val children = mapOf("root" to listOf("main", "assets", "image", "extensionless"), "assets" to listOf("css", "js", "space"))
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val id = DocumentsContract.getDocumentId(uri)
            if (id == "root" && uri.lastPathSegment != "children") beforeRootQuery()
            val ids = if (uri.lastPathSegment == "children") children[id].orEmpty() else listOf(id)
            return MatrixCursor(columns).apply {
                for (entry in ids) addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> entry
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> files[entry]?.first ?: entry
                    else -> null
                } }.toTypedArray())
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            beforeOpen()
            val id = DocumentsContract.getDocumentId(uri)
            val file = File(RuntimeEnvironment.getApplication().cacheDir, "resource-$id")
            file.writeText(files.getValue(id).second)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Read only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Read only")
    }
}
