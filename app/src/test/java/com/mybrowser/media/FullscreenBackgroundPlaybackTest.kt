package com.mybrowser.media

import android.app.Activity
import android.app.Application
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebView
import com.mybrowser.core.VideoFit
import com.mybrowser.data.VideoPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FullscreenBackgroundPlaybackTest {
    class PlayerActivity : Activity() {
        var autoEnter = false
        override fun setPictureInPictureParams(params: PictureInPictureParams) {
            autoEnter = params.isAutoEnterEnabled
        }
    }

    @Test fun enhancedControlsSurviveWindowHideAndWakeWithoutPausingOrExiting() {
        val controller = Robolectric.buildActivity(PlayerActivity::class.java).setup()
        val activity = controller.get()
        val commands = mutableListOf<String>()
        val page = object : WebView(activity) {
            override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                if (script.contains("JSON.stringify(out)")) {
                    resultCallback?.onReceiveValue(
                        """[{"frameId":"page","videoId":"video","hasVideo":true,"playing":true,"fullscreen":true,"nativeControlsAvailable":true}]""")
                } else {
                    Regex("""api\.command\((\{.*?\})\)""").find(script)?.let {
                        commands += org.json.JSONObject(it.groupValues[1]).getString("type")
                    }
                    resultCallback?.onReceiveValue("true")
                }
            }
        }
        val tracker = MediaPlaybackTracker(page) {}
        tracker.probe()
        val video = object : View(activity) {
            val visibilityChanges = mutableListOf<Int>()
            override fun onWindowVisibilityChanged(visibility: Int) { visibilityChanges += visibility }
        }
        var exits = 0
        val host = FullscreenVideoView(
            activity, video, VideoPreferences(), enhancedPlayback = true, initialMirror = false,
            initialFit = VideoFit.NATURAL, tracker = tracker, titleProvider = { "Playing video" },
            canCast = { false }, onExit = { exits++ }, onChooseSpeed = {}, onChooseTransform = { _, _ -> },
            castContent = {}, backgroundPlayback = { true }, onBackgroundPlaybackChange = {},
        )
        try {
            host.update(tracker.current)
            assertTrue("The test must enter enhanced playback", "nativeControls" in commands)
            val originalParent = video.parent
            commands.clear()
            host.cancelTransientControls()
            host.onWindowFocusChanged(false)
            host.dispatchWindowVisibilityChanged(View.GONE)
            host.update(tracker.current)
            assertTrue(host.keepMediaOnWindowHidden)
            host.dispatchWindowVisibilityChanged(View.VISIBLE)
            host.onWindowFocusChanged(true)
            host.update(tracker.current)
            assertTrue(video.visibilityChanges.isNotEmpty())
            assertTrue(video.visibilityChanges.all { it == View.VISIBLE })
            assertFalse(commands.any { it in setOf("pause", "pauseAll", "suspend", "restoreControls", "nativeControls") })
            assertSame(originalParent, video.parent)
            assertEquals(0, exits)
        } finally {
            host.release()
            tracker.close()
            page.destroy()
            controller.pause().stop().destroy()
        }
    }

    @Test fun backgroundPlaybackControlsFullscreenVisibilityAndAutoPip() {
        val activity = Robolectric.buildActivity(PlayerActivity::class.java).setup().get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val page = object : WebView(activity) {
            var playing = true
            override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                if (script.contains("JSON.stringify(out)")) {
                    resultCallback?.onReceiveValue(
                        """[{"frameId":"page","videoId":"video","hasVideo":true,"playing":$playing}]""")
                }
            }
        }
        val tracker = MediaPlaybackTracker(page) {}
        val video = object : View(activity) {
            var reportedVisibility = GONE
            override fun onWindowVisibilityChanged(visibility: Int) { reportedVisibility = visibility }
        }
        fun hideWindow(background: Boolean, playing: Boolean) {
            var allowBackground = background
            page.playing = playing
            tracker.probe()
            val host = FullscreenVideoView(
                activity, video, VideoPreferences(), enhancedPlayback = false, initialMirror = false,
                initialFit = VideoFit.NATURAL, tracker = tracker, titleProvider = { "" },
                canCast = { false }, onExit = {}, onChooseSpeed = {}, onChooseTransform = { _, _ -> },
                castContent = {}, backgroundPlayback = { allowBackground }, onBackgroundPlaybackChange = {},
            )
            val pip = PictureInPictureController(activity, { tracker }, { host }, { background })
            pip.update()
            assertEquals(background && playing, activity.autoEnter)
            host.dispatchWindowVisibilityChanged(View.GONE)
            assertEquals(if (background && playing) View.VISIBLE else View.GONE, video.reportedVisibility)
            allowBackground = !background
            host.update(tracker.current)
            host.dispatchWindowVisibilityChanged(View.GONE)
            assertEquals(if (allowBackground && playing) View.VISIBLE else View.GONE, video.reportedVisibility)
            pip.close()
            host.release()
        }
        hideWindow(background = true, playing = true)
        hideWindow(background = false, playing = true)
        hideWindow(background = true, playing = false)
        tracker.close()
        page.destroy()
    }
}
