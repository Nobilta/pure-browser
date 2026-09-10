package com.mybrowser.ui

import com.mybrowser.ui.BrowserSheetNavigation.Destination.*
import org.junit.Assert.*
import org.junit.Test

class BrowserSheetNavigationTest {
    @Test fun settingsReturnsToMenuBeforeClosingTheBrowserOverlay() {
        val navigation = BrowserSheetNavigation()
        navigation.open(MENU)
        val menu = navigation.current!!
        assertTrue(navigation.push(menu, SETTINGS))
        assertTrue(navigation.back(navigation.current!!))
        assertEquals(MENU, navigation.current!!.destination)
        assertEquals(menu.route.key, navigation.current!!.route.key)
        assertTrue(navigation.back(navigation.current!!))
        assertNull(navigation.current)
    }

    @Test fun cancelledMenuAnimationCannotDismissTheChildOrRemountedParent() {
        val navigation = BrowserSheetNavigation()
        navigation.open(MENU)
        val oldMenu = navigation.current!!
        navigation.push(oldMenu, SETTINGS)
        assertFalse(navigation.back(oldMenu))
        assertEquals(SETTINGS, navigation.current!!.destination)
        val settings = navigation.current!!
        navigation.back(settings)
        assertFalse(navigation.back(oldMenu))
        assertFalse(navigation.back(settings))
        assertEquals(MENU, navigation.current!!.destination)
    }

    @Test fun delayedCallbackCannotDismissANewMenuOrNavigateFromAnOldOne() {
        val navigation = BrowserSheetNavigation()
        navigation.open(MENU)
        val oldMenu = navigation.current!!
        navigation.back(oldMenu)
        navigation.open(MENU)
        assertFalse(navigation.back(oldMenu))
        assertFalse(navigation.push(oldMenu, SETTINGS))
        assertFalse(navigation.close(oldMenu))
        assertEquals(MENU, navigation.current!!.destination)
    }

    @Test fun repeatedClicksOpenOnlyOneChildAndRepeatedDismissPopsOnlyOnce() {
        val navigation = BrowserSheetNavigation()
        navigation.open(MENU)
        val menu = navigation.current!!
        assertTrue(navigation.push(menu, BOOKMARKS))
        assertFalse(navigation.push(menu, HISTORY))
        val bookmarks = navigation.current!!
        assertTrue(navigation.back(bookmarks))
        assertFalse(navigation.back(bookmarks))
        assertEquals(listOf(MENU), navigation.routes.map { it.destination })
    }

    @Test fun closingAnActionLeavesTheWholeHierarchyAndInvalidatesAllCallbacks() {
        val navigation = BrowserSheetNavigation()
        navigation.open(MENU)
        val menu = navigation.current!!
        navigation.push(menu, HISTORY)
        val history = navigation.current!!
        assertTrue(navigation.close(history))
        assertNull(navigation.current)
        assertFalse(navigation.back(history))
        assertFalse(navigation.push(menu, SETTINGS))
    }

    @Test fun directEntryClosesToBrowserWhileSettingsToolsReturnThroughTheirParents() {
        val navigation = BrowserSheetNavigation()
        navigation.open(CAST)
        navigation.back(navigation.current!!)
        assertNull(navigation.current)
        navigation.open(MENU)
        navigation.push(navigation.current!!, SETTINGS)
        val settingsKey = navigation.current!!.route.key
        navigation.push(navigation.current!!, DEVELOPER_TOOLS)
        navigation.back(navigation.current!!)
        assertEquals(SETTINGS, navigation.current!!.destination)
        assertEquals(settingsKey, navigation.current!!.route.key)
        navigation.back(navigation.current!!)
        assertEquals(MENU, navigation.current!!.destination)
    }

    @Test fun recreationRetainsRouteStateKeysButNotOldCallbackOwnership() {
        val navigation = BrowserSheetNavigation()
        repeat(5) { navigation.open(MENU); navigation.clear() }
        navigation.open(MENU)
        navigation.push(navigation.current!!, SETTINGS)
        val oldOwner = navigation.current!!
        val recreated = BrowserSheetNavigation()
        recreated.restore(navigation.routes)
        assertEquals(navigation.routes, recreated.routes)
        assertFalse(recreated.back(oldOwner))
        recreated.push(recreated.current!!, DEVELOPER_TOOLS)
        assertEquals(3, recreated.routes.map { it.key }.distinct().size)
    }

    @Test fun invalidRestoredRoutesDoNotTrapTheUserInADuplicateStack() {
        val navigation = BrowserSheetNavigation()
        navigation.restore(listOf(BrowserSheetNavigation.Route(1, MENU), BrowserSheetNavigation.Route(1, SETTINGS)))
        assertNull(navigation.current)
        navigation.restore(listOf(BrowserSheetNavigation.Route(-1, MENU)))
        assertNull(navigation.current)
    }

    @Test fun rapidRoundTripsKeepOnlyTheCurrentMenuAndRejectAllRetiredCallbacks() {
        val navigation = BrowserSheetNavigation()
        navigation.open(MENU)
        repeat(100) {
            val menu = navigation.current!!
            navigation.push(menu, SETTINGS)
            val settings = navigation.current!!
            navigation.back(settings)
            assertFalse(navigation.back(menu))
            assertFalse(navigation.back(settings))
            assertEquals(1, navigation.routes.size)
        }
        navigation.back(navigation.current!!)
        assertNull(navigation.current)
    }

    @Test fun openingOrClosingAMenuDisarmsAnEarlierExitAttempt() {
        val exit = BrowserExitConfirmation(2_000)
        assertFalse(exit.onBack(10_000))
        exit.reset()
        assertFalse(exit.onBack(10_300))
        assertTrue(exit.onBack(10_700))
        exit.reset()
        assertFalse(exit.onBack(20_000))
        assertFalse(exit.onBack(22_000))
        assertTrue(exit.onBack(22_001))
    }
}
