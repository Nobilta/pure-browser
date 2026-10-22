package com.mybrowser.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.mybrowser.data.BrowserPreferences
import com.mybrowser.data.ThemeMode
import com.mybrowser.data.VideoPreferences
import com.mybrowser.core.PlaybackSpeed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextOverflow
import com.mybrowser.R
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.DownloadSettings
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngine
import kotlin.math.roundToInt
import com.mybrowser.ui.shell.SystemLoginDialog
import com.mybrowser.ui.shell.BrowserBottomSheet
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
    var showSystemLogin by remember { mutableStateOf(false) }
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
        if (!childOpen && picker == null && !showSystemLogin && !showUpdate) {
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
    if (showSystemLogin) SystemLoginDialog { showSystemLogin = false }
    CompositionLocalProvider(LocalSettingHighlight provides highlightTitle) {
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        .testTag(if (selected == null) "settings_root" else "settings_detail")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets())) {
            val twoPane = maxWidth >= 720.dp && selected != null
            Row(Modifier.fillMaxSize()) {
                if (twoPane || selected == null) {
                    Box(if (twoPane) Modifier.width(260.dp) else Modifier.fillMaxSize()) {
                        paneState.SaveableStateProvider("settings-root") {
                        SettingsPage(textResources.getString(R.string.menu_settings), back,
                            modifier = Modifier.settingsPaneChange(paneKey = null,
                                animate = paneChanged && !twoPane, direction = -1f, travel = !twoPane)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it.take(128) },
                                placeholder = { Text(textResources.getString(R.string.settings_search)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                                singleLine = true,
                                shape = CircleShape,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            if (searchQuery.isNotBlank()) {
                                SETTINGS_SEARCH.filter { (title, category) ->
                                    textResources.getString(title).contains(searchQuery, true) || textResources.getString(category.titleRes).contains(searchQuery, true)
                                }.forEach { (title, category) ->
                                    val label = textResources.getString(title)
                                    SettingsItem(label, textResources.getString(category.titleRes), {
                                        highlightTitle = label; sectionName = category.name
                                    }, category.icon)
                                }
                            }
                            if (searchQuery.isBlank()) SettingsCategory.entries.groupBy { it.group }.forEach { (group, categories) ->
                                Text(
                                    text = textResources.getString(group.titleRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                                )
                                categories.forEach { category ->
                                val summary = when (category) {
                                    SettingsCategory.BROWSING -> "${currentSearchEngine.displayName(textResources)} · " +
                                        if (restoreLastSession) textResources.getString(R.string.ui_restore_previous_pages) else textResources.getString(R.string.ui_open_homepage_on_startup)
                                    SettingsCategory.APPEARANCE -> textResources.getString(preferences.theme.labelRes)
                                    // The summary answers the category's own question: privacy is
                                    // whether the browser is currently private, extensions is what
                                    // filtering is doing and where the installed pieces live.
                                    SettingsCategory.PRIVACY -> listOf(
                                        textResources.getString(
                                            if (isIncognito) R.string.settings_privacy_incognito_on
                                            else R.string.settings_privacy_incognito_off),
                                        textResources.getString(R.string.site_settings),
                                        textResources.getString(R.string.settings_import),
                                    ).joinToString(" · ")
                                    SettingsCategory.EXTENSIONS -> listOfNotNull(
                                        textResources.getString(
                                            if (isFilterEnabled) R.string.ui_ad_filtering_on else R.string.ui_ad_filtering_off),
                                        textResources.getString(R.string.script_title),
                                        textResources.getString(R.string.translate_title),
                                    ).joinToString(" · ")
                                    SettingsCategory.DOWNLOADS -> textResources.getString(R.string.ui_connections_2ad3d3, downloadSettings.displayDestinationLabel(textResources), downloadSettings.threadCount)
                                    SettingsCategory.VIDEO -> if (preferences.video.enhancedControls) textResources.getString(R.string.ui_fullscreen_gestures_hold_for, PlaybackSpeed.label(preferences.video.boostRate)) else textResources.getString(R.string.ui_use_webpage_controls)
                                    SettingsCategory.ABOUT -> textResources.getString(R.string.ui_version_information)
                                }
                                Card(
                                    onClick = {
                                        highlightTitle = null
                                        sectionName = category.name
                                    },
                                    colors = CardDefaults.cardColors(containerColor =
                                        if (twoPane && category == selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow),
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(category.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(textResources.getString(category.titleRes), style = MaterialTheme.typography.titleMedium)
                                            Text(summary, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                        Icon(painterResource(R.drawable.ic_forward), null, Modifier.size(18.dp))
                                    }
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
                                            SettingsGroup(textResources.getString(R.string.ui_startup))
                                            SettingsItem(textResources.getString(R.string.cd_home), if (currentHomepageMode == HomepageMode.NAVIGATION) textResources.getString(R.string.ui_shortcuts_homepage) else currentHomepage,
                                                { openPicker("home") }, R.drawable.ic_home)
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_restore_pages_on_startup),
                                                summary = if (restoreLastSession) textResources.getString(R.string.ui_next_launch_restore_regular_tabs) else textResources.getString(R.string.ui_next_launch_homepage),
                                                checked = restoreLastSession,
                                                onChange = onRestoreLastSessionChange,
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsGroup(textResources.getString(R.string.ui_search_and_system))
                                            SettingsItem(textResources.getString(R.string.ui_search_engine), currentSearchEngine.displayName(textResources), { openPicker("search") }, R.drawable.ic_search)
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_online_search_suggestions),
                                                summary = textResources.getString(R.string.ui_online_search_suggestions_summary),
                                                checked = preferences.searchSuggestionsEnabled,
                                                onChange = { onPreferencesChange(preferences.copy(searchSuggestionsEnabled = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_private_search_suggestions),
                                                summary = textResources.getString(R.string.ui_private_search_suggestions_summary),
                                                checked = preferences.privateSearchSuggestionsEnabled,
                                                onChange = { onPreferencesChange(preferences.copy(privateSearchSuggestionsEnabled = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsItem(textResources.getString(R.string.ui_default_browser), if (isDefaultBrowser) textResources.getString(R.string.ui_set_as_default) else textResources.getString(R.string.ui_not_set_as_default),
                                                onSetDefaultBrowser, R.drawable.ic_desktop)
                                        }
                                        SettingsCategory.APPEARANCE -> {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.browser_fullscreen),
                                                summary = textResources.getString(R.string.browser_fullscreen_summary),
                                                checked = preferences.browserFullscreenEnabled,
                                                onChange = { onPreferencesChange(preferences.copy(browserFullscreenEnabled = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.bottom_address_bar),
                                                summary = textResources.getString(R.string.preference_immediate),
                                                checked = preferences.bottomAddressBar,
                                                onChange = { onPreferencesChange(preferences.copy(bottomAddressBar = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.swipe_tab_switch),
                                                summary = textResources.getString(R.string.swipe_tab_switch_summary),
                                                checked = preferences.swipeTabs,
                                                onChange = { onPreferencesChange(preferences.copy(swipeTabs = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsItem(textResources.getString(R.string.ui_app_theme), textResources.getString(preferences.theme.labelRes), { openPicker("theme") }, R.drawable.ic_settings)
                                            SettingsNote(textResources.getString(R.string.ui_light_and_dark_themes_support_the_system_font))
                                        }
                                        SettingsCategory.PRIVACY -> {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_incognito_browsing),
                                                summary = textResources.getString(R.string.ui_incognito_persistent_summary),
                                                checked = isIncognito,
                                                onChange = onIncognitoChange,
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsItem(textResources.getString(R.string.system_login), textResources.getString(R.string.system_login_summary),
                                                { showSystemLogin = true }, R.drawable.ic_lock)
                                            SettingsNote(textResources.getString(R.string.ui_open_incognito_mode_from_the_browser_menu_supported))
                                            SettingsGroup(textResources.getString(R.string.settings_sites_and_data))
                                            SettingsItem(textResources.getString(R.string.site_settings), textResources.getString(R.string.site_settings_summary), onManageSites, R.drawable.ic_site_settings)
                                            SettingsItem(textResources.getString(R.string.menu_clear_data), textResources.getString(R.string.ui_confirm_before_clearing_cache_cookies_and_history), onClearData, R.drawable.ic_delete)
                                            SettingsItem(textResources.getString(R.string.settings_export), textResources.getString(R.string.settings_export_summary), onExportSettings, R.drawable.ic_share)
                                            SettingsItem(textResources.getString(R.string.settings_import), textResources.getString(R.string.settings_import_summary), onImportSettings, R.drawable.ic_download)
                                        }
                                        SettingsCategory.EXTENSIONS -> {
                                            // The things the user installs or points at. Sites,
                                            // permissions and data management live under Privacy.
                                            SettingsGroup(textResources.getString(R.string.ui_content_filtering))
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_ad_filtering),
                                                summary = textResources.getString(R.string.ui_block_requests_matching_built_in_and_custom_filter),
                                                checked = isFilterEnabled,
                                                onChange = onFilterEnabledChange,
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsItem(textResources.getString(R.string.ui_custom_ad_filter_rules), textResources.getString(R.string.ui_add_and_manage_filter_lists), onManageCustomFilters, R.drawable.ic_shield)
                                            SettingsItem(textResources.getString(R.string.script_title), textResources.getString(R.string.script_settings_summary), onManageUserScripts, R.drawable.ic_code)
                                            SettingsItem(textResources.getString(R.string.translate_title), textResources.getString(R.string.translate_settings_summary),
                                                onOpenTranslation, R.drawable.ic_translate)
                                        }
                                        SettingsCategory.VIDEO -> {
                                            val video = preferences.video
                                            SettingsToggle(
                                                title = textResources.getString(R.string.automatic_pip),
                                                summary = textResources.getString(R.string.automatic_pip_summary),
                                                checked = video.automaticPip,
                                                onChange = { updateVideo(video.copy(automaticPip = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.background_playback),
                                                summary = textResources.getString(R.string.background_playback_summary),
                                                checked = video.backgroundPlayback,
                                                onChange = { updateVideo(video.copy(backgroundPlayback = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsGroup(textResources.getString(R.string.ui_fullscreen_experience))
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_enhanced_video_controls),
                                                summary = textResources.getString(R.string.ui_playback_seeking_speed_and_lock_controls_switch_to),
                                                checked = video.enhancedControls,
                                                onChange = { updateVideo(video.copy(enhancedControls = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_rotate_landscape_videos_automatically),
                                                summary = textResources.getString(R.string.ui_portrait_videos_stay_upright_orientation_is_restored_on),
                                                checked = video.landscapeFullscreen,
                                                onChange = { updateVideo(video.copy(landscapeFullscreen = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsGroup(textResources.getString(R.string.ui_fullscreen_gestures))
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_brightness_and_volume_gestures),
                                                summary = textResources.getString(R.string.ui_swipe_vertically_on_the_left_for_brightness_and),
                                                checked = video.verticalGestures,
                                                onChange = { updateVideo(video.copy(verticalGestures = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_swipe_to_seek),
                                                summary = textResources.getString(R.string.ui_swipe_horizontally_to_preview_a_position_release_to),
                                                checked = video.horizontalSeek,
                                                onChange = { updateVideo(video.copy(horizontalSeek = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_hold_for_temporary_speed_boost),
                                                summary = textResources.getString(R.string.ui_release_to_restore_the_previous_playback_speed),
                                                checked = video.holdToBoost,
                                                onChange = { updateVideo(video.copy(holdToBoost = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsItem(textResources.getString(R.string.ui_hold_speed), PlaybackSpeed.label(video.boostRate), { openPicker("boost") }, R.drawable.ic_speed)
                                            SettingsNote(textResources.getString(R.string.ui_tap_to_show_or_hide_controls_double_tap))
                                            SettingsGroup(textResources.getString(R.string.menu_playback_speed))
                                            SettingsToggle(
                                                title = textResources.getString(R.string.ui_remember_playback_speed),
                                                summary = textResources.getString(R.string.ui_use_the_selected_speed_for_future_videos_temporary),
                                                checked = video.rememberSpeed,
                                                onChange = { updateVideo(video.copy(rememberSpeed = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            SettingsItem(textResources.getString(R.string.ui_default_playback_speed), PlaybackSpeed.label(video.preferredSpeed), { openPicker("speed") }, R.drawable.ic_speed)
                                            SettingsNote(textResources.getString(R.string.ui_videos_keep_playing_through_the_webpage_preserving_sign))
                                        }
                                        SettingsCategory.ABOUT -> {
                                            val context = LocalContext.current
                                            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                                            Text(textResources.getString(R.string.ui_pure_browser), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                                            SettingsNote(textResources.getString(R.string.ui_version_android_10_or_later_64_bit_arm, version))
                                            SettingsItem(textResources.getString(R.string.update_check),
                                                "GitHub · Nobilta/pure-browser", { showUpdate = true }, R.drawable.ic_download)
                                            SettingsToggle(
                                                title = textResources.getString(R.string.update_auto_check),
                                                summary = textResources.getString(R.string.update_auto_check_summary),
                                                checked = preferences.autoCheckUpdates,
                                                onChange = { onPreferencesChange(preferences.copy(autoCheckUpdates = it)) },
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                        }
                                        SettingsCategory.DOWNLOADS -> {
                                            SettingsToggle(
                                                title = textResources.getString(R.string.download_unmetered),
                                                summary = textResources.getString(R.string.download_budget_summary),
                                                checked = downloadSettings.unmeteredOnly,
                                                onChange = onDownloadNetworkChange,
                                                modifier = Modifier.padding(SettingsRowPadding),
                                            )
                                            DownloadNotificationItem(downloadNotificationsEnabled, onDownloadNotifications)
                                            DownloadSettingsSection(downloadSettings, onUseSystemDownloadDirectory, onChooseDownloadDirectory,
                                                onDownloadThreadCountChange)
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
                        Box(Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
                    when (picker) {
                        "search" -> SearchEngineSettings(currentSearchEngine, availableSearchEngines,
                            onSearchEngineChange, onAddCustomSearchEngine, onRemoveCustomSearchEngine, dismissPicker)
                        "home" -> HomepageSettings(currentHomepageMode, currentHomepage,
                            onHomepageModeChange, onHomepageChange, dismissPicker)
                        else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                            val title = when (picker) { "theme" -> textResources.getString(R.string.ui_app_theme); "boost" -> textResources.getString(R.string.ui_hold_speed); else -> textResources.getString(R.string.ui_default_playback_speed) }
                            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                            if (picker == "theme") {
                                ThemeMode.entries.forEach { mode ->
                                    SettingsRadioItem(textResources.getString(mode.labelRes), selected = preferences.theme == mode) {
                                        onPreferencesChange(preferences.copy(theme = mode)); dismissPicker()
                                    }
                                }
                            } else {
                                val boost = picker == "boost"
                                val choices = if (boost) PlaybackSpeed.BOOST_OPTIONS else PlaybackSpeed.OPTIONS
                                choices.forEach { speed ->
                                    SettingsRadioItem(PlaybackSpeed.label(speed),
                                        selected = speed == if (boost) preferences.video.boostRate else preferences.video.preferredSpeed) {
                                        updateVideo(if (boost) preferences.video.copy(boostRate = speed)
                                            else preferences.video.copy(preferredSpeed = speed, rememberSpeed = true))
                                        dismissPicker()
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
    BROWSING(R.string.settings_general, R.drawable.ic_home, SettingsCategoryGroup.BROWSER),
    APPEARANCE(R.string.ui_appearance, R.drawable.ic_settings, SettingsCategoryGroup.BROWSER),
    PRIVACY(R.string.settings_privacy_data, R.drawable.ic_shield, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    EXTENSIONS(R.string.settings_content_tools, R.drawable.ic_code, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    DOWNLOADS(R.string.ui_download_settings, R.drawable.ic_download, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    VIDEO(R.string.settings_media, R.drawable.ic_speed, SettingsCategoryGroup.PRIVACY_CONTENT_MEDIA),
    ABOUT(R.string.settings_about_updates, R.drawable.ic_code, SettingsCategoryGroup.OTHER),
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
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
        TextButton(onClick = onAction) {
            Text(textResources.getString(
                if (enabled) R.string.download_notifications_manage else R.string.download_notifications_enable,
            ))
        }
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
    onBack: () -> Unit,
) {
    val textResources = localizedResources()
    var showAddDialog by remember { mutableStateOf(false) }

    SettingsPage(
        title = textResources.getString(R.string.ui_search_engine),
        onBack = onBack,
        actions = {
            TextButton(onClick = { showAddDialog = true }) {
                Text(stringResource(R.string.search_engine_add))
            }
        },
    ) {
        available.forEach { engine ->
            SettingsRadioItem(
                title = engine.displayName(textResources),
                selected = engine.id == current.id,
                trailing = if (engine.isCustom) {
                    {
                        IconButton(onClick = { onRemoveCustom(engine) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.search_engine_delete),
                            )
                        }
                    }
                } else null,
            ) {
                onChange(engine)
                onBack()
            }
        }
    }

    if (showAddDialog) {
        CustomSearchEngineDialog(
            onConfirm = { name, template, suggestTemplate ->
                if (onAddCustom(name, template, suggestTemplate)) showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun CustomSearchEngineDialog(
    onConfirm: (name: String, template: String, suggestTemplate: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var suggestTemplate by remember { mutableStateOf("") }
    val canSave = name.trim().isNotEmpty() && template.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_engine_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.search_engine_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(stringResource(R.string.search_engine_url)) },
                    placeholder = { Text(stringResource(R.string.search_engine_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = suggestTemplate,
                    onValueChange = { suggestTemplate = it },
                    label = { Text(stringResource(R.string.search_engine_suggest_url)) },
                    placeholder = { Text(stringResource(R.string.search_engine_suggest_url_hint)) },
                    singleLine = true,
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
) {
    val textResources = localizedResources()
    var threadDraft by remember(settings.threadCount) {
        mutableFloatStateOf(settings.threadCount.toFloat())
    }

    // The section heading the settings search indexes under `ui_download_settings`. Without a node
    // carrying exactly that text there is nothing for `highlightSetting` to match, so a search hit
    // jumped into this page with nothing highlighted and nothing scrolled into view.
    Text(
        text = textResources.getString(R.string.ui_download_settings),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .highlightSetting(textResources.getString(R.string.ui_download_settings))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

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

    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

    Text(
        text = textResources.getString(R.string.ui_download_connections),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = textResources.getString(R.string.ui_connections_818c76, threadDraft.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = textResources.getString(R.string.ui_choose_1_16_connections_servers_without_range_support),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Slider(
        value = threadDraft,
        onValueChange = { threadDraft = it },
        onValueChangeFinished = {
            onThreadCountChange(threadDraft.roundToInt())
        },
        valueRange = 1f..16f,
        steps = 14,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("1", style = MaterialTheme.typography.labelSmall)
        Text("16", style = MaterialTheme.typography.labelSmall)
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
        Text(
            text = textResources.getString(R.string.ui_homepage_style),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )

        SettingsRadioItem(
            title = textResources.getString(R.string.ui_shortcuts_homepage),
            subtitle = textResources.getString(R.string.ui_show_shortcuts_to_favorite_sites),
            selected = currentMode == HomepageMode.NAVIGATION,
            onClick = { onModeChange(HomepageMode.NAVIGATION) },
        )

        SettingsRadioItem(
            title = textResources.getString(R.string.ui_custom_url),
            subtitle = textResources.getString(R.string.ui_open_this_page, current),
            selected = currentMode == HomepageMode.FIXED_URL,
            onClick = { onModeChange(HomepageMode.FIXED_URL) },
        )

        if (currentMode == HomepageMode.FIXED_URL) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            SettingsItem(
                title = textResources.getString(R.string.ui_edit_homepage_url),
                subtitle = current,
                onClick = { showDialog = true },
            )
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

private val SETTINGS_SEARCH = listOf(
    R.string.ui_incognito_browsing to SettingsCategory.PRIVACY,
    R.string.system_login to SettingsCategory.PRIVACY,
    R.string.automatic_pip to SettingsCategory.VIDEO,
    R.string.background_playback to SettingsCategory.VIDEO,
    R.string.cd_home to SettingsCategory.BROWSING,
    R.string.ui_restore_pages_on_startup to SettingsCategory.BROWSING,
    R.string.ui_search_engine to SettingsCategory.BROWSING,
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
    R.string.translate_title to SettingsCategory.EXTENSIONS,
    R.string.menu_clear_data to SettingsCategory.PRIVACY,
    R.string.settings_export to SettingsCategory.PRIVACY,
    R.string.settings_import to SettingsCategory.PRIVACY,
    R.string.download_unmetered to SettingsCategory.DOWNLOADS,
    R.string.download_notifications to SettingsCategory.DOWNLOADS,
    R.string.ui_download_settings to SettingsCategory.DOWNLOADS,
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
