package com.mybrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue

/** The route stack owns navigation; a presentation owns callbacks from one mounted sheet. */
internal class BrowserSheetNavigation {
    enum class Destination {
        MENU, CAST, TABS, BOOKMARKS, HISTORY, DOWNLOADS, SETTINGS,
        SITE_SETTINGS, DEVELOPER_TOOLS,
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

/** One dialog survives route changes; only the visible page and its Back callback are mounted. */
@Composable
internal fun BrowserSheetHost(
    navigation: BrowserSheetNavigation,
    content: @Composable (BrowserSheetNavigation.Presentation) -> Unit,
) {
    val savedState = rememberSaveableStateHolder()
    val knownKeys = remember { mutableSetOf<Long>() }
    val activeKeys = navigation.routes.map { it.key }.toSet()
    SideEffect {
        (knownKeys - activeKeys).forEach(savedState::removeState)
        knownKeys.clear()
        knownKeys.addAll(activeKeys)
    }
    navigation.current?.let { owner ->
        BrowserSheetWindow(onDismissRequest = { navigation.current?.let(navigation::back) }) {
            // Replace content atomically inside the existing window. Stable keys
            // restore parent state without hidden dialogs or stale Back callbacks.
            key(owner.route.key) {
                savedState.SaveableStateProvider(owner.route.key) { content(owner) }
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
