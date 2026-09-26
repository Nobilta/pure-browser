package com.mybrowser.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** The route stack owns navigation; a presentation owns callbacks from one mounted sheet. */
internal class BrowserSheetNavigation {
    enum class Destination {
        MENU, CAST, TABS, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS,
        SITE_SETTINGS, DEVELOPER_TOOLS, QR_SCANNER,
    }

    data class Route(val key: Long, val destination: Destination)

    // Intentionally uses reference equality. Returning to a parent creates a new
    // presentation even though its saved scroll/category state keeps the same route key.
    class Presentation internal constructor(val route: Route) {
        val destination get() = route.destination
    }

    private data class State(val routes: List<Route>, val current: Presentation?)
    private var state by mutableStateOf(State(emptyList(), null))
    private var nextKey = 0L
    val routes get() = state.routes
    val current get() = state.current

    fun isCurrent(owner: Presentation?) = owner != null && current === owner

    fun open(destination: Destination) {
        if (routes.size == 1 && current?.destination == destination) return
        show(listOf(Route(nextKey++, destination)))
    }

    fun push(owner: Presentation, destination: Destination): Boolean {
        if (!isCurrent(owner) || owner.destination != Destination.MENU ||
            destination == Destination.MENU || routes.size != 1) return false
        show(routes + Route(nextKey++, destination))
        return true
    }

    /** Called by toolbar Back, system Back, outside taps and swipe dismissal alike. */
    fun back(owner: Presentation): Boolean {
        if (!isCurrent(owner)) return false
        show(routes.dropLast(1))
        return true
    }

    /** An action opening a webpage leaves the entire menu hierarchy. */
    fun close(owner: Presentation): Boolean {
        if (!isCurrent(owner)) return false
        clear()
        return true
    }

    fun clear() = show(emptyList())

    fun restore(savedRoutes: List<Route>) {
        // Saved state is local but may come from an older version or malformed Bundle.
        val valid = savedRoutes.size <= 2 &&
            (savedRoutes.size < 2 || (savedRoutes.first().destination == Destination.MENU &&
                savedRoutes.last().destination != Destination.MENU)) &&
            savedRoutes.all { it.key >= 0 && it.key < Long.MAX_VALUE } &&
            savedRoutes.map { it.key }.distinct().size == savedRoutes.size &&
            savedRoutes.map { it.destination }.distinct().size == savedRoutes.size
        val restored = savedRoutes.takeIf { valid }.orEmpty()
        nextKey = (restored.maxOfOrNull { it.key } ?: -1L) + 1
        show(restored)
    }

    private fun show(routes: List<Route>) {
        state = State(routes, routes.lastOrNull()?.let(::Presentation))
    }
}

/**
 * One dialog survives route changes; only the visible page and its Back callback are mounted.
 *
 * The host owns the window's progress, so it also owns the exit: when the stack empties the last
 * page stays composed while the panel travels out, which keeps the closing transition showing a
 * real page instead of an empty window. A route opened mid-exit reverses from the current
 * position — and, because both directions run on springs, from the current velocity as well, so
 * changing your mind about a sheet reads as catching it rather than as restarting it.
 */
@Composable
internal fun BrowserSheetHost(
    navigation: BrowserSheetNavigation,
    content: @Composable (BrowserSheetNavigation.Presentation) -> Unit,
) {
    val savedState = rememberSaveableStateHolder()
    val current = navigation.current

    // The page that is leaving: kept composed until the exit finishes, then dropped.
    val leaving = remember { mutableStateOf<BrowserSheetNavigation.Presentation?>(null) }
    if (current != null) leaving.value = current
    // Whether the last exit has finished, rather than whether one is in progress. The difference is
    // load-bearing: a "closing" flag only becomes true when the effect runs, which is the frame
    // *after* the one that cleared the route — so for that frame there is no page to show, and the
    // window is torn down and immediately rebuilt to run the exit. The rebuilt window draws with
    // the shared progress still near one, which is the sheet blinking back after it has closed: its
    // dim was measured at 0.94 on the last frame before the window went away. This starts true
    // (nothing to show yet) and is cleared when a route opens, so it never depends on a later write.
    var exitFinished by remember { mutableStateOf(true) }
    var measured by remember { mutableStateOf(false) }
    val progress = remember { SheetWindowProgress() }
    LaunchedEffect(current) {
        // Whether this movement is interrupting one that never arrived. Read before anything is
        // started, and synchronously in the body rather than from the cancelled coroutine's
        // `finally`: cancellation is dispatched, so a flag set there can lose the race with the
        // effect that replaces it.
        //
        // A position strictly inside the range means the last movement was cut short, because an
        // animation that finishes lands exactly on its target. Without this a reversal replays the
        // curve from zero and stops the surface dead first, which is the seam the whole mechanism
        // exists to remove.
        val interrupted = progress.visibility.value > 0f && progress.visibility.value < 1f
        val carried = progress.takeCarried() ?: if (interrupted) progress.visibility.velocity else null
        if (current != null) {
            exitFinished = false
            // The dialog window is created on this frame and its surface is measured a frame or two
            // later. Animating straight away spends most of the entrance on an invisible panel and
            // then snaps the remainder, so the travel starts from the measured surface instead.
            measured = false
            snapshotFlow { measured }.first { it }
            progress.visibility.animateTo(
                1f,
                if (carried == null) progress.arriveSpec() else BrowserMotion.resume(),
                initialVelocity = carried ?: 0f,
            )
        } else if (leaving.value != null) {
            try {
                progress.visibility.animateTo(
                    0f,
                    if (carried == null) progress.departSpec() else BrowserMotion.resume(),
                    initialVelocity = carried ?: 0f,
                )
            } finally {
                // The page is gone either way, so the position has to end at zero even when this
                // coroutine is cancelled part-way — which is what happens whenever the host is
                // recomposed out from under it. Left mid-flight, that value is still what the page
                // draws with, so a last frame would show a nearly-open sheet.
                withContext(NonCancellable) { progress.visibility.snapTo(0f) }
                exitFinished = true
                leaving.value = null
            }
        }
    }
    val shown = current ?: leaving.value?.takeIf { !exitFinished }

    // Saved state is released only once nothing is displaying that route any more.
    val knownKeys = remember { mutableSetOf<Long>() }
    val retained = navigation.routes.map { it.key }.toSet() + setOfNotNull(shown?.route?.key)
    SideEffect {
        (knownKeys - retained).forEach(savedState::removeState)
        knownKeys.clear()
        knownKeys.addAll(retained)
    }

    if (shown != null) {
        BrowserSheetWindow(
            progress = progress,
            onDismissRequest = { navigation.current?.let(navigation::back) },
            onMeasured = { measured = true },
        ) {
            // Replace content atomically inside the existing window. Stable keys
            // restore parent state without hidden dialogs or stale Back callbacks.
            // Depth tells the arriving page whether it travels from below (child) or above (parent).
            CompositionLocalProvider(LocalSheetDepth provides navigation.routes.size) {
                key(shown.route.key) {
                    savedState.SaveableStateProvider(shown.route.key) { content(shown) }
                }
            }
        }
    }
}

/** Only consecutive Back presses on the browser itself may confirm an exit. */
internal class BrowserExitConfirmation(private val windowMillis: Long) {
    private var previousPress: Long? = null

    fun reset() { previousPress = null }

    fun onBack(now: Long): Boolean {
        val previous = previousPress
        previousPress = now
        return previous != null && now - previous in 0 until windowMillis
    }
}
