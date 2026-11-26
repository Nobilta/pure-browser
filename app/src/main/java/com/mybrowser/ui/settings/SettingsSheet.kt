package com.mybrowser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.mybrowser.data.BrowserPreferences
import com.mybrowser.data.ThemeMode
import com.mybrowser.data.VideoPreferences
import com.mybrowser.core.PlaybackSpeed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.composed
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.DownloadSettings
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngine
import com.mybrowser.search.SearchEngineKind
import com.mybrowser.ui.components.BrowserTextField
import androidx.compose.foundation.selection.selectableGroup
import kotlin.math.roundToInt
import com.mybrowser.ui.shell.BrowserAlertDialog
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.browserSheetDismiss
import com.mybrowser.ui.shell.BrowserFullscreenSheet
import com.mybrowser.ui.shell.BrowserMotion
import com.mybrowser.ui.shell.TextInputDialog
import com.mybrowser.ui.shell.browserSheetInsets
import com.mybrowser.ui.shell.localizedResources
import kotlinx.coroutines.launch

/** Category navigation stays mounted while pickers and filter lists are open. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsSheet(
    childOpen: Boolean = false,
    restoreLastSession: Boolean,
    onRestoreLastSessionChange: (Boolean) -> Unit,
    isDefaultBrowser: Boolean,
    onSetDefaultBrowser: () -> Unit,
    currentSearchEngine: SearchEngine,
    availableSearchEngines: List<SearchEngine>,
    onSearchEngineChange: (SearchEngine) -> Unit,
    onAddCustomSearchEngine: (String, String, String?) -> Boolean,
    onRemoveCustomSearchEngine: (SearchEngine) -> Unit,
    currentImageSearchEngine: SearchEngine,
    availableImageSearchEngines: List<SearchEngine>,
    onImageSearchEngineChange: (SearchEngine) -> Unit,
    onAddCustomImageSearchEngine: (String, String) -> Boolean,
    onRemoveCustomImageSearchEngine: (SearchEngine) -> Unit,
    currentHomepageMode: HomepageMode,
    currentHomepage: String,
    onHomepageModeChange: (HomepageMode) -> Unit,
    onHomepageChange: (String) -> Boolean,
    onManageCustomFilters: () -> Unit,
    onManageUserScripts: () -> Unit,
    onManageSites: () -> Unit,
    onOpenTranslation: () -> Unit = {},
    downloadSettings: DownloadSettings,
    onUseSystemDownloadDirectory: () -> Unit,
    onChooseDownloadDirectory: () -> Unit,
    onDownloadThreadCountChange: (Int) -> Unit,
    onDownloadNetworkChange: (Boolean) -> Unit = {},
    onDownloadConcurrencyChange: (Int) -> Unit = {},
    onDownloadSpeedLimitChange: (Int) -> Unit = {},
    downloadNotificationsEnabled: Boolean = true,
    onDownloadNotifications: () -> Unit = {},
    preferences: BrowserPreferences,
    onPreferencesChange: (BrowserPreferences) -> Unit,
    isIncognito: Boolean = false,
    onIncognitoChange: (Boolean) -> Unit = {},
    isFilterEnabled: Boolean,
    onFilterEnabledChange: (Boolean) -> Unit,
    onClearData: () -> Unit,
    onExportSettings: () -> Unit = {},
    onImportSettings: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val textResources = localizedResources()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showUpdate by remember { mutableStateOf(false) }
    if (showUpdate) UpdatePanel { showUpdate = false }
    var highlightTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerGeneration by rememberSaveable { mutableLongStateOf(0L) }
    val openPicker: (String) -> Unit = { pickerGeneration++; picker = it }
    val selected = SettingsCategory.entries.firstOrNull { it.name == sectionName }
    // The list and a category are two panes of this one route. The sheet carries whichever pane the
    // route shows first; every later switch is a page change inside the route, so no operation is
    // animated twice.
    val previousPane = remember { mutableStateOf(selected) }
    val paneChanged = previousPane.value != selected
    SideEffect { previousPane.value = selected }
    // Each pane keeps its own scroll position and field state: leaving a category and coming back
    // returns to where the reader was, instead of to the top of the list.
    val paneState = rememberSaveableStateHolder()
    val back = {
        if (!childOpen && picker == null && !showUpdate) {
            if (selected != null) {
                highlightTitle = null
                sectionName = null
            } else {
                onDismiss()
            }
        }
    }
    val updateVideo = { value: VideoPreferences -> onPreferencesChange(preferences.copy(video = value)) }

    BrowserFullscreenSheet(onDismissRequest = back) {
    CompositionLocalProvider(LocalSettingHighlight provides highlightTitle) {
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        .testTag(if (selected == null) "settings_root" else "settings_detail")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets())) {
            // Material 3's expanded window class; below it the list owns the width and a
            // category is a pushed page.
            val twoPane = maxWidth >= 840.dp && selected != null
            Row(Modifier.fillMaxSize()) {
                if (twoPane || selected == null) {
                    Box(if (twoPane) Modifier.width(260.dp).selectableGroup() else Modifier.fillMaxSize()) {
                        paneState.SaveableStateProvider("settings-root") {
                        SettingsPage(textResources.getString(R.string.menu_settings), back,
                            modifier = Modifier.settingsPaneChange(paneKey = null,
                                animate = paneChanged && !twoPane, direction = -1f, travel = !twoPane)) {
                            LibrarySearchField(
                                query = searchQuery,
                                label = textResources.getString(R.string.settings_search),
                                onQueryChange = { searchQuery = it.take(128) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            if (searchQuery.isNotBlank()) {
                                val locale = textResources.configuration.locales[0]
                                val results = remember(searchQuery, locale) { SETTINGS_SEARCH.filter { (title, category) ->
                                    textResources.getString(title).contains(searchQuery, true) || textResources.getString(category.titleRes).contains(searchQuery, true)
                                } }
                                if (results.isEmpty()) Text(
                                    textResources.getString(R.string.library_no_results),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 40.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                results.forEach { (title, category) ->
                                    val label = textResources.getString(title)
                                    SettingsItem(label, textResources.getString(category.titleRes), {
                                        highlightTitle = label; sectionName = category.name
                                        if (title == R.string.context_search_image) openPicker("search")
                                    }, category.icon)
                                }
                            }
                            if (searchQuery.isBlank()) SETTINGS_GROUPS.forEach { (group, categories) ->
                                SettingsGroupCard(title = textResources.getString(group.titleRes)) {
                                    categories.forEachIndexed { index, category ->
                                        if (index > 0) SettingsDivider()
                                        val summary = when (category) {
                                            SettingsCategory.BROWSING -> "${currentSearchEngine.displayName(textResources)} · " +
                                                if (restoreLastSession) textResources.getString(R.string.ui_restore_previous_pages) else textResources.getString(R.string.ui_open_homepage_on_startup)
                                            SettingsCategory.APPEARANCE -> textResources.getString(preferences.theme.labelRes)
                                            // Root rows stay short; the category page owns the switch
                                            // and the state that used to be repeated here.
                                            SettingsCategory.PRIVACY -> textResources.getString(R.string.menu_incognito_short)
                                            SettingsCategory.EXTENSIONS -> textResources.getString(R.string.ui_ad_filtering)
                                            SettingsCategory.DOWNLOADS -> textResources.getString(R.string.ui_connections_2ad3d3, downloadSettings.displayDestinationLabel(textResources), downloadSettings.threadCount)
                                            SettingsCategory.VIDEO -> if (preferences.video.enhancedControls) textResources.getString(R.string.ui_fullscreen_gestures_hold_for, PlaybackSpeed.label(preferences.video.boostRate)) else textResources.getString(R.string.ui_use_webpage_controls)
                                            SettingsCategory.ABOUT -> textResources.getString(R.string.ui_version_information)
                                        }
                                        SettingsItem(
                                            title = textResources.getString(category.titleRes),
                                            subtitle = summary,
                                            onClick = {
                                                highlightTitle = null
                                                sectionName = category.name
                                            },
                                            iconRes = category.icon,
                                            selected = if (twoPane) category == selected else null,
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                }
                if (twoPane) VerticalDivider()
                if (selected != null) {
                    val category = selected
                    Box(Modifier.weight(1f)) {
                        paneState.SaveableStateProvider(category.name) {
                        key(category) {
                            SettingsPage(textResources.getString(category.titleRes), back,
                                modifier = Modifier.settingsPaneChange(paneKey = category,
                                    animate = paneChanged, direction = 1f, travel = !twoPane)) {
                                when (category) {
                                    SettingsCategory.BROWSING -> {
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_startup)) {
                                            SettingsItem(textResources.getString(R.string.cd_home), if (currentHomepageMode == HomepageMode.NAVIGATION) textResources.getString(R.string.ui_shortcuts_homepage) else currentHomepage,
                                                { openPicker("home") }, R.drawable.ic_ui_home)
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_restore_pages_on_startup),
                                                summary = if (restoreLastSession) textResources.getString(R.string.ui_next_launch_restore_regular_tabs) else textResources.getString(R.string.ui_next_launch_homepage),
                                                checked = restoreLastSession,
                                                onChange = onRestoreLastSessionChange,
                                            )
                                        }
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_search_and_system)) {
                                            SettingsItem(textResources.getString(R.string.ui_search_engine), currentSearchEngine.displayName(textResources), { openPicker("search") }, R.drawable.ic_ui_search)
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_online_search_suggestions),
                                                summary = textResources.getString(R.string.ui_online_search_suggestions_summary),
                                                checked = preferences.searchSuggestionsEnabled,
                                                onChange = { onPreferencesChange(preferences.copy(searchSuggestionsEnabled = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_private_search_suggestions),
                                                summary = textResources.getString(R.string.ui_private_search_suggestions_summary),
                                                checked = preferences.privateSearchSuggestionsEnabled,
                                                onChange = { onPreferencesChange(preferences.copy(privateSearchSuggestionsEnabled = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.ui_default_browser), if (isDefaultBrowser) textResources.getString(R.string.ui_set_as_default) else textResources.getString(R.string.ui_not_set_as_default),
                                                onSetDefaultBrowser, R.drawable.ic_ui_monitor)
                                        }
                                    }
                                    SettingsCategory.APPEARANCE -> {
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_appearance)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.browser_fullscreen),
                                                summary = textResources.getString(R.string.browser_fullscreen_summary),
                                                checked = preferences.browserFullscreenEnabled,
                                                onChange = { onPreferencesChange(preferences.copy(browserFullscreenEnabled = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.bottom_address_bar),
                                                summary = textResources.getString(R.string.preference_immediate),
                                                checked = preferences.bottomAddressBar,
                                                onChange = { onPreferencesChange(preferences.copy(bottomAddressBar = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.swipe_tab_switch),
                                                summary = textResources.getString(R.string.swipe_tab_switch_summary),
                                                checked = preferences.swipeTabs,
                                                onChange = { onPreferencesChange(preferences.copy(swipeTabs = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.ui_app_theme), textResources.getString(preferences.theme.labelRes), { openPicker("theme") }, R.drawable.ic_ui_palette)
                                        }
                                    }
                                    SettingsCategory.PRIVACY -> {
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_incognito_browsing)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_incognito_browsing),
                                                summary = textResources.getString(R.string.ui_incognito_persistent_summary),
                                                checked = isIncognito,
                                                onChange = onIncognitoChange,
                                            )
                                        }
                                        SettingsGroupCard(title = textResources.getString(R.string.settings_sites_and_data)) {
                                            SettingsItem(textResources.getString(R.string.site_settings), textResources.getString(R.string.site_settings_summary), onManageSites, R.drawable.ic_ui_tools)
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.menu_clear_data), textResources.getString(R.string.ui_confirm_before_clearing_cache_cookies_and_history), onClearData, R.drawable.ic_ui_delete)
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.settings_export), textResources.getString(R.string.settings_export_summary), onExportSettings, R.drawable.ic_ui_share)
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.settings_import), textResources.getString(R.string.settings_import_summary), onImportSettings, R.drawable.ic_ui_download)
                                        }
                                    }
                                    SettingsCategory.EXTENSIONS -> {
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_content_filtering)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_ad_filtering),
                                                summary = textResources.getString(R.string.ui_block_requests_matching_built_in_and_custom_filter),
                                                checked = isFilterEnabled,
                                                onChange = onFilterEnabledChange,
                                            )
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.ui_custom_ad_filter_rules), textResources.getString(R.string.ui_add_and_manage_filter_lists), onManageCustomFilters, R.drawable.ic_ui_shield)
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.script_title), textResources.getString(R.string.script_settings_summary), onManageUserScripts, R.drawable.ic_ui_script)
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.translate_settings_title), textResources.getString(R.string.translate_settings_summary),
                                                onOpenTranslation, R.drawable.ic_ui_translate)
                                        }
                                    }
                                    SettingsCategory.VIDEO -> {
                                        val video = preferences.video
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_video_playback)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.background_playback),
                                                summary = textResources.getString(R.string.background_playback_summary),
                                                checked = video.backgroundPlayback,
                                                onChange = { updateVideo(video.copy(backgroundPlayback = it)) },
                                            )
                                        }
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_fullscreen_experience)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_enhanced_video_controls),
                                                summary = textResources.getString(R.string.ui_playback_seeking_speed_and_lock_controls_switch_to),
                                                checked = video.enhancedControls,
                                                onChange = { updateVideo(video.copy(enhancedControls = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_rotate_landscape_videos_automatically),
                                                summary = textResources.getString(R.string.ui_portrait_videos_stay_upright_orientation_is_restored_on),
                                                checked = video.landscapeFullscreen,
                                                onChange = { updateVideo(video.copy(landscapeFullscreen = it)) },
                                            )
                                        }
                                        SettingsGroupCard(title = textResources.getString(R.string.ui_fullscreen_gestures)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_brightness_and_volume_gestures),
                                                summary = textResources.getString(R.string.ui_swipe_vertically_on_the_left_for_brightness_and),
                                                checked = video.verticalGestures,
                                                onChange = { updateVideo(video.copy(verticalGestures = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_swipe_to_seek),
                                                summary = textResources.getString(R.string.ui_swipe_horizontally_to_preview_a_position_release_to),
                                                checked = video.horizontalSeek,
                                                onChange = { updateVideo(video.copy(horizontalSeek = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_hold_for_temporary_speed_boost),
                                                summary = textResources.getString(R.string.ui_release_to_restore_the_previous_playback_speed),
                                                checked = video.holdToBoost,
                                                onChange = { updateVideo(video.copy(holdToBoost = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.ui_hold_speed), PlaybackSpeed.label(video.boostRate), { openPicker("boost") }, R.drawable.ic_ui_media)
                                            SettingsNote(textResources.getString(R.string.ui_tap_to_show_or_hide_controls_double_tap))
                                        }
                                        SettingsGroupCard(title = textResources.getString(R.string.menu_playback_speed)) {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_remember_playback_speed),
                                                summary = textResources.getString(R.string.ui_use_the_selected_speed_for_future_videos_temporary),
                                                checked = video.rememberSpeed,
                                                onChange = { updateVideo(video.copy(rememberSpeed = it)) },
                                            )
                                            SettingsDivider()
                                            SettingsItem(textResources.getString(R.string.ui_default_playback_speed), PlaybackSpeed.label(video.preferredSpeed), { openPicker("speed") }, R.drawable.ic_ui_media)
                                        }
                                    }
                                    SettingsCategory.ABOUT -> {
                                        val context = LocalContext.current
                                        val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                                        Text(textResources.getString(R.string.ui_pure_browser), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp))
                                        SettingsNote(textResources.getString(R.string.settings_version_line, version))
                                        SettingsGroupCard {
                                            SettingsItem(textResources.getString(R.string.update_check),
                                                "GitHub · Nobilta/pure-browser", { showUpdate = true }, R.drawable.ic_ui_download)
                                            SettingsDivider()
                                            SettingsToggle(
                                                title = textResources.getString(R.string.update_auto_check),
                                                summary = textResources.getString(R.string.update_auto_check_summary),
                                                checked = preferences.autoCheckUpdates,
                                                onChange = { onPreferencesChange(preferences.copy(autoCheckUpdates = it)) },
                                            )
                                        }
                                    }
                                    SettingsCategory.DOWNLOADS -> {
                                        SettingsGroupCard {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.download_unmetered),
                                                checked = downloadSettings.unmeteredOnly,
                                                onChange = onDownloadNetworkChange,
                                            )
                                            SettingsDivider()
                                            DownloadNotificationItem(downloadNotificationsEnabled, onDownloadNotifications)
                                        }
                                        DownloadSettingsSection(downloadSettings, onUseSystemDownloadDirectory, onChooseDownloadDirectory,
                                            onDownloadThreadCountChange, onDownloadConcurrencyChange, { openPicker("downloadSpeed") })
                                    }
                                }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (picker != null) {
        val owner = pickerGeneration
        val dismissPicker = { if (pickerGeneration == owner) picker = null }
        key(owner) {
            BrowserBottomSheet(onDismissRequest = dismissPicker) {
                val closePicker = browserSheetDismiss()
                Box(Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
                    when (picker) {
                        "search" -> SearchEngineSettings(currentSearchEngine, availableSearchEngines,
                            onSearchEngineChange, onAddCustomSearchEngine, onRemoveCustomSearchEngine,
                            currentImageSearchEngine, availableImageSearchEngines, onImageSearchEngineChange,
                            onAddCustomImageSearchEngine, onRemoveCustomImageSearchEngine, closePicker)
                        "home" -> HomepageSettings(currentHomepageMode, currentHomepage,
                            onHomepageModeChange, onHomepageChange, closePicker)
                        else -> {
                            val title = when (picker) {
                                "theme" -> R.string.ui_app_theme
                                "downloadSpeed" -> R.string.download_speed_limit
                                "boost" -> R.string.ui_hold_speed
                                else -> R.string.ui_default_playback_speed
                            }
                            Column(Modifier.fillMaxWidth()) {
                                com.mybrowser.ui.shell.BrowserSheetHeader(textResources.getString(title), onBack = closePicker)
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                                    SettingsGroupCard {
                                        Column(Modifier.selectableGroup()) {
                                            when (picker) {
                                                "downloadSpeed" -> listOf(0, 128, 512, 1024, 5120, 10240).forEach { limit ->
                                                    SettingsRadioItem(speedLimitLabel(textResources, limit), selected = downloadSettings.speedLimitKib == limit) {
                                                        onDownloadSpeedLimitChange(limit)
                                                        closePicker()
                                                    }
                                                }
                                                "theme" -> ThemeMode.entries.forEach { mode ->
                                                    SettingsRadioItem(textResources.getString(mode.labelRes), selected = preferences.theme == mode) {
                                                        onPreferencesChange(preferences.copy(theme = mode))
                                                        closePicker()
                                                    }
                                                }
                                                else -> {
                                                    val boost = picker == "boost"
                                                    val choices = if (boost) PlaybackSpeed.BOOST_OPTIONS else PlaybackSpeed.OPTIONS
                                                    choices.forEach { speed ->
                                                        SettingsRadioItem(PlaybackSpeed.label(speed), selected = speed ==
                                                            (if (boost) preferences.video.boostRate else preferences.video.preferredSpeed)) {
                                                            updateVideo(if (boost) preferences.video.copy(boostRate = speed)
                                                                else preferences.video.copy(preferredSpeed = speed, rememberSpeed = true))
                                                            closePicker()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    }
}

/**
 * The settings root, one entry per question a user can actually have.
 *
 * The categories are cut by intent, not by what shares an implementation: privacy holds what
 * changes how the browser behaves towards the user and the sites they visit, while extensions
 * holds the things the user installs and configures (filters, scripts, per-site rules) plus the
 * browsing-data controls. "Privacy and filtering" used to be one entry holding both, so it was
 * split; site settings had a second door in the menu, and browsing data had none in the menu
 * taxonomy this sheet shares.
 */
