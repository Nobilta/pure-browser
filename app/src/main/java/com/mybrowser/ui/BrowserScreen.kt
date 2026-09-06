package com.mybrowser.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mybrowser.R
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager
import com.mybrowser.home.HomeShortcut
import com.mybrowser.media.PlaybackSpeed

/**
 * The browser chrome: omnibar on top, page in the middle, navigation at the bottom.
 */
@Composable
fun BrowserScreen(
    state: BrowserState,
    webView: WebView,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onBackLongPress: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onReloadOrStop: () -> Unit,
    onMenu: () -> Unit,
    onTabs: () -> Unit,
    tabCount: Int,
    showHomeDashboard: Boolean = false,
    homeShortcuts: List<HomeShortcut> = emptyList(),
    onOpenHomeShortcut: (HomeShortcut) -> Unit = {},
    onRemoveHomeShortcut: (HomeShortcut) -> Unit = {},
    /** Number of castable media candidates discovered on the current page. */
    mediaCount: Int = 0,
    /** True while an HTML5 video is available, including when it is paused. */
    hasVideo: Boolean = false,
    /** Actual rate reported by the strongest playing video. */
    playbackSpeed: Float = PlaybackSpeed.DEFAULT,
    /** Opens the speed picker for the active video. */
    onPlaybackSpeed: () -> Unit = {},
    /** Opens the media/device picker from the floating cast affordance. */
    onCast: () -> Unit = {},
    onFindQueryChange: (String) -> Unit,
    onFindNext: () -> Unit,
    onFindPrevious: () -> Unit,
    onFindClose: () -> Unit,
    isIncognito: Boolean = false,
    onSecurityClick: () -> Unit = {},
    bookmarkManager: BookmarkManager? = null,
    historyManager: HistoryManager? = null,
    // Read by the caller from TabManager's snapshot state; keeping it in the parameter
    // makes title/favicon mutations recompose this screen even though TabState fields are
    // intentionally lightweight mutable records.
    tabRevision: Int = 0,
) {
    @Suppress("UNUSED_VARIABLE")
    val observedTabRevision = tabRevision
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Only the omnibar-focused case is handled here, because clearing focus needs a
    // FocusManager and that only exists inside the composition. Everything else the back
    // gesture has to do — leaving fullscreen video, walking WebView history, confirming
    // exit — stays on the Activity's dispatcher callback.
    //
    // Precedence works out without coordination: this callback registers later than the
    // Activity's, and OnBackPressedDispatcher runs callbacks in reverse registration
    // order, so an enabled BackHandler wins. Disabled, it is transparent.
    BackHandler(enabled = state.isOmnibarFocused) {
        focusManager.clearFocus()
        state.onOmnibarFocusChange(false)
        keyboard?.hide()
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- top bar ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Tinted in incognito. A mode this consequential should be visible without
                // opening a menu, and the surface tint is what X and Via both lean on.
                .background(
                    if (isIncognito) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surfaceContainer,
                )
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                ),
        ) {
            if (isIncognito) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_incognito),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.incognito_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = !state.isToolbarHidden || showHomeDashboard,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Omnibar(
                    value = state.omnibarValue,
                    onValueChange = state::onOmnibarValueChange,
                    onFocusChange = state::onOmnibarFocusChange,
                    onNavigate = onNavigate,
                    onClear = state::clearOmnibar,
                    onRefresh = onReloadOrStop,
                    isFocused = state.isOmnibarFocused,
                    isLoading = state.isLoading,
                    securityLevel = state.securityLevel,
                    currentUrl = state.currentUrl,
                    displayTitle = state.displayTitle,
                    onSecurityClick = onSecurityClick,
                    bookmarkManager = bookmarkManager,
                    historyManager = historyManager,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // Absent rather than empty outside 1..99: a zero-width or full bar sitting
            // under the omnibar reads as a stalled page.
            if (state.isProgressVisible) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    // MD3 1.3+ draws a track gap and a stop dot by default. Both are for
                    // a determinate task indicator; on a page-load bar spanning the full
                    // window they read as artefacts, so both are off.
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }

        // --- page ---
        //
        // AndroidView owns only a stable empty holder; the WebView is attached into it by
        // the update block. Handing the WebView to `factory` directly would be the obvious
        // move and is a trap: on instance replacement (renderer death) Compose can create
        // the new node before disposing the old one, and the WebView would still have a
        // parent, which throws. A holder that never changes identity cannot hit that.
        //
        // This also leaves WebViewPool untouched — it still hands out detached instances
        // and takes them back, exactly as the View implementation used it.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { context -> FrameLayout(context) },
                modifier = Modifier.fillMaxSize(),
                update = { holder ->
                    if (holder.getChildAt(0) !== webView) {
                        holder.removeAllViews()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        holder.addView(
                            webView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                },
            )

            if (showHomeDashboard) {
                HomeDashboard(
                    shortcuts = homeShortcuts,
                    onOpen = onOpenHomeShortcut,
                    onRemove = onRemoveHomeShortcut,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Media actions stay one tap away while a video is active. Speed is tied to
            // the playing element; casting is available whenever a usable stream exists.
            if (!showHomeDashboard && (hasVideo || mediaCount > 0)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (hasVideo) {
                        val speedLabel = PlaybackSpeed.label(playbackSpeed)
                        val speedDescription = stringResource(
                            R.string.cd_playback_speed,
                            speedLabel,
                        )
                        SmallFloatingActionButton(
                            onClick = onPlaybackSpeed,
                            modifier = Modifier.semantics {
                                contentDescription = speedDescription
                            },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                text = speedLabel,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (mediaCount > 0) {
                        SmallFloatingActionButton(
                            onClick = onCast,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cast),
                                contentDescription = stringResource(R.string.cd_cast),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }

        // --- bottom bar ---
        //
        // safeDrawing already unions the IME inset with the navigation bar inset, which is
        // what the View implementation hand-rolled as maxOf(bars.bottom, ime.bottom). One
        // inset source instead of two, same result: the bar sits directly on the keyboard
        // when it is up and on the navigation bar when it is not.
        Column {
            // Find bar appears above the toolbar
            if (state.isFindBarVisible) {
                FindBar(
                    query = state.findQuery,
                    onQueryChange = { query ->
                        state.onFindQueryChange(query)
                        onFindQueryChange(query)
                    },
                    matchCount = state.findMatchCount,
                    currentMatch = state.findCurrentMatch,
                    onNext = onFindNext,
                    onPrevious = onFindPrevious,
                    onClose = onFindClose,
                )
            }

            BrowserToolbar(
                canGoBack = state.canGoBack,
                canGoForward = state.canGoForward,
                onBack = onBack,
                onBackLongPress = onBackLongPress,
                onForward = onForward,
                onHome = onHome,
                onTabs = onTabs,
                tabCount = tabCount,
                onMenu = onMenu,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    ),
            )
        }
    }
}
