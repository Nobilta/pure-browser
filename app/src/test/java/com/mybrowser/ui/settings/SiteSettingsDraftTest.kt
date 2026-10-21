package com.mybrowser.ui.settings

import com.mybrowser.site.SitePermission
import com.mybrowser.site.SiteSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SiteSettingsDraftTest {
    @Test fun savingADraftKeepsFieldsChangedElsewhereWhileTheSheetWasOpen() {
        val initial = SiteSettings()
        val current = initial.copy(camera = SitePermission.ALLOW, javaScript = false)
        val draft = initial.copy(filtering = false)
        val merged = mergeSiteDraft(initial, draft, current)
        assertFalse(merged.filtering)
        assertEquals(SitePermission.ALLOW, merged.camera)
        assertFalse(merged.javaScript)
    }

    @Test fun aFieldTheUserChangedWinsOverTheCurrentValue() {
        val initial = SiteSettings()
        val current = initial.copy(camera = SitePermission.ALLOW)
        val draft = initial.copy(camera = SitePermission.BLOCK)
        assertEquals(SitePermission.BLOCK, mergeSiteDraft(initial, draft, current).camera)
    }
}
