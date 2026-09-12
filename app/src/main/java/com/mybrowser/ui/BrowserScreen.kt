package com.mybrowser.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mybrowser.R
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager
import com.mybrowser.home.HomeShortcut
import com.mybrowser.home.ShortcutIconChange
import com.mybrowser.home.ShortcutSaveResult

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
    isVideoFullscreen: Boolean = false,
    mediaCount: Int = 0,
    onCast: () -> Unit = {},
    showHomeDashboard: Boolean = false,
    homeShortcuts: List<HomeShortcut> = emptyList(),
    onOpenHomeShortcut: (HomeShortcut) -> Unit = {},
    onSaveHomeShortcut: suspend (String, String, String, ShortcutIconChange) -> ShortcutSaveResult = { _, _, _, _ -> ShortcutSaveResult.FAILED },
    onRemoveHomeShortcut: suspend (HomeShortcut) -> Boolean = { false },
    onFindQueryChange: (String) -> Unit,
    onFindNext: () -> Unit,
    onFindPrevious: () -> Unit,
    onFindClose: () -> Unit,
    isIncognito: Boolean = false,
    hasPrivateIsolation: Boolean = false,
    onNewTab: () -> Unit = onTabs,
    bottomAddressBar: Boolean = false,
    swipeTabs: Boolean = false,
    onSwitchTab: (Int) -> Unit = {},
    onRetryPage: () -> Unit = onReloadOrStop,
    onSecurityClick: () -> Unit = {},
    bookmarkManager: BookmarkManager? = null,
    historyManager: HistoryManager? = null,
    // Read by the caller from TabManager's snapshot state; keeping it in the parameter
    // makes title/favicon mutations recompose this screen even though TabState fields are
    // intentionally lightweight mutable records.
    tabRevision: Int = 0,
    certificateError: Boolean = false,
    snackbarHostState: SnackbarHostState? = null,
) {
    @Suppress("UNUSED_VARIABLE")
    val observedTabRevision = tabRevision
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishEditing() {
        focusManager.clearFocus()
        state.onOmnibarFocusChange(false)
        keyboard?.hide()
    }

    // Only the omnibar-focused case is handled here, because clearing focus needs a
    // FocusManager and that only exists inside the composition. Everything else the back
    // gesture has to do — leaving fullscreen video, walking WebView history, confirming
    // exit — stays on the Activity's dispatcher callback.
    //
    // Precedence works out without coordination: this callback registers later than the
    // Activity's, and OnBackPressedDispatcher runs callbacks in reverse registration
    // order, so an enabled BackHandler wins. Disabled, it is transparent.
    BackHandler(enabled = state.isOmnibarFocused) {
        finishEditing()
    }

    val addressBar: @Composable () -> Unit = {
                Omnibar(
                    value = state.omnibarValue,
                    onValueChange = state::onOmnibarValueChange,
                    onFocusChange = state::onOmnibarFocusChange,
                    onNavigate = onNavigate,
                    onClear = state::clearOmnibar,
                    onRefresh = onReloadOrStop,
                    isFocused = state.isOmnibarFocused,
                    isLoading = state.isLoading,
                    certificateError = certificateError,
                    currentUrl = state.currentUrl,
                    displayTitle = state.displayTitle,
                    onSecurityClick = onSecurityClick,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- top bar ---
        // Keep the underlying WebView nonzero in a small PiP window. Reserving
        // address/toolbar height there makes Chromium exit HTML fullscreen.
        if (!isVideoFullscreen) Column(
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
                        text = stringResource(if (hasPrivateIsolation) R.string.private_isolated else R.string.private_shared),
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
                if (!bottomAddressBar) addressBar()
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
                    onSave = onSaveHomeShortcut,
                    onRemove = onRemoveHomeShortcut,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Inline playback remains owned by the website. This action opens cast
            // selection without adding a second set of playback controls.
            if (mediaCount > 0 && !showHomeDashboard && !state.isOmnibarFocused) {
                FloatingActionButton(
                    onClick = onCast,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    BadgedBox(badge = { if (mediaCount > 1) Badge { Text(mediaCount.toString()) } }) {
                        Icon(
                            painterResource(R.drawable.ic_cast),
                            contentDescription = stringResource(R.string.cast_detected_sources, mediaCount),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            state.pageFailure?.let { PageRecovery(it, onRetryPage, onHome, onSecurityClick) }

            snackbarHostState?.let {
                SnackbarHost(it, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }

            if (state.isOmnibarFocused && !isVideoFullscreen) {
                // Share the editor's window: Popup outside-touch callbacks also
                // receive keyboard and clear-button taps, cancelling their input.
                BoxWithConstraints(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = ::finishEditing,
                )) {
                    if (bookmarkManager != null && state.omnibarValue.text.isNotBlank() && maxHeight >= 56.dp) {
                        SmartSuggestions(
                            query = state.omnibarValue.text,
                            bookmarkManager = bookmarkManager,
                            historyManager = if (isIncognito) null else historyManager,
                            onFillSuggestion = { text ->
                                state.onOmnibarValueChange(androidx.compose.ui.text.input.TextFieldValue(
                                    text, androidx.compose.ui.text.TextRange(text.length)))
                                keyboard?.show()
                            },
                            onSuggestionClick = { text -> onNavigate(text); finishEditing() },
                            maxHeight = (maxHeight - 8.dp).coerceAtMost(400.dp),
                            modifier = Modifier.align(if (bottomAddressBar) Alignment.BottomCenter else Alignment.TopCenter)
                                .padding(vertical = 4.dp),
                        )
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
        if (!isVideoFullscreen) Column {
            if (bottomAddressBar) AnimatedVisibility(visible = !state.isToolbarHidden || showHomeDashboard) { addressBar() }
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
                onBack = { if (state.isOmnibarFocused) finishEditing() else onBack() },
                onBackLongPress = { finishEditing(); onBackLongPress() },
                onForward = { finishEditing(); onForward() },
                onHome = { finishEditing(); onHome() },
                onTabs = { finishEditing(); onTabs() },
                onNewTab = { finishEditing(); onNewTab() },
                swipeTabs = swipeTabs,
                onSwitchTab = { finishEditing(); onSwitchTab(it) },
                tabCount = tabCount,
                onMenu = { finishEditing(); onMenu() },
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
