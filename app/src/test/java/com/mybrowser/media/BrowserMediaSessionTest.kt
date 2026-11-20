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
    @Test fun anUnownedServiceActionFinishesTheServiceItJustStarted() {
        val context = RuntimeEnvironment.getApplication()
        val media = BrowserMediaSession(context)
        media.command(BrowserMediaSession.ACTION_PLAY, fromService = true, ownerToken = 0L)
        assertEquals(MediaPlaybackService.ACTION_FINISH,
            org.robolectric.Shadows.shadowOf(context).nextStartedService?.action)
    }

    @Test fun anUnownedActionCannotStopCurrentBackgroundPlayback() {
        val context = RuntimeEnvironment.getApplication()
        val media = BrowserMediaSession(context)
        val owner = Owner()
        media.update(owner, playing(), "Playing", false, true)
        val shadow = org.robolectric.Shadows.shadowOf(context)
        while (shadow.nextStartedService != null) { }
        media.command(BrowserMediaSession.ACTION_PLAY, fromService = true, ownerToken = 0L)
        assertNull(shadow.nextStartedService)
        assertTrue(owner.commands.isEmpty())
        media.detach(owner)
    }

    @Test fun finishingTheServiceDoesNotCreateNewControls() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val owner = Owner()
        media.update(owner, playing(), "Video", false, false)
        assertTrue(media.notification(withControls = false).actions.isNullOrEmpty())
        media.detach(owner)
        assertTrue(media.notification(withControls = false).actions.isNullOrEmpty())
        val intents = BrowserMediaSession::class.java.getDeclaredField("actionIntents").apply { isAccessible = true }
        assertTrue((intents.get(media) as Map<*, *>).isEmpty())
    }

    private class Owner : BrowserMediaSession.Owner {
        val commands = mutableListOf<Boolean>()
        override fun setPlaying(playing: Boolean) { commands += playing }
        override fun seek(positionMs: Long) = Unit
        override fun openIntent() = Intent(Intent.ACTION_MAIN).setClassName("com.mybrowser", "com.mybrowser.MainActivity")
    }
    @Test fun aPrivatePageGetsAGenericSessionAndAStaleTokenCannotDriveIt() {
        val context = RuntimeEnvironment.getApplication()
        val media = BrowserMediaSession(context)
        val normal = Owner(); val privatePage = Owner()
        val playing = MediaPlaybackTracker.Signal(isPlaying = true, hasMedia = true)
        media.update(normal, playing, "Public title", false, false)
        media.update(privatePage, playing, "Secret private title", true, true)
        // The public page is stopped; the private one keeps a session with no page title.
        assertEquals(listOf(false), normal.commands)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(context.getString(R.string.app_name),
            media.notification().extras.getString(Notification.EXTRA_TITLE))
        // A control minted for the previous document must not touch the private page.
        media.command(BrowserMediaSession.ACTION_PLAY, fromService = true, ownerToken = 12_345L)
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

    private fun playing() = MediaPlaybackTracker.Signal(isPlaying = true, hasMedia = true,
        frameId = "page", videoId = "video", sourceUrl = "https://example.com/video.mp4")

    @Test fun closingMediaRemovesTheSystemTokenTitleAndRemoteOwner() {
        val context = RuntimeEnvironment.getApplication()
        val media = BrowserMediaSession(context)
        val owner = Owner()
        media.update(owner, playing(), "Closed video", false, true)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        media.update(owner, MediaPlaybackTracker.Signal(), "Other page", false, true)
        assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(context.getString(R.string.app_name), media.notification().extras.getString(Notification.EXTRA_TITLE))
        media.command(BrowserMediaSession.ACTION_PLAY)
        assertTrue(owner.commands.isEmpty())
    }

    @Test fun anEndedOrUnloadedElementDoesNotLeaveAResumableSession() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val owner = Owner()
        media.update(owner, playing(), "Video", false, false)
        val ended = playing().copy(isPlaying = false, playbackAvailable = false)
        media.update(owner, ended, "Video", false, false)
        assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        media.update(owner, ended, "Video", false, false)
        media.command(BrowserMediaSession.ACTION_PLAY)
        assertTrue(owner.commands.isEmpty())
    }

    @Test fun aPauseKeepsResumeButSystemStopReleasesItUntilActualPlayback() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val owner = Owner()
        media.update(owner, playing(), "Video", false, false)
        media.update(owner, playing().copy(isPlaying = false), "Video", false, false)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        media.command(BrowserMediaSession.ACTION_PLAY)
        assertEquals(listOf(true), owner.commands)
        media.command(BrowserMediaSession.ACTION_STOP)
        media.update(owner, playing().copy(isPlaying = false), "Video", false, false)
        assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        media.update(owner, playing(), "Video", false, false)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
    }

    @Test fun systemStopIgnoresInFlightPlayingSignalsUntilTheMediaPauses() {
        val context = RuntimeEnvironment.getApplication()
        val media = BrowserMediaSession(context)
        val owner = Owner()
        media.update(owner, playing(), "Video", false, true)
        val shadow = org.robolectric.Shadows.shadowOf(context)
        while (shadow.nextStartedService != null) { }

        media.command(BrowserMediaSession.ACTION_STOP)
        assertEquals(listOf(false), owner.commands)
        assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(MediaPlaybackService.ACTION_FINISH, shadow.nextStartedService?.action)
        repeat(3) {
            media.update(owner, playing(), "Video", false, true)
            assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
            assertNull(shadow.nextStartedService)
        }
        // A callback queued on the released controls cannot resume the stopped owner.
        media.command(BrowserMediaSession.ACTION_PLAY)
        assertEquals(listOf(false), owner.commands)
        media.update(owner, playing().copy(isPlaying = false), "Video", false, true)
        assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))

        // A later real play of the same element still acquires system controls.
        media.update(owner, playing(), "Video", false, true)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertNotNull(shadow.nextStartedService)
        media.detach(owner)
    }

    @Test fun aStopAwaitingPauseDoesNotBlockReplacementMediaOrAnotherOwner() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val owner = Owner()
        media.update(owner, playing(), "Video", false, false)
        media.command(BrowserMediaSession.ACTION_STOP)
        media.update(owner, playing().copy(frameId = "new-document"), "New page", false, false)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        media.command(BrowserMediaSession.ACTION_STOP)
        val replacement = Owner()
        media.update(replacement, playing(), "Other owner", false, false)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        media.detach(replacement)
    }

    @Test fun replacingAPausedVideoDoesNotTransferThePreviousSystemControls() {
        for (replacement in listOf(playing().copy(videoId = "replacement", isPlaying = false),
            playing().copy(sourceUrl = "https://example.com/other.mp4", isPlaying = false))) {
            val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
            val owner = Owner()
            media.update(owner, playing(), "Old video", false, false)
            media.update(owner, replacement, "New video", false, false)
            assertFalse(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
            media.command(BrowserMediaSession.ACTION_PLAY)
            assertTrue(owner.commands.isEmpty())
        }
    }

    @Test fun switchingToPrivatePlaybackKeepsAGenericSessionAndReleasesThePublicOwner() {
        val media = BrowserMediaSession(RuntimeEnvironment.getApplication())
        val publicOwner = Owner(); val privateOwner = Owner()
        media.update(publicOwner, playing(), "Public", false, true)
        media.update(privateOwner, playing(), "Private", true, true)
        assertEquals(listOf(false), publicOwner.commands)
        assertTrue(media.notification().extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.app_name),
            media.notification().extras.getString(Notification.EXTRA_TITLE))
    }
}
