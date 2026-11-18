package com.mybrowser.devtools

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.mybrowser.core.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageRefererTest {
    @Test fun sharingUsesTheLatestImageRequestInTheCurrentDocument() {
        val view = WebView(RuntimeEnvironment.getApplication())
        val session = DevToolsSession(view)
        val image = "https://cdn.example/picture"
        val page = "https://page.example/"
        try {
            session.onDocumentStarted(page)
            assertNull(session.observedImageReferer(image))
            fun record(headers: Map<String, String>) {
                session.recordNativeRequest(object : WebResourceRequest {
                    override fun getUrl(): Uri = Uri.parse(image)
                    override fun isForMainFrame() = false
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = headers
                }, page, ResourceType.IMAGE)
            }
            record(mapOf("referer" to page))
            assertEquals(page, session.observedImageReferer("$image#part"))
            record(emptyMap())
            assertEquals("", session.observedImageReferer(image))
            session.onDocumentStarted("https://next.example/")
            assertNull(session.observedImageReferer(image))
        } finally {
            session.close()
            view.destroy()
        }
    }
}
