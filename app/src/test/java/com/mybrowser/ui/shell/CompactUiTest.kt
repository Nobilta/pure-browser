package com.mybrowser.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.VideoFit
import com.mybrowser.media.PlayerControls
import com.mybrowser.media.PlayerControlsState
import com.mybrowser.ui.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h720dp-mdpi")
class CompactUiTest {
    @Test fun findInputHasOneLabelAndKeepsTheEditableValueAccessible() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val label = context.getString(R.string.ui_find_in_page)
        for (query in listOf("", "visible query")) {
            render(360, 1f, { FindBar(query, {}, 1, 0, {}, {}, {}) }) { nodes ->
                val input = nodes.single { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                assertEquals(listOf(label), input.config[SemanticsProperties.ContentDescription])
                assertEquals(query, input.config[SemanticsProperties.EditableText].text)
                assertTrue(nodes.none { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                })
            }
        }
    }

    @Test fun actionRowsExposeButtonsWithoutReplacingSwitchOrSelectionRoles() {
        var clicked = 0
        render(360, 1f, {
            androidx.compose.foundation.layout.Column {
                com.mybrowser.ui.components.BrowserListItem("Action", { clicked++ })
                com.mybrowser.ui.components.BrowserListItem("Long action", { clicked++ }, onLongClick = {})
                com.mybrowser.ui.components.BrowserListItem("Disabled", {}, enabled = false)
                com.mybrowser.ui.components.BrowserListItem("Switch", {}, toggleState = true)
                com.mybrowser.ui.components.BrowserListItem("Radio", {}, selected = true)
                com.mybrowser.ui.components.BrowserListItem("Tab", {}, selected = true, role = androidx.compose.ui.semantics.Role.Tab)
                BrowserActionRow(R.drawable.ic_ui_copy, "Sheet action") { clicked++ }
            }
        }) { nodes ->
            fun action(label: String): SemanticsNode {
                val text = nodes.first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
                return generateSequence(text) { it.parent }.first { it.config.getOrNull(SemanticsProperties.Role) != null }
            }
            listOf("Action", "Long action", "Sheet action").forEach { label ->
                val node = action(label)
                assertEquals(androidx.compose.ui.semantics.Role.Button, node.config[SemanticsProperties.Role])
                assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
            }
            assertEquals(3, clicked)
            assertTrue(action("Disabled").config.contains(SemanticsProperties.Disabled))
            assertEquals(androidx.compose.ui.semantics.Role.Switch, action("Switch").config[SemanticsProperties.Role])
            assertEquals(androidx.compose.ui.semantics.Role.RadioButton, action("Radio").config[SemanticsProperties.Role])
            assertEquals(androidx.compose.ui.semantics.Role.Tab, action("Tab").config[SemanticsProperties.Role])
        }
    }