private enum class SettingsCategoryGroup(val titleRes: Int) {
    BROWSER(R.string.settings_group_browser),
    PRIVACY_CONTENT_MEDIA(R.string.settings_group_privacy_content_media),
    OTHER(R.string.settings_group_other),
}

private enum class SettingsCategory(val titleRes: Int, val icon: Int, val group: SettingsCategoryGroup) {
    BROWSING(R.string.settings_general, R.drawable.ic_ui_home, SettingsCategoryGroup.BROWSER),
    APPEARANCE(R.string.ui_appearance, R.drawable.ic_ui_palette, SettingsCategoryGroup.BROWSER),
    PRIVACY(R.string.settings_privacy_data, R.drawable.ic_ui_shield, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    EXTENSIONS(R.string.settings_content_tools, R.drawable.ic_ui_layers, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    DOWNLOADS(R.string.ui_download_settings, R.drawable.ic_ui_download, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    VIDEO(R.string.settings_media, R.drawable.ic_ui_media, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    ABOUT(R.string.settings_about_updates, R.drawable.ic_ui_info, SettingsCategoryGroup.OTHER),
}

/**
 * The download notification entry: real system state, plain styling, no red dots or
 * banners. Only this row ever starts the permission request or opens the system pages.
 */
@Composable
private fun DownloadNotificationItem(enabled: Boolean, onAction: () -> Unit) {
    val textResources = localizedResources()
    Column(
        Modifier.fillMaxWidth()
            .highlightSetting(textResources.getString(R.string.download_notifications))
            .clickable(onClick = onAction)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = textResources.getString(R.string.download_notifications),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = textResources.getString(
                    if (enabled) R.string.download_notifications_state_on else R.string.download_notifications_state_off,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = textResources.getString(
                if (enabled) R.string.download_notifications_summary_on
                else R.string.download_notifications_summary_off,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(textResources.getString(
                if (enabled) R.string.download_notifications_manage else R.string.download_notifications_enable,
            ), modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A page change inside the settings route. The sheet carries the pane a route shows first, so only
 * a later switch animates: the narrow layout pushes and pops between the list and a category, while
 * the wide layout keeps its navigation column and swaps the detail without travel.
 */
private fun Modifier.settingsPaneChange(
    paneKey: Any?,
    animate: Boolean,
    direction: Float,
    travel: Boolean,
): Modifier = composed {
    val position = remember(paneKey) { Animatable(if (animate) 0f else 1f) }
    val fade = remember(paneKey) { Animatable(if (animate) 0f else 1f) }
    val positionSpec = BrowserMotion.pageArrive<Float>()
    val fadeSpec = BrowserMotion.contentCross<Float>()
    LaunchedEffect(paneKey) {
        if (animate) {
            launch { fade.animateTo(1f, fadeSpec) }
            position.animateTo(1f, positionSpec)
        }
    }
    val distance = with(LocalDensity.current) { BrowserMotion.PAGE_CHANGE_DISTANCE.toPx() }
    graphicsLayer {
        alpha = fade.value
        if (travel) translationX = (1f - position.value) * distance * direction
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEngineSettings(
    current: SearchEngine,
    available: List<SearchEngine>,
    onChange: (SearchEngine) -> Unit,
    onAddCustom: (String, String, String?) -> Boolean,
    onRemoveCustom: (SearchEngine) -> Unit,
    currentImage: SearchEngine,
    availableImages: List<SearchEngine>,
    onImageChange: (SearchEngine) -> Unit,
    onAddCustomImage: (String, String) -> Boolean,
    onRemoveCustomImage: (SearchEngine) -> Unit,
    onBack: () -> Unit,
) {
    var adding by remember { mutableStateOf<SearchEngineKind?>(null) }
    SettingsPage(title = stringResource(R.string.ui_search_engine), onBack = onBack) {
        SearchEngineGroup(stringResource(R.string.search_engine_web), current, available,
            onChange, onRemoveCustom, { adding = SearchEngineKind.WEB })
        SearchEngineGroup(stringResource(R.string.context_search_image), currentImage, availableImages,
            onImageChange, onRemoveCustomImage, { adding = SearchEngineKind.IMAGE })
    }
    adding?.let { kind ->
        CustomSearchEngineDialog(
            kind = kind,
            onConfirm = { name, template, suggestTemplate ->
                val saved = if (kind == SearchEngineKind.IMAGE) onAddCustomImage(name, template)
                    else onAddCustom(name, template, suggestTemplate)
                if (saved) adding = null
            },
            onDismiss = { adding = null },
        )
    }
}

@Composable
private fun SearchEngineGroup(
    title: String,
    current: SearchEngine,
    available: List<SearchEngine>,
    onChange: (SearchEngine) -> Unit,
    onRemove: (SearchEngine) -> Unit,
    onAdd: () -> Unit,
) {
    val resources = localizedResources()
    SettingsGroupCard(title = title, modifier = Modifier.highlightSettingTarget(title)) {
        Column(Modifier.selectableGroup()) {
            available.forEachIndexed { index, engine ->
                if (index > 0) SettingsDivider()
                SettingsRadioItem(
                    title = engine.displayName(resources), selected = engine.id == current.id,
                    trailing = if (engine.isCustom) {
                        {
                            IconButton(onClick = { onRemove(engine) }) {
                                Icon(painterResource(R.drawable.ic_ui_delete),
                                    contentDescription = stringResource(R.string.search_engine_delete))
                            }
                        }
                    } else null,
                ) { onChange(engine) }
            }
        }
        SettingsDivider()
        SettingsItem(stringResource(R.string.search_engine_add_custom), "", onAdd, R.drawable.ic_ui_add)
    }
}

@Composable
private fun CustomSearchEngineDialog(
    kind: SearchEngineKind,
    onConfirm: (name: String, template: String, suggestTemplate: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var suggestTemplate by remember { mutableStateOf("") }
    val canSave = name.trim().isNotEmpty() && template.trim().isNotEmpty()

    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == SearchEngineKind.IMAGE)
            R.string.image_search_engine_add else R.string.search_engine_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BrowserTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.search_engine_name),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                BrowserTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = stringResource(R.string.search_engine_url),
                    placeholder = stringResource(if (kind == SearchEngineKind.IMAGE)
                        R.string.image_search_engine_url_hint else R.string.search_engine_url_hint),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = if (kind == SearchEngineKind.IMAGE) ImeAction.Done else ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind == SearchEngineKind.WEB) BrowserTextField(
                    value = suggestTemplate,
                    onValueChange = { suggestTemplate = it },
                    label = stringResource(R.string.search_engine_suggest_url),
                    placeholder = stringResource(R.string.search_engine_suggest_url_hint),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), template.trim(), suggestTemplate.trim().ifEmpty { null }) },
                enabled = canSave,
            ) { Text(stringResource(R.string.bookmark_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun DownloadSettingsSection(
    settings: DownloadSettings,
    onUseSystemDirectory: () -> Unit,
    onChooseDirectory: () -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onConcurrencyChange: (Int) -> Unit,
    onOpenSpeedPicker: () -> Unit,
) {
    val textResources = localizedResources()
    var threadDraft by remember(settings.threadCount) {
        mutableFloatStateOf(settings.threadCount.toFloat())
    }
    var concurrencyDraft by remember(settings.maxConcurrentDownloads) {
        mutableFloatStateOf(settings.maxConcurrentDownloads.toFloat())
    }

    // The group heading carries the text the settings search indexes under
    // `ui_download_settings`, so a search hit jumps here and highlights the card.
    SettingsGroupCard(
        title = textResources.getString(R.string.ui_download_settings),
        modifier = Modifier.highlightSettingTarget(textResources.getString(R.string.ui_download_settings)).selectableGroup(),
    ) {
        Text(
            text = textResources.getString(R.string.ui_save_location),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        SettingsRadioItem(
            title = textResources.getString(R.string.ui_system_downloads_folder),
            subtitle = textResources.getString(R.string.ui_save_to_the_device_downloads_folder),
            selected = settings.destinationMode == DownloadDestinationMode.SYSTEM_DOWNLOADS,
            onClick = onUseSystemDirectory,
        )
        SettingsDivider()
        SettingsRadioItem(
            title = textResources.getString(R.string.ui_custom_folder),
            subtitle = if (settings.customTreeUri == null) {
                textResources.getString(R.string.ui_choose_a_folder_with_write_access)
            } else {
                textResources.getString(R.string.ui_tap_to_change, com.mybrowser.download.localizeDownloadDirectory(
                    textResources, settings.customDirectoryLabel ?: com.mybrowser.download.CUSTOM_DIRECTORY_LABEL,
                ))
            },
            selected = settings.destinationMode == DownloadDestinationMode.CUSTOM_DIRECTORY,
            onClick = onChooseDirectory,
        )
    }

    SettingsGroupCard(
        modifier = Modifier.highlightSettingTarget(textResources.getString(R.string.download_transfer_policy)),
        title = textResources.getString(R.string.download_transfer_policy),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val concurrencyLabel = stringResource(R.string.download_concurrent, concurrencyDraft.roundToInt())
            Text(concurrencyLabel, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.download_concurrent_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = concurrencyDraft, onValueChange = { concurrencyDraft = it },
                onValueChangeFinished = { onConcurrencyChange(concurrencyDraft.roundToInt()) }, valueRange = 1f..5f, steps = 3,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = concurrencyLabel })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1", style = MaterialTheme.typography.bodySmall)
                Text("5", style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingsDivider()
        SettingsItem(
            title = textResources.getString(R.string.download_speed_limit),
            subtitle = speedLimitLabel(textResources, settings.speedLimitKib),
            onClick = onOpenSpeedPicker,
        )
        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = textResources.getString(R.string.ui_connections_818c76, threadDraft.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = textResources.getString(R.string.ui_choose_1_16_connections_servers_without_range_support),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = threadDraft,
                onValueChange = { threadDraft = it },
                onValueChangeFinished = {
                    onThreadCountChange(threadDraft.roundToInt())
                },
                valueRange = 1f..16f,
                steps = 14,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = textResources.getString(R.string.ui_download_connections)
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1", style = MaterialTheme.typography.bodySmall)
                Text("16", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomepageSettings(
    currentMode: HomepageMode,
    current: String,
    onModeChange: (HomepageMode) -> Unit,
    onChange: (String) -> Boolean,
    onBack: () -> Unit,
) {
    val textResources = localizedResources()
    var showDialog by remember { mutableStateOf(false) }

    SettingsPage(title = textResources.getString(R.string.cd_home), onBack = onBack) {
        SettingsGroupCard(title = textResources.getString(R.string.ui_homepage_style), modifier = Modifier.selectableGroup()) {
            SettingsRadioItem(
                title = textResources.getString(R.string.ui_shortcuts_homepage),
                subtitle = textResources.getString(R.string.ui_show_shortcuts_to_favorite_sites),
                selected = currentMode == HomepageMode.NAVIGATION,
                onClick = { onModeChange(HomepageMode.NAVIGATION) },
            )
            SettingsDivider()
            SettingsRadioItem(
                title = textResources.getString(R.string.ui_custom_url),
                subtitle = textResources.getString(R.string.ui_open_this_page, current),
                selected = currentMode == HomepageMode.FIXED_URL,
                onClick = { onModeChange(HomepageMode.FIXED_URL) },
            )
            if (currentMode == HomepageMode.FIXED_URL) {
                SettingsDivider()
                SettingsItem(
                    title = textResources.getString(R.string.ui_edit_homepage_url),
                    subtitle = current,
                    onClick = { showDialog = true },
                )
            }
        }
    }

    if (showDialog) {
        TextInputDialog(
            title = textResources.getString(R.string.ui_set_homepage),
            label = textResources.getString(R.string.ui_homepage_url),
            initialValue = current,
            onConfirm = { newUrl ->
                if (onChange(newUrl)) {
                    showDialog = false
                }
            },
            onDismiss = { showDialog = false },
        )
    }
}

private fun speedLimitLabel(resources: android.content.res.Resources, limit: Int): String = when {
    limit == 0 -> resources.getString(R.string.download_speed_unlimited)
    limit % 1024 == 0 -> resources.getString(R.string.download_speed_value_mib, limit / 1024)
    else -> resources.getString(R.string.download_speed_value, limit)
}

private val SETTINGS_GROUPS = SettingsCategory.entries.groupBy { it.group }

private val SETTINGS_SEARCH = listOf(
    R.string.ui_incognito_browsing to SettingsCategory.PRIVACY,
    R.string.background_playback to SettingsCategory.VIDEO,
    R.string.cd_home to SettingsCategory.BROWSING,
    R.string.ui_restore_pages_on_startup to SettingsCategory.BROWSING,
    R.string.ui_search_engine to SettingsCategory.BROWSING,
    R.string.context_search_image to SettingsCategory.BROWSING,
    R.string.ui_online_search_suggestions to SettingsCategory.BROWSING,
    R.string.ui_private_search_suggestions to SettingsCategory.BROWSING,
    R.string.ui_default_browser to SettingsCategory.BROWSING,
    R.string.ui_app_theme to SettingsCategory.APPEARANCE,
    R.string.browser_fullscreen to SettingsCategory.APPEARANCE,
    R.string.bottom_address_bar to SettingsCategory.APPEARANCE,
    R.string.swipe_tab_switch to SettingsCategory.APPEARANCE,
    R.string.site_settings to SettingsCategory.PRIVACY,
    R.string.ui_ad_filtering to SettingsCategory.EXTENSIONS,
    R.string.ui_custom_ad_filter_rules to SettingsCategory.EXTENSIONS,
    R.string.script_title to SettingsCategory.EXTENSIONS,
    R.string.translate_settings_title to SettingsCategory.EXTENSIONS,
    R.string.menu_clear_data to SettingsCategory.PRIVACY,
    R.string.settings_export to SettingsCategory.PRIVACY,
    R.string.settings_import to SettingsCategory.PRIVACY,
    R.string.download_unmetered to SettingsCategory.DOWNLOADS,
    R.string.download_notifications to SettingsCategory.DOWNLOADS,
    R.string.ui_download_settings to SettingsCategory.DOWNLOADS,
    R.string.download_transfer_policy to SettingsCategory.DOWNLOADS,
    R.string.download_speed_limit to SettingsCategory.DOWNLOADS,
    R.string.ui_brightness_and_volume_gestures to SettingsCategory.VIDEO,
    R.string.ui_swipe_to_seek to SettingsCategory.VIDEO,
    R.string.ui_enhanced_video_controls to SettingsCategory.VIDEO,
    R.string.ui_rotate_landscape_videos_automatically to SettingsCategory.VIDEO,
    R.string.ui_hold_for_temporary_speed_boost to SettingsCategory.VIDEO,
    R.string.ui_hold_speed to SettingsCategory.VIDEO,
    R.string.ui_remember_playback_speed to SettingsCategory.VIDEO,
    R.string.ui_default_playback_speed to SettingsCategory.VIDEO,
    R.string.update_check to SettingsCategory.ABOUT,
    R.string.update_auto_check to SettingsCategory.ABOUT,
)
