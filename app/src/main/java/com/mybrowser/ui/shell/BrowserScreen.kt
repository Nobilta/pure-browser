package com.mybrowser.ui.shell

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
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
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchSuggestionProvider
import com.mybrowser.ui.home.HomeDashboard

/**
 * The browser chrome: omnibar on top, page in the middle, navigation at the bottom.
 */
import kotlinx.coroutines.delay

/** How long a load must last before its progress bar is worth showing. */
private const val PROGRESS_REVEAL_DELAY_MS = 150L

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
    /** The persistent immersive preference; video fullscreen overrides it on screen. */
    isBrowserFullscreen: Boolean = false,
    onRevealChrome: () -> Unit = {},
    onContinueFullscreen: () -> Unit = {},
    onExitBrowserFullscreen: () -> Unit = {},
    onSwitchTab: (Int) -> Unit = {},
    onRetryPage: () -> Unit = onReloadOrStop,
    onSecurityClick: () -> Unit = {},
    onScanQr: () -> Unit = {},
    bookmarkManager: BookmarkManager? = null,
    historyManager: HistoryManager? = null,
    searchEngine: SearchEngine = SearchEngine.BAIDU,
    onlineSuggestionsEnabled: Boolean = false,
    suggestionProvider: SearchSuggestionProvider? = null,
    /** Searches the exact text with the current engine, bypassing URL-vs-search guessing. */
    onOmnibarSearch: (String) -> Unit = {},
    // Read by the caller from TabManager's snapshot state; keeping it in the parameter
    // makes title/favicon mutations recompose this screen even though TabState fields are
    // intentionally lightweight mutable records.
    tabRevision: Int = 0,
    certificateError: Boolean = false,
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

    // Immersive mode only owns the screen while no video is fullscreen; video keeps its
    // own controls and hides the floating entry.
    val immersiveActive = isBrowserFullscreen && !isVideoFullscreen
    // Revealed chrome is shown in full: the scroll-collapse flag must not eat it.
    val chromeShown = !isVideoFullscreen && (!isBrowserFullscreen || state.isChromeRevealed)
    val chromeForced = immersiveActive && state.isChromeRevealed
    LaunchedEffect(immersiveActive) { state.onImmersiveFullscreenChanged(immersiveActive) }

    // While immersive, back first leaves the revealed state (closing the find bar if it
    // is up) before the Activity's dispatcher gets to exit fullscreen. Registered before
    // the omnibar handler below, so omnibar editing still wins when both apply.
    BackHandler(enabled = immersiveActive && state.isChromeRevealed && !state.isOmnibarFocused) {
        if (state.isFindBarVisible) onFindClose() else state.hideChrome()
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
                    isHomePage = showHomeDashboard,
                    onScanQr = { finishEditing(); onScanQr() },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
    }

    // A fast load must not flash a bar, and a stalled one must show something: wait a beat
    // before appearing, then stay indeterminate until Chromium reports its first percentage.
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (state.isLoading) {
            delay(PROGRESS_REVEAL_DELAY_MS)
            showProgress = true
        } else showProgress = false
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // --- top bar ---
        // Keep the underlying WebView nonzero in a small PiP window. Reserving
        // address/toolbar height there makes Chromium exit HTML fullscreen.
        AnimatedVisibility(
            visible = chromeShown,
            enter = expandVertically(animationSpec = BrowserMotion.chromeShow, expandFrom = Alignment.Top),
            exit = shrinkVertically(animationSpec = BrowserMotion.chromeHide, shrinkTowards = Alignment.Top),
        ) {
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
            AnimatedVisibility(
                visible = isIncognito,
                enter = fadeIn(animationSpec = BrowserMotion.localEnter),
                exit = fadeOut(animationSpec = BrowserMotion.localExit),
            ) {
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
                        text = stringResource(if (hasPrivateIsolation) R.string.incognito_badge else R.string.private_shared),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = !state.isToolbarHidden || showHomeDashboard || chromeForced,
                enter = expandVertically(animationSpec = BrowserMotion.chromeShow, expandFrom = Alignment.Top),
                exit = shrinkVertically(animationSpec = BrowserMotion.chromeHide, shrinkTowards = Alignment.Top),
            ) {
                if (!bottomAddressBar) addressBar()
            }

            // Absent rather than empty outside 1..99: a zero-width or full bar sitting
            // under the omnibar reads as a stalled page.
            AnimatedVisibility(
                visible = showProgress && state.isProgressVisible,
                enter = fadeIn(animationSpec = BrowserMotion.localEnter),
                exit = fadeOut(animationSpec = BrowserMotion.localExit),
            ) {
                if (state.progress <= 0) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                ) else LinearProgressIndicator(
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

            PageOverlay(visible = showHomeDashboard) {
                HomeDashboard(
                    shortcuts = homeShortcuts,
                    onOpen = onOpenHomeShortcut,
                    onSave = onSaveHomeShortcut,
                    onRemove = onRemoveHomeShortcut,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // PageOverlay keeps these five inside the page box: a plain AnimatedVisibility here
            // resolves to the ColumnScope overload of the outer layout instead.
            // The cast FAB folds into the browser-actions entry while immersive, so the
            // corner never stacks two floating buttons.
            PageOverlay(
                visible = mediaCount > 0 && !showHomeDashboard && !state.isOmnibarFocused && !immersiveActive,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(16.dp),
                enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                    scaleIn(animationSpec = BrowserMotion.localEnter, initialScale = 0.8f),
                exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                    scaleOut(animationSpec = BrowserMotion.localExit, targetScale = 0.85f),
            ) {
                FloatingActionButton(
                    onClick = onCast,
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

            // The immersive entry point: one low-distraction native button that brings
            // the omnibar and toolbar back. 56dp FAB with a 24dp icon satisfies the
            // touch-target floor, and the insets padding keeps it clear of cutouts.
            PageOverlay(
                visible = immersiveActive && !state.isChromeRevealed,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(20.dp),
                enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                    scaleIn(animationSpec = BrowserMotion.localEnter, initialScale = 0.8f),
                exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                    scaleOut(animationSpec = BrowserMotion.localExit, targetScale = 0.85f),
            ) {
                FloatingActionButton(
                    onClick = onRevealChrome,
                    // Incognito stays visually loud on the entry point, like the top bar tint.
                    containerColor = if (isIncognito) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isIncognito) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("browser_fullscreen_fab"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_fullscreen),
                        contentDescription = stringResource(R.string.browser_fullscreen_actions),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            PageOverlay(
                visible = state.pageFailure != null,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                state.pageFailure?.let { PageRecovery(it, onRetryPage, onHome, onSecurityClick) }
            }

            PageOverlay(visible = state.isOmnibarFocused && !isVideoFullscreen) {
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
                            searchEngine = searchEngine,
                            onlineSuggestionsEnabled = onlineSuggestionsEnabled,
                            suggestionProvider = suggestionProvider,
                            isIncognito = isIncognito,
                            hasComposingText = state.omnibarValue.composition != null,
                            onFillSuggestion = { text ->
                                state.onOmnibarValueChange(androidx.compose.ui.text.input.TextFieldValue(
                                    text, androidx.compose.ui.text.TextRange(text.length)))
                                keyboard?.show()
                            },
                            onSuggestionAction = { action ->
                                when (action) {
                                    is SuggestionAction.Visit -> onNavigate(action.url)
                                    is SuggestionAction.Search -> onOmnibarSearch(action.query)
                                }
                                finishEditing()
                            },
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
        AnimatedVisibility(
            visible = chromeShown,
            enter = expandVertically(animationSpec = BrowserMotion.chromeShow, expandFrom = Alignment.Bottom),
            exit = shrinkVertically(animationSpec = BrowserMotion.chromeHide, shrinkTowards = Alignment.Bottom),
        ) {
        Column {
            // Both placements use one spec, each towards its own screen edge, so scrolling reads the
            // same whichever position the address bar is set to.
            if (bottomAddressBar) AnimatedVisibility(
                visible = !state.isToolbarHidden || showHomeDashboard || chromeForced,
                enter = expandVertically(animationSpec = BrowserMotion.chromeShow, expandFrom = Alignment.Bottom),
                exit = shrinkVertically(animationSpec = BrowserMotion.chromeHide, shrinkTowards = Alignment.Bottom),
            ) { addressBar() }
            // Find bar appears above the toolbar
            AnimatedVisibility(
                visible = state.isFindBarVisible,
                enter = fadeIn(animationSpec = BrowserMotion.localEnter) +
                    slideInVertically(animationSpec = BrowserMotion.overlayEnter) { it },
                exit = fadeOut(animationSpec = BrowserMotion.localExit) +
                    slideOutVertically(animationSpec = BrowserMotion.overlayExit) { it },
            ) {
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

            // Revealed immersive chrome carries its own way back: collapse without
            // touching the preference, or turn it off for good. No auto-hide timer.
            // Placed above the toolbar, which owns the bottom safe-area padding.
            if (chromeForced) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        finishEditing()
                        // Same exit order as the back gesture: the find bar goes first.
                        if (state.isFindBarVisible) onFindClose() else state.hideChrome()
                        onContinueFullscreen()
                    },
                    modifier = Modifier.testTag("browser_fullscreen_continue"),
                ) {
                    Text(stringResource(R.string.browser_fullscreen_continue))
                }
                TextButton(
                    onClick = { finishEditing(); onExitBrowserFullscreen() },
                    modifier = Modifier.testTag("browser_fullscreen_exit"),
                ) {
                    Text(stringResource(R.string.browser_fullscreen_exit))
                }
            }

            BrowserToolbar(
                canGoBack = state.canGoBack || tabCount > 1,
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
}

/**
 * A surface the page area stacks over the WebView.
 *
 * Named so the call site keeps the box scope it needs for alignment: inside a `Box` nested in a
 * `Column`, a plain `AnimatedVisibility` resolves to the column's overload instead.
 */
@Composable
private fun PageOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = BrowserMotion.localEnter),
    exit: ExitTransition = fadeOut(animationSpec = BrowserMotion.localExit),
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = enter, exit = exit) { content() }
}
