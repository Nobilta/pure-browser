package com.mybrowser.reader

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReaderStyleTest {
    @Test
    fun `reader typography survives a restart`() {
        val context = RuntimeEnvironment.getApplication()

        ReaderStyle.save(context, ReaderStyle(size = 22, lineHeight = 2.0f, paper = ReaderStyle.Paper.WARM))

        val loaded = ReaderStyle.load(context)
        assertEquals(22, loaded.size)
        assertEquals(2.0f, loaded.lineHeight)
        assertEquals(ReaderStyle.Paper.WARM, loaded.paper)
    }

    @Test
    fun `a stored value outside the supported range falls back to the defaults`() {
        val context = RuntimeEnvironment.getApplication()
        ReaderStyle.save(context, ReaderStyle(size = 99, lineHeight = 9f, paper = ReaderStyle.Paper.SYSTEM))

        val loaded = ReaderStyle.load(context)

        assertEquals(ReaderStyle.MAX_SIZE, loaded.size)
        assertEquals(ReaderStyle.DEFAULT_LINE_HEIGHT, loaded.lineHeight)
        assertEquals(ReaderStyle.Paper.SYSTEM, loaded.paper)
    }
}
