package com.mybrowser.data

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import com.mybrowser.R
import com.mybrowser.download.CUSTOM_DIRECTORY_LABEL
import com.mybrowser.download.DownloadSettings
import com.mybrowser.download.localizeDownloadDirectory
import com.mybrowser.security.SecurityChecker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LocalizationTest {
    private fun resources(language: String): Resources {
        val context = RuntimeEnvironment.getApplication()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return context.createConfigurationContext(configuration).resources
    }

    @Test fun promptsAndActionsFollowTheRequestedLanguage() {
        assertEquals("Cancel", resources("en").getString(R.string.action_cancel))
        assertEquals("取消", resources("zh-CN").getString(R.string.action_cancel))
        assertEquals("Enter a valid URL", resources("en").getString(R.string.bookmark_invalid_url))
        assertEquals("请输入有效的网址", resources("zh-TW").getString(R.string.bookmark_invalid_url))
    }

    @Test fun unsupportedLanguagesUseTheEnglishFallback() {
        assertEquals("Cancel", resources("fr-FR").getString(R.string.action_cancel))
        assertEquals("Pure Browser", resources("de-DE").getString(R.string.app_name))
    }

    @Test fun formattedHintsKeepNumbersAndLiteralPercentSigns() {
        assertEquals("Brightness 60%", resources("en").getString(R.string.ui_brightness, 60))
        assertEquals("亮度 60%", resources("zh").getString(R.string.ui_brightness, 60))
        assertEquals("Unable to load the page (error -2)", resources("en").getString(R.string.page_load_error_code, -2))
        assertEquals("已清除 2 项，1 个文件无法删除。", resources("zh").getString(R.string.downloads_cleared_partial, 2, 1))
    }

    @Test fun persistedDownloadLabelsDoNotPinTheOldLanguage() {
        val settings = DownloadSettings()
        assertEquals("System Downloads folder", settings.displayDestinationLabel(resources("en")))
        assertEquals("系统下载目录", settings.displayDestinationLabel(resources("zh")))
        assertEquals("System Downloads folder", localizeDownloadDirectory(resources("en"), "系统下载目录"))
        assertEquals("自定义目录", localizeDownloadDirectory(resources("zh"), CUSTOM_DIRECTORY_LABEL))
        assertEquals("我的文件", localizeDownloadDirectory(resources("en"), "我的文件"))
    }

    @Test fun securityWarningsCanBeRenderedAgainAfterALanguageChange() {
        val warning = SecurityChecker.getSecurityInfo("ftp://example.com/file")
        val id = requireNotNull(warning.warningRes)
        assertEquals("Insecure protocol: ftp", resources("en").getString(id, *warning.warningArgs.toTypedArray()))
        assertEquals("不安全的协议: ftp", resources("zh").getString(id, *warning.warningArgs.toTypedArray()))
    }
}
