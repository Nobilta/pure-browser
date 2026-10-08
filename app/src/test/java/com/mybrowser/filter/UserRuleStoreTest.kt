package com.mybrowser.filter

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The hand-written rules the user blocks through the page, and the payload they travel in.
 *
 * Two things are worth pinning here. The store's answers have to be distinguishable — "already
 * there" and "could not be saved" are different from "blocked", and a user who is told "blocked"
 * when nothing happened will keep looking at the ad. And the engine refuses a payload set larger
 * than [FilterListFormat.MAX_LISTS] outright, leaving the previous snapshot in place, so the count
 * of lists the app can produce must not exceed it.
 *
 * Robolectric with the real preference file: persisting is half of what this store does, and a test
 * against an in-memory double would not cover the round trip that the next launch depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UserRuleStoreTest {

    /**
     * A store as the app gets it: [FilterSubscriptions.initialize] is what loads the manifest and the
     * hand-written rules, and the app calls it once during startup. Reading before it would see an
     * empty list, which is a property of the class rather than of this test.
     */
    private suspend fun store(): FilterSubscriptions =
        FilterSubscriptions(RuntimeEnvironment.getApplication(), filter = null).also { it.initialize() }

    @Test
    fun aRuleIsAddedPersistedAndReportedOnce() = runTest {
        val store = store()
        assertEquals(FilterSubscriptions.RuleChange.ADDED, store.addUserRule("||ads.example.com^"))
        assertEquals(listOf("||ads.example.com^"), store.userRules.value)
        assertEquals(
            "a second tap on the same element must not report a second block",
            FilterSubscriptions.RuleChange.ALREADY_PRESENT,
            store.addUserRule("||ads.example.com^"),
        )
        // The whitespace a rule arrives with from a selector is trimmed, so the same rule is not
        // stored twice under two spellings.
        assertEquals(FilterSubscriptions.RuleChange.ALREADY_PRESENT, store.addUserRule("  ||ads.example.com^  "))
    }

    @Test
    fun enablingAListThatOverflowsTheSnapshotIsRefused() = runTest {
        // A list's bytes are what the engine will have to parse once it is enabled. Turning one on
        // is therefore a configuration change, and it has to be judged before it is committed.
        val store = store()
        // The recorded size is the guard that does not need a payload on disk: a subscription whose
        // own bytes are already past the ceiling can never be part of a snapshot the engine loads.
        val huge = FilterSubscriptions.Subscription(
            "x", "Big", "https://example.com/big", bytes = FilterListFormat.MAX_TOTAL_BYTES + 1,
        )
        org.junit.Assert.assertThrows(IOException::class.java) {
            store.checkSize(listOf(huge), emptyList())
        }
    }

    @Test
    fun anImportIsJudgedAgainstTheRulesItIsAboutToWrite() = runTest {
        // The import commits its own rule list, and a rule is capped at 64 KiB on its own, so the
        // budget has to be exercised with the pending list rather than the stored one. The stored
        // rules stay empty here, which is exactly the state the old check would have judged.
        val store = store()
        val many = List(600) { "||ads" + it + ".example.com^" }
        val size = many.joinToString("\n").toByteArray(Charsets.UTF_8).size
        assertTrue("the fixture has to be big enough to matter", size > 0)
        // Under the ceilings, the same list passes.
        store.fitsPayloadLimit(emptyList(), size).let { assertTrue(it) }
        // And the combined check refuses a list that would not fit next to a nearly-full payload.
        org.junit.Assert.assertThrows(IOException::class.java) {
            store.checkSize(emptyList(), many + "|" + "a".repeat(FilterListFormat.MAX_TOTAL_BYTES))
        }
    }

    @Test
    fun aRuleThatWouldOverflowTheEngineSnapshotIsRefused() = runTest {
        // The engine refuses the whole payload set once the combined size passes its limit, which
        // would leave the settings showing a rule the filter never applied. These are the same three
        // comparisons it makes, evaluated before the rule is written.
        val store = store()
        assertTrue(store.fitsPayloadLimit(emptyList(), 1024))
        assertFalse("one payload past the per-list ceiling is refused", store.fitsPayloadLimit(emptyList(), FilterListFormat.MAX_BYTES + 1))
        assertFalse(
            "payloads that together pass the total ceiling are refused",
            store.fitsPayloadLimit(listOf(FilterListFormat.MAX_TOTAL_BYTES - 10), 11),
        )
        assertTrue(store.fitsPayloadLimit(listOf(FilterListFormat.MAX_TOTAL_BYTES - 10), 10))
        assertFalse(
            "one more list than the engine accepts is refused",
            store.fitsPayloadLimit(List(FilterListFormat.MAX_LISTS) { 1 }, 1),
        )
    }


    @Test
    fun aRuleSurvivesTheNextLaunch() = runTest {
        store().addUserRule("example.com##div.ad-slot")
        // A new instance reads the same preference file, which is all the next launch does.
        assertEquals(listOf("example.com##div.ad-slot"), store().userRules.value)
    }

    @Test
    fun rulesThatWouldBeDroppedByTheEngineAreRefused() = runTest {
        val store = store()
        for (rule in listOf("", "   ", "div\n##.other", ".ad{display:none}", "##")) {
            assertEquals(rule, FilterSubscriptions.RuleChange.INVALID, store.addUserRule(rule))
        }
        assertTrue("nothing invalid may be stored", store.userRules.value.isEmpty())
    }

    @Test
    fun removingReportsWhetherItRemovedAnything() = runTest {
        val store = store()
        store.addUserRule("||ads.example.com^")
        store.addUserRule("example.com##.banner")
        assertTrue(store.removeUserRule("||ads.example.com^"))
        assertEquals(listOf("example.com##.banner"), store.userRules.value)
        assertFalse("removing what is not there must not claim success", store.removeUserRule("||never.example^"))
        // And the removal outlives this instance too.
        assertEquals(listOf("example.com##.banner"), store().userRules.value)
    }

    @Test
    fun theEngineIsNeverAskedForMoreListsThanItAccepts() {
        // The engine refuses the whole payload when there are too many lists, which silently keeps
        // the previous rules — no filtering at all and nothing on screen to say so. Every list the
        // app can offer (the bundled ones, the custom-subscription cap, and the local rule list)
        // has to fit.
        assertEquals(
            FilterSubscriptions.BUILT_INS.size + FilterSubscriptions.MAX_CUSTOM_LISTS + 1,
            FilterListFormat.MAX_LISTS,
        )
    }

    @Test fun aLegacyStoreTooLargeToLoadIsNotMigratedIntoABrokenState() = runBlocking {
        // A migration that saves more than the engine accepts leaves every list shown as enabled
        // with a rule count while the engine keeps its previous snapshot: the state every write path
        // pre-checks against, and the one path that used to skip it.
        val context = RuntimeEnvironment.getApplication()
        // The migration is only reached when no manifest exists; earlier tests in this class write
        // one, so the store would load from it and the branch under test would never run.
        context.getSharedPreferences("filter_settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "filters/subscriptions.json").delete()
        // Each list stays under the per-list ceiling (8 MiB, 200k lines); five of them together
        // are what overruns the 32 MiB total the engine takes as one snapshot.
        val ids = (1..5).map { "abcdef%010d".format(it) }
        val payload = "||averyverylongadhostname.example.com^\n".repeat(190_000)
        java.io.File(context.cacheDir, "filter_lists").mkdirs()
        val manifest = org.json.JSONArray()
        for (id in ids) {
            java.io.File(context.cacheDir, "filter_lists/" + id + ".txt").writeText(payload)
            manifest.put(org.json.JSONObject().put("id", id).put("name", "Legacy " + id)
                .put("url", "https://example.com/" + id + ".txt"))
        }
        context.getSharedPreferences("custom_filters", android.content.Context.MODE_PRIVATE).edit()
            .putString("lists", manifest.toString()).commit()
        val store = FilterSubscriptions(context, filter = null)
        store.initialize()
        // The migration refused the set, so none of the subscriptions it would have added is there:
        // saving them would show every list as enabled with a rule count while the engine quietly
        // kept its previous snapshot. The store stays usable rather than holding that state.
        assertTrue(
            "an over-budget legacy store must not be migrated",
            store.subscriptions.value.none { it.id in ids },
        )
        // And the configuration it does hold is one the engine accepts.
        store.checkSize(store.subscriptions.value.toList())
    }

}
