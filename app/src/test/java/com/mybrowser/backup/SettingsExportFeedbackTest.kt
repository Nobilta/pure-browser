package com.mybrowser.backup

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ExportFeedbackActivity : ComponentActivity() {
    internal lateinit var feedback: SettingsExportFeedback
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedback = SettingsExportFeedback(this)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class SettingsExportFeedbackTest {
    private fun roundTrip(export: ExportResult?, dismissed: Boolean = false): SettingsExportSummary? {
        val controller = Robolectric.buildActivity(ExportFeedbackActivity::class.java).setup()
        export?.let { controller.get().feedback.show(it) }
        if (dismissed) controller.get().feedback.dismiss()
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val parcel = Parcel.obtain()
        try {
            saved.writeToParcel(parcel, 0)
            assertTrue("Only the small summary belongs in saved state", parcel.dataSize() < 16_384)
        } finally { parcel.recycle() }
        val recreated = Robolectric.buildActivity(ExportFeedbackActivity::class.java)
            .create(saved).start().resume().visible()
        return try { recreated.get().feedback.result }
        finally { recreated.pause().stop().destroy() }
    }

    private fun export() = ExportResult(
        SettingsBackup(SettingsBackup.FORMAT_ID, 1, "test", "now", BackupSettings(
            bookmarks = BackupBookmarks(entries = listOf(BackupBookmark("a".repeat(100_000), "https://example.com"))),
        )), 17, 28, listOf("sites", "filtering"),
    )

    @Test fun preservesAllWarningsAcrossActivityRecreationWithoutSavingTheBackup() {
        assertEquals(SettingsExportSummary(17, 28, listOf("sites", "filtering")), roundTrip(export()))
    }

    @Test fun noResultOrDismissedResultDoesNotReappear() {
        assertNull(roundTrip(null))
        assertNull(roundTrip(export(), dismissed = true))
    }
}
