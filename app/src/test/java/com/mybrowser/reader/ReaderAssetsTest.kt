package com.mybrowser.reader

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The reader is shipped as assets; losing one would only fail at runtime, on a tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderAssetsTest {
    @Test
    fun `readability and the controller are packaged`() {
        val assets = RuntimeEnvironment.getApplication().assets
        val readability = assets.open("readability.js").bufferedReader().use { it.readText() }
        val controller = assets.open("reader.js").bufferedReader().use { it.readText() }
        assertTrue(readability.contains("function Readability"))
        assertTrue(controller.contains("__pureReaderState"))
    }
}