    @Test fun devToolsStorageKeepsRefreshReachableWithLargeText() {
        render(320, 2f, { com.mybrowser.ui.devtools.StorageTab(null, null) }) { nodes ->
            val label = org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.dev_storage_refresh)
            val icon = nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            val action = generateSequence(icon) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val bounds = action.touchBoundsInRoot
            assertTrue("Refresh bounds: $bounds", bounds.width >= 48f && bounds.height >= 48f)
            assertTrue("Refresh bounds: $bounds", bounds.left >= 0f && bounds.right <= 320f)
        }
    }

    @Test fun jsonDisclosureHasAnAccessible48dpActionSeparateFromSelectableText() {
        var toggled: String? = null
        render(320, 2f, {
            com.mybrowser.ui.devtools.JsonRowView(
                com.mybrowser.ui.devtools.JsonRow("root/key", 0, "payload", "", true, false, "{2}"),
            ) { toggled = it }
        }) { nodes ->
            val label = org.robolectric.RuntimeEnvironment.getApplication().getString(R.string.dev_dom_expand, "payload")
            val icon = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            val action = generateSequence(icon) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(action.touchBoundsInRoot.width >= 48f && action.touchBoundsInRoot.height >= 48f)
            assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals("root/key", toggled)
            val value = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "payload: {2}" } == true }
            assertTrue(!value.config.contains(SemanticsActions.OnClick))
        }
    }

    @Test fun networkActionsAndCountRemainReadableWithLargeText() {
        render(320, 2f, {
            com.mybrowser.ui.devtools.NetworkLogTab(emptyList(), {}, {}, null)
        }) { nodes ->
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val countLabel = context.getString(R.string.dev_network_count, 0, 0)
            val count = nodes.first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == countLabel } == true
            }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(count.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val layout = layouts.single()
            assertTrue("Counter must fit in at most two lines", layout.lineCount <= 2)
            assertEquals(countLabel.length, layout.getLineEnd(layout.lineCount - 1))
            // Paragraph width can include unused space; check the painted line extents.
            repeat(layout.lineCount) { line ->
                assertTrue("Counter line width: ${layout.getLineRight(line)} / ${layout.size.width}",
                    layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue(!layout.isLineEllipsized(line))
            }
            assertTrue(!layout.didOverflowHeight)
            val clear = nodes.first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(R.string.ui_clear)) == true
            }
            val action = generateSequence(clear) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val bounds = action.touchBoundsInRoot
            assertTrue("Clear bounds: $bounds", bounds.width >= 48f && bounds.height >= 48f)
            assertTrue("Clear bounds: $bounds", bounds.left >= 0f && bounds.right <= 320f)
            assertTrue(count.boundsInRoot.top >= bounds.bottom)
        }
    }

    @Test fun wrappedSelectionActionsStayAtTheTrailingEdge() {
        render(320, 2f, {
            LibrarySelectionBar(true, false, {}, "2 selected", Modifier.padding(horizontal = 16.dp)) {
                repeat(4) { BrowserIconAction(R.drawable.ic_ui_more, "Action $it") {} }
            }
        }) { nodes ->
            val count = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "2 selected" } == true }
            val actions = (0..3).map { index ->
                val icon = nodes.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Action $index") == true }
                generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            }
            assertTrue("count=${count.boundsInRoot}, actions=${actions.map { it.boundsInRoot }}",
                actions.first().boundsInRoot.top >= count.boundsInRoot.bottom)
            assertEquals(304f, actions.last().touchBoundsInRoot.right, 1f)
            actions.forEach { assertTrue(it.touchBoundsInRoot.width >= 48f) }
        }
    }

    @Test fun playerStatusFitsPortraitButYieldsToLargeText() {
        listOf(Triple(360, 1f, true), Triple(412, 1f, true), Triple(488, 1f, true),
            Triple(720, 2f, true), Triple(320, 2f, false)).forEach { (width, scale, statusVisible) ->
            render(width, scale, {
                PlayerControls(
                    state = PlayerControlsState(true, false, "Video title", true, true, false,
                        0f, "00:00", 1f, false, null, false, VideoFit.NATURAL, null,
                        false, null, "12:30", 85, false),
                    onPlayPause = {}, onSeek = {}, onSeekFinished = {}, onLock = {}, onExit = {},
                    onRotate = {}, onPictureInPicture = {}, onMenu = {}, onSpeedPreview = {},
                    onSpeed = {}, onMirror = {}, onFit = {}, backgroundPlayback = false,
                    onBackgroundPlaybackChange = {}, castContent = {},
                )
            }) { nodes ->
                assertEquals(statusVisible, nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "player_status_strip" })
                val title = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Video title" } == true }
                assertTrue(title.boundsInRoot.width >= 100f)
            }
        }
    }

    @Test fun videoOptionsLeaveTheControlBarVisibleInLandscapeAndLargeText() {
        for ((width, height, scale) in listOf(Triple(720, 360, 1f), Triple(320, 720, 2f))) {
            render(width, scale, {
                PlayerControls(
                    state = PlayerControlsState(true, false, "Video title", true, true, true,
                        0f, "01:23 / 45:67", 1f, true, com.mybrowser.media.PlayerMenu.VIDEO, false,
                        VideoFit.NATURAL, null, false, null, "12:30", 85, false),
                    onPlayPause = {}, onSeek = {}, onSeekFinished = {}, onLock = {}, onExit = {},
                    onRotate = {}, onPictureInPicture = null, onMenu = {}, onSpeedPreview = {},
                    onSpeed = {}, onMirror = {}, onFit = {}, backgroundPlayback = true,
                    onBackgroundPlaybackChange = {}, castContent = {},
                )
            }, height = height) { nodes ->
                val bar = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "player_control_bar" }
                val panel = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "player_video_menu" }
                assertTrue(bar.boundsInRoot.height >= 96f)
                assertTrue(bar.boundsInRoot.bottom <= height)
                assertTrue(panel.boundsInRoot.height > 0f)
                assertTrue(panel.boundsInRoot.bottom <= bar.boundsInRoot.top)
                val position = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "player_position" }
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                position.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                assertTrue(!layouts.single().hasVisualOverflow)
            }
        }
    }

    @Test fun speedPanelScrollsInsteadOfCompressingLargeText() {
        render(720, 2f, {
            PlayerControls(
                state = PlayerControlsState(true, false, "Video title", true, true, true,
                    0f, "01:23 / 45:67", 1f, true, com.mybrowser.media.PlayerMenu.SPEED, false,
                    VideoFit.NATURAL, null, false, null, "12:30", 85, false),
                onPlayPause = {}, onSeek = {}, onSeekFinished = {}, onLock = {}, onExit = {},
                onRotate = {}, onPictureInPicture = null, onMenu = {}, onSpeedPreview = {},
                onSpeed = {}, onMirror = {}, onFit = {}, backgroundPlayback = true,
                onBackgroundPlaybackChange = {}, castContent = {},
            )
        }, height = 360) { nodes ->
            val bar = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "player_control_bar" }
            val panel = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "player_speed_menu" }
            assertTrue(panel.boundsInRoot.bottom <= bar.boundsInRoot.top)
            assertTrue(nodes.any { it.config.getOrNull(SemanticsActions.ScrollBy) != null })
            nodes.filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("×") } == true }
                .forEach { node ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertTrue(!layouts.single().didOverflowHeight)
                }
        }
    }

    @Test fun findModeHidesReaderStyleControlsInShortViewport() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val web = android.webkit.WebView(context)
        val state = BrowserState().apply { showFindBar() }
        try {
            render(720, 1f, {
                BrowserScreen(state, web, {}, {}, {}, {}, {}, {}, {}, {}, 1,
                    onFindQueryChange = {}, onFindNext = {}, onFindPrevious = {}, onFindClose = {},
                    isReaderActive = true)
            }, height = 200) { nodes ->
                assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.contains(context.getString(R.string.reader_font_larger)) == true })
                assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.EditableText) != null })
            }
        } finally { web.destroy() }
    }

    @Test fun findBarReservesSpaceForItsDivider() {
        var height = 0
        render(360, 1f, {
            androidx.compose.foundation.layout.Column {
                androidx.compose.animation.AnimatedVisibility(true,
                    modifier = Modifier.onSizeChanged { height = it.height }) {
                    FindBar("", {}, 0, 0, {}, {}, {})
                }
            }
        }) { assertEquals(57, height) }
    }

    @Test fun settingsSelectionOnlyExistsForTwoPaneNavigation() {
        render(360, 1f, {
            androidx.compose.foundation.layout.Column {
                com.mybrowser.ui.settings.SettingsItem("Navigation", "", {})
                com.mybrowser.ui.settings.SettingsItem("Current", "", {}, selected = true)
                com.mybrowser.ui.settings.SettingsItem("Other", "", {}, selected = false)
            }
        }) { nodes ->
            val selections = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.Selected) }
            assertEquals(listOf(true, false), selections)
            assertEquals(2, nodes.count { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Tab })
        }
    }

    private fun render(width: Int, scale: Float, content: @Composable () -> Unit, height: Int = 720, check: (List<SemanticsNode>) -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(activity.get())
        try {
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    PureBrowserTheme { content() }
                }
            }
            activity.get().setContentView(view)
            shadowOf(Looper.getMainLooper()).idle()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val root = (view.getChildAt(0) as ViewRootForTest).semanticsOwner.unmergedRootSemanticsNode
            fun children(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::children)
            check(children(root))
        } finally {
            view.disposeComposition()
            activity.pause().stop().destroy()
        }
    }
}
