package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.mybrowser.data.BrowserPreferences
import com.mybrowser.data.ThemeMode
import com.mybrowser.data.VideoPreferences
import com.mybrowser.media.PlaybackSpeed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.mybrowser.R
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.DownloadSettings
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngine
import kotlin.math.roundToInt

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
    onAddCustomSearchEngine: (String, String) -> Unit,
    onRemoveCustomSearchEngine: (SearchEngine) -> Unit,
    currentHomepageMode: HomepageMode,
    currentHomepage: String,
    onHomepageModeChange: (HomepageMode) -> Unit,
    onHomepageChange: (String) -> Boolean,
    onManageCustomFilters: () -> Unit,
    onManageUserScripts: () -> Unit,
    onManageSites: () -> Unit,
    downloadSettings: DownloadSettings,
    onUseSystemDownloadDirectory: () -> Unit,
    onChooseDownloadDirectory: () -> Unit,
    onDownloadThreadCountChange: (Int) -> Unit,
    onDownloadNetworkChange: (Boolean) -> Unit = {},
    preferences: BrowserPreferences,
    onPreferencesChange: (BrowserPreferences) -> Unit,
    isFilterEnabled: Boolean,
    onFilterEnabledChange: (Boolean) -> Unit,
    onClearData: () -> Unit,
    onDismiss: () -> Unit,
) {
    val textResources = localizedResources()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSystemLogin by remember { mutableStateOf(false) }
    var highlightTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerGeneration by rememberSaveable { mutableLongStateOf(0L) }
    val openPicker: (String) -> Unit = { pickerGeneration++; picker = it }
    val selected = SettingsCategory.entries.firstOrNull { it.name == sectionName }
    val back = {
        if (!childOpen && picker == null && !showSystemLogin) {
            if (selected != null) sectionName = null else onDismiss()
        }
    }
    val updateVideo = { value: VideoPreferences -> onPreferencesChange(preferences.copy(video = value)) }

    // The settings surface and system Back callback share one dialog lifecycle.
    // Returning to the menu removes the whole window, including its input owner.
    Dialog(onDismissRequest = back, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
        dismissOnClickOutside = false,
    )) {
    ApplySheetSystemBars(fullscreen = true)
    if (showSystemLogin) SystemLoginDialog { showSystemLogin = false }
    CompositionLocalProvider(LocalSettingHighlight provides highlightTitle) {
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
        .testTag(if (selected == null) "settings_root" else "settings_detail")) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val twoPane = maxWidth >= 720.dp && selected != null
            Row(Modifier.fillMaxSize()) {
                if (twoPane || selected == null) {
                    Box(if (twoPane) Modifier.width(260.dp) else Modifier.fillMaxSize()) {
                        SettingsPage(textResources.getString(R.string.menu_settings), back) {
                            OutlinedTextField(searchQuery, { searchQuery = it.take(128) }, label = { Text(textResources.getString(R.string.settings_search)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
                            if (searchQuery.isNotBlank()) {
                                SETTINGS_SEARCH.filter { (title, category) ->
                                    textResources.getString(title).contains(searchQuery, true) || textResources.getString(category.titleRes).contains(searchQuery, true)
                                }.take(24).forEach { (title, category) ->
                                    val label = textResources.getString(title)
                                    SettingsItem(label, textResources.getString(category.titleRes), {
                                        highlightTitle = label; sectionName = category.name
                                    }, category.icon)
                                }
                            }
                            if (searchQuery.isBlank()) SettingsCategory.entries.forEach { category ->
                                val summary = when (category) {
                                    SettingsCategory.BROWSING -> "${currentSearchEngine.displayName(textResources)} · " +
                                        if (restoreLastSession) textResources.getString(R.string.ui_restore_previous_pages) else textResources.getString(R.string.ui_open_homepage_on_startup)
                                    SettingsCategory.APPEARANCE -> textResources.getString(preferences.theme.labelRes)
                                    SettingsCategory.PRIVACY -> if (isFilterEnabled) textResources.getString(R.string.ui_ad_filtering_on) else textResources.getString(R.string.ui_ad_filtering_off)
                                    SettingsCategory.DOWNLOADS -> textResources.getString(R.string.ui_connections_2ad3d3, downloadSettings.displayDestinationLabel(textResources), downloadSettings.threadCount)
                                    SettingsCategory.VIDEO -> if (preferences.video.enhancedControls) textResources.getString(R.string.ui_fullscreen_gestures_hold_for, PlaybackSpeed.label(preferences.video.boostRate)) else textResources.getString(R.string.ui_use_webpage_controls)
                                    SettingsCategory.ABOUT -> textResources.getString(R.string.ui_version_information)
                                }
                                Card(
                                    onClick = { sectionName = category.name },
                                    colors = CardDefaults.cardColors(containerColor =
                                        if (twoPane && category == selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow),
                                    shape = MaterialTheme.shapes.medium,
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
                if (twoPane) VerticalDivider()
                if (selected != null) {
                    val category = selected
                    Box(Modifier.weight(1f)) {
                        key(category) {
                            when (category) {
                                SettingsCategory.DOWNLOADS -> Column {
                                    SettingsToggle(textResources.getString(R.string.download_unmetered), textResources.getString(R.string.download_budget_summary),
                                        downloadSettings.unmeteredOnly, onDownloadNetworkChange)
                                    DownloadSettingsPage(downloadSettings, onUseSystemDownloadDirectory, onChooseDownloadDirectory,
                                        onDownloadThreadCountChange, back)
                                }
                                else -> SettingsPage(textResources.getString(category.titleRes), back) {
                                    when (category) {
                                        SettingsCategory.BROWSING -> {
                                            SettingsGroup(textResources.getString(R.string.ui_startup))
                                            SettingsItem(textResources.getString(R.string.cd_home), if (currentHomepageMode == HomepageMode.NAVIGATION) textResources.getString(R.string.ui_shortcuts_homepage) else currentHomepage,
                                                { openPicker("home") }, R.drawable.ic_home)
                                            SettingsToggle(textResources.getString(R.string.ui_restore_pages_on_startup),
                                                if (restoreLastSession) textResources.getString(R.string.ui_next_launch_restore_regular_tabs) else textResources.getString(R.string.ui_next_launch_homepage),
                                                restoreLastSession, onRestoreLastSessionChange)
                                            SettingsGroup(textResources.getString(R.string.ui_search_and_system))
                                            SettingsItem(textResources.getString(R.string.ui_search_engine), currentSearchEngine.displayName(textResources), { openPicker("search") }, R.drawable.ic_search)
                                            SettingsItem(textResources.getString(R.string.ui_default_browser), if (isDefaultBrowser) textResources.getString(R.string.ui_set_as_default) else textResources.getString(R.string.ui_not_set_as_default),
                                                onSetDefaultBrowser, R.drawable.ic_desktop)
                                        }
                                        SettingsCategory.APPEARANCE -> {
                                            SettingsToggle(textResources.getString(R.string.bottom_address_bar), textResources.getString(R.string.preference_immediate),
                                                preferences.bottomAddressBar, { onPreferencesChange(preferences.copy(bottomAddressBar = it)) })
                                            SettingsToggle(textResources.getString(R.string.swipe_tab_switch), textResources.getString(R.string.swipe_tab_switch_summary),
                                                preferences.swipeTabs, { onPreferencesChange(preferences.copy(swipeTabs = it)) })
                                            SettingsItem(textResources.getString(R.string.ui_app_theme), textResources.getString(preferences.theme.labelRes), { openPicker("theme") }, R.drawable.ic_settings)
                                            SettingsNote(textResources.getString(R.string.ui_light_and_dark_themes_support_the_system_font))
                                        }
                                        SettingsCategory.PRIVACY -> {
                                            SettingsItem(textResources.getString(R.string.system_login), textResources.getString(R.string.system_login_summary),
                                                { showSystemLogin = true }, R.drawable.ic_lock)
                                            SettingsToggle(textResources.getString(R.string.private_screenshot_protection),
                                                textResources.getString(R.string.private_screenshot_summary), preferences.protectPrivateScreens,
                                                { onPreferencesChange(preferences.copy(protectPrivateScreens = it)) })
                                            SettingsGroup(textResources.getString(R.string.ui_content_filtering))
                                            SettingsToggle(textResources.getString(R.string.ui_ad_filtering), textResources.getString(R.string.ui_block_requests_matching_built_in_and_custom_filter), isFilterEnabled, onFilterEnabledChange)
                                            SettingsItem(textResources.getString(R.string.ui_custom_ad_filter_rules), textResources.getString(R.string.ui_add_and_manage_filter_lists), onManageCustomFilters, R.drawable.ic_shield)
                                            SettingsItem(textResources.getString(R.string.script_title), textResources.getString(R.string.script_settings_summary), onManageUserScripts, R.drawable.ic_code)
                                            SettingsItem(textResources.getString(R.string.site_settings), textResources.getString(R.string.site_settings_summary), onManageSites, R.drawable.ic_settings)
                                            SettingsGroup(textResources.getString(R.string.menu_section_data))
                                            SettingsItem(textResources.getString(R.string.menu_clear_data), textResources.getString(R.string.ui_confirm_before_clearing_cache_cookies_and_history), onClearData, R.drawable.ic_delete)
                                            SettingsNote(textResources.getString(R.string.ui_open_incognito_mode_from_the_browser_menu_supported))
                                        }
                                        SettingsCategory.VIDEO -> {
                                            val video = preferences.video
                                            SettingsToggle(textResources.getString(R.string.automatic_pip), textResources.getString(R.string.automatic_pip_summary),
                                                video.automaticPip, { updateVideo(video.copy(automaticPip = it)) })
                                            SettingsToggle(textResources.getString(R.string.background_playback), textResources.getString(R.string.background_playback_summary),
                                                video.backgroundPlayback, { updateVideo(video.copy(backgroundPlayback = it)) })
                                            SettingsGroup(textResources.getString(R.string.ui_fullscreen_experience))
                                            SettingsToggle(textResources.getString(R.string.ui_enhanced_video_controls), textResources.getString(R.string.ui_playback_seeking_speed_and_lock_controls_switch_to), video.enhancedControls,
                                                { updateVideo(video.copy(enhancedControls = it)) })
                                            SettingsToggle(textResources.getString(R.string.ui_rotate_landscape_videos_automatically), textResources.getString(R.string.ui_portrait_videos_stay_upright_orientation_is_restored_on), video.landscapeFullscreen,
                                                { updateVideo(video.copy(landscapeFullscreen = it)) })
                                            SettingsGroup(textResources.getString(R.string.ui_fullscreen_gestures))
                                            SettingsToggle(textResources.getString(R.string.ui_brightness_and_volume_gestures), textResources.getString(R.string.ui_swipe_vertically_on_the_left_for_brightness_and), video.verticalGestures,
                                                { updateVideo(video.copy(verticalGestures = it)) }, video.enhancedControls)
                                            SettingsToggle(textResources.getString(R.string.ui_swipe_to_seek), textResources.getString(R.string.ui_swipe_horizontally_to_preview_a_position_release_to), video.horizontalSeek,
                                                { updateVideo(video.copy(horizontalSeek = it)) }, video.enhancedControls)
                                            SettingsToggle(textResources.getString(R.string.ui_hold_for_temporary_speed_boost), textResources.getString(R.string.ui_release_to_restore_the_previous_playback_speed), video.holdToBoost,
                                                { updateVideo(video.copy(holdToBoost = it)) }, video.enhancedControls)
                                            SettingsItem(textResources.getString(R.string.ui_hold_speed), PlaybackSpeed.label(video.boostRate), { openPicker("boost") }, R.drawable.ic_speed)
                                            SettingsNote(textResources.getString(R.string.ui_tap_to_show_or_hide_controls_double_tap))
                                            SettingsGroup(textResources.getString(R.string.menu_playback_speed))
                                            SettingsToggle(textResources.getString(R.string.ui_remember_playback_speed), textResources.getString(R.string.ui_use_the_selected_speed_for_future_videos_temporary), video.rememberSpeed,
                                                { updateVideo(video.copy(rememberSpeed = it)) })
                                            SettingsItem(textResources.getString(R.string.ui_default_playback_speed), PlaybackSpeed.label(video.preferredSpeed), { openPicker("speed") }, R.drawable.ic_speed)
                                            SettingsNote(textResources.getString(R.string.ui_videos_keep_playing_through_the_webpage_preserving_sign))
                                        }
                                        SettingsCategory.ABOUT -> {
                                            val context = LocalContext.current
                                            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                                            Text(textResources.getString(R.string.ui_pure_browser), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                                            SettingsNote(textResources.getString(R.string.ui_version_android_10_or_later_64_bit_arm, version))
                                        }
                                        SettingsCategory.DOWNLOADS -> Unit
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
            ModalBottomSheet(onDismissRequest = dismissPicker,
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                ApplySheetSystemBars()
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
                                    HomepageModeItem(textResources.getString(mode.labelRes), "", preferences.theme == mode) {
                                        onPreferencesChange(preferences.copy(theme = mode)); dismissPicker()
                                    }
                                }
                            } else {
                                val boost = picker == "boost"
                                val choices = if (boost) listOf(2f, 3f) else PlaybackSpeed.OPTIONS
                                choices.forEach { speed ->
                                    HomepageModeItem(PlaybackSpeed.label(speed), "",
                                        speed == if (boost) preferences.video.boostRate else preferences.video.preferredSpeed) {
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

private enum class SettingsCategory(val titleRes: Int, val icon: Int) {
    BROWSING(R.string.ui_browsing_and_startup, R.drawable.ic_home),
    APPEARANCE(R.string.ui_appearance, R.drawable.ic_settings),
    PRIVACY(R.string.ui_privacy_and_filtering, R.drawable.ic_shield),
    DOWNLOADS(R.string.ui_download_settings, R.drawable.ic_download),
    VIDEO(R.string.ui_video_playback, R.drawable.ic_speed),
    ABOUT(R.string.ui_about, R.drawable.ic_code),
}

@Composable
private fun SettingsToggle(title: String, summary: String, checked: Boolean,
    onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().highlightSetting(title).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
}

@Composable
private fun SettingsGroup(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val textResources = localizedResources()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = textResources.getString(R.string.ui_back))
                }
            },
            actions = actions,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEngineSettings(
    current: SearchEngine,
    available: List<SearchEngine>,
    onChange: (SearchEngine) -> Unit,
    onAddCustom: (String, String) -> Unit,
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onChange(engine)
                        onBack()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = engine.id == current.id,
                    onClick = {
                        onChange(engine)
                        onBack()
                    },
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = engine.displayName(textResources),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (engine.isCustom) {
                    IconButton(onClick = { onRemoveCustom(engine) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.search_engine_delete),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CustomSearchEngineDialog(
            onConfirm = { name, template ->
                onAddCustom(name, template)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun CustomSearchEngineDialog(
    onConfirm: (name: String, template: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
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
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), template.trim()) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSettingsPage(
    settings: DownloadSettings,
    onUseSystemDirectory: () -> Unit,
    onChooseDirectory: () -> Unit,
    onThreadCountChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val textResources = localizedResources()
    var threadDraft by remember(settings.threadCount) {
        mutableFloatStateOf(settings.threadCount.toFloat())
    }

    SettingsPage(title = textResources.getString(R.string.ui_download_settings), onBack = onBack) {
        Text(
            text = textResources.getString(R.string.ui_save_location),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        DownloadDirectoryItem(
            title = textResources.getString(R.string.ui_system_downloads_folder),
            subtitle = textResources.getString(R.string.ui_save_to_the_device_downloads_folder),
            selected = settings.destinationMode == DownloadDestinationMode.SYSTEM_DOWNLOADS,
            onClick = onUseSystemDirectory,
        )
        DownloadDirectoryItem(
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
}

@Composable
private fun DownloadDirectoryItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Text(
            text = textResources.getString(R.string.ui_homepage_style),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )

        HomepageModeItem(
            title = textResources.getString(R.string.ui_shortcuts_homepage),
            subtitle = textResources.getString(R.string.ui_show_shortcuts_to_favorite_sites),
            selected = currentMode == HomepageMode.NAVIGATION,
            onClick = { onModeChange(HomepageMode.NAVIGATION) },
        )

        HomepageModeItem(
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

@Composable
private fun HomepageModeItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconRes: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .highlightSetting(title)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        }
        Spacer(Modifier.width(12.dp))
        Icon(painterResource(R.drawable.ic_forward), null, modifier = Modifier.size(18.dp))
    }
}

private val LocalSettingHighlight = staticCompositionLocalOf<String?> { null }
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.highlightSetting(title: String): Modifier = composed {
    val highlighted = LocalSettingHighlight.current == title
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) { kotlinx.coroutines.delay(250); requester.bringIntoView() } }
    bringIntoViewRequester(requester).then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
}
private val SETTINGS_SEARCH = listOf(
    R.string.system_login to SettingsCategory.PRIVACY,
    R.string.automatic_pip to SettingsCategory.VIDEO,
    R.string.background_playback to SettingsCategory.VIDEO,
    R.string.cd_home to SettingsCategory.BROWSING,
    R.string.ui_restore_pages_on_startup to SettingsCategory.BROWSING,
    R.string.ui_search_engine to SettingsCategory.BROWSING,
    R.string.ui_default_browser to SettingsCategory.BROWSING,
    R.string.ui_app_theme to SettingsCategory.APPEARANCE,
    R.string.bottom_address_bar to SettingsCategory.APPEARANCE,
    R.string.swipe_tab_switch to SettingsCategory.APPEARANCE,
    R.string.private_screenshot_protection to SettingsCategory.PRIVACY,
    R.string.ui_ad_filtering to SettingsCategory.PRIVACY,
    R.string.ui_custom_ad_filter_rules to SettingsCategory.PRIVACY,
    R.string.script_title to SettingsCategory.PRIVACY,
    R.string.site_settings to SettingsCategory.PRIVACY,
    R.string.menu_clear_data to SettingsCategory.PRIVACY,
    R.string.download_unmetered to SettingsCategory.DOWNLOADS,
    R.string.ui_download_settings to SettingsCategory.DOWNLOADS,
    R.string.ui_brightness_and_volume_gestures to SettingsCategory.VIDEO,
    R.string.ui_swipe_to_seek to SettingsCategory.VIDEO,
    R.string.ui_hold_for_temporary_speed_boost to SettingsCategory.VIDEO,
    R.string.ui_hold_speed to SettingsCategory.VIDEO,
    R.string.ui_remember_playback_speed to SettingsCategory.VIDEO,
    R.string.ui_default_playback_speed to SettingsCategory.VIDEO,
)
