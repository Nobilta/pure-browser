package com.mybrowser.filter

import android.app.Application
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FilterListFormatTest {
    @Test
    fun `plain HTTP error pages are not filter rules`() {
        for (body in listOf("Service Unavailable", "Access denied", "Not Found", "Forbidden", "ads")) {
            assertThrows(body, IOException::class.java) { FilterListFormat.validate(body) }
        }
        assertThrows(IOException::class.java) {
            FilterListFormat.validate("||ads^" + "a".repeat(16 * 1024))
        }
        assertThrows(IOException::class.java) {
            FilterListFormat.validate("example.com#?#div:-abp-has(.ad)")
        }
    }
    @Test
    fun `validation accepts scoped keyword and wildcard rules`() {
        assertTrue("Use the same native sandbox as the engine integration tests", NativeFilter.isAvailable)
        for (rule in listOf("ads\$script,domain=example.com", "*\$image,domain=example.com", "@@*\$document,domain=example.com")) {
            assertEquals(rule, 1, FilterListFormat.validate(rule))
        }
    }
}
