package com.mybrowser.media

import android.app.Application
import android.app.Notification
import android.content.Intent
import com.mybrowser.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserMediaSessionTest {
    private class Owner : BrowserMediaSession.Owner {
        val commands = mutableListOf<Boolean>()
        override fun setPlaying(playing: Boolean) { commands += playing }
        override fun seek(positionMs: Long) = Unit
        override fun openIntent() = Intent(Intent.ACTION_MAIN).setClassName("com.mybrowser", "com.mybrowser.MainActivity")
    }
    @Test fun staleNotificationCannotPublishPrivateTitleOrResumePrivateMedia() {
        val context = RuntimeEnvironment.getApplication()
        val media = BrowserMediaSession(context)
        val normal = Owner(); val privatePage = Owner()
        val playing = MediaPlaybackTracker.Signal(isPlaying = true, hasMedia = true)
        media.update(normal, playing, "Public title", false, false)
        media.update(privatePage, playing, "Secret private title", true, true)
        assertEquals(listOf(false), normal.commands)
        assertEquals(context.getString(R.string.app_name), media.notification().extras.getString(Notification.EXTRA_TITLE))
        media.command(BrowserMediaSession.ACTION_PLAY, fromService = true)
        assertTrue(privatePage.commands.isEmpty())
    }
    @Test fun staleForegroundActionCannotOverrideDisabledBackgroundPreference() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val owner = Owner()
        media.update(owner, MediaPlaybackTracker.Signal(isPlaying = true, hasMedia = true), "Public", false, false)
        media.command(BrowserMediaSession.ACTION_PLAY, fromService = true)
        assertTrue(owner.commands.isEmpty())
    }
    @Test fun removingBrowserTaskStopsItsMedia() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val owner = Owner()
        media.update(owner, MediaPlaybackTracker.Signal(isPlaying = true, hasMedia = true), "Public", false, false)
        media.taskRemoved(owner.openIntent())
        assertEquals(listOf(false), owner.commands)
    }
}
