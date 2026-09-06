package com.mybrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.mybrowser.R
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.DownloadSettings
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngine
import kotlin.math.roundToInt

/** Category navigation stays mounted while pickers and filter lists are open. */
@Composable
fun SettingsSheet(
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
    downloadSettings: DownloadSettings,
    onUseSystemDownloadDirectory: () -> Unit,
    onChooseDownloadDirectory: () -> Unit,
    onDownloadThreadCountChange: (Int) -> Unit,
    preferences: BrowserPreferences,
    onPreferencesChange: (BrowserPreferences) -> Unit,
    isFilterEnabled: Boolean,
    onFilterEnabledChange: (Boolean) -> Unit,
    onClearData: () -> Unit,
    onOpenDeveloperTools: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = sectionName?.let(SettingsCategory::valueOf)
    val back = { if (selected != null) sectionName = null else onDismiss() }
    BackHandler(enabled = picker == null, onBack = back)
    val updateVideo = { value: VideoPreferences -> onPreferencesChange(preferences.copy(video = value)) }

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                if (wide || selected == null) {
                    Box(if (wide) Modifier.width(260.dp) else Modifier.fillMaxSize()) {
                        SettingsPage("设置", onDismiss) {
                            Text("按功能分类", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                            SettingsCategory.entries.forEach { category ->
                                val summary = when (category) {
                                    SettingsCategory.BROWSING -> "${currentSearchEngine.name} · " +
                                        if (restoreLastSession) "恢复上次网页" else "启动时打开主页"
                                    SettingsCategory.APPEARANCE -> preferences.theme.label
                                    SettingsCategory.PRIVACY -> if (isFilterEnabled) "广告过滤已开启" else "广告过滤已关闭"
                                    SettingsCategory.DOWNLOADS -> "${downloadSettings.destinationLabel} · ${downloadSettings.threadCount} 线程"
                                    SettingsCategory.VIDEO -> if (preferences.video.enhancedControls) "全屏手势 · 长按 ${PlaybackSpeed.label(preferences.video.boostRate)}" else "使用网页控件"
                                    SettingsCategory.ABOUT -> "版本信息与开发工具"
                                }
                                Card(
                                    onClick = { sectionName = category.name },
                                    colors = CardDefaults.cardColors(containerColor =
                                        if (wide && category == (selected ?: SettingsCategory.BROWSING)) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow),
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(category.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(category.title, style = MaterialTheme.typography.titleMedium)
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
                if (wide) VerticalDivider()
                if (wide || selected != null) {
                    val category = selected ?: SettingsCategory.BROWSING
                    Box(Modifier.weight(1f)) {
                        key(category) {
                            val detailBack = { if (wide) onDismiss() else sectionName = null }
                            when (category) {
                                SettingsCategory.DOWNLOADS -> DownloadSettingsPage(downloadSettings,
                                    onUseSystemDownloadDirectory, onChooseDownloadDirectory,
                                    onDownloadThreadCountChange, detailBack)
                                else -> SettingsPage(category.title, detailBack) {
                                    when (category) {
                                        SettingsCategory.BROWSING -> {
                                            SettingsGroup("启动")
                                            SettingsItem("主页", if (currentHomepageMode == HomepageMode.NAVIGATION) "导航首页" else currentHomepage,
                                                { picker = "home" }, R.drawable.ic_home)
                                            SettingsToggle("启动时恢复上次网页",
                                                if (restoreLastSession) "下次启动：继续浏览普通标签页" else "下次启动：主页",
                                                restoreLastSession, onRestoreLastSessionChange)
                                            SettingsGroup("搜索与系统")
                                            SettingsItem("搜索引擎", currentSearchEngine.name, { picker = "search" }, R.drawable.ic_search)
                                            SettingsItem("默认浏览器", if (isDefaultBrowser) "已设为默认" else "尚未设为默认",
                                                onSetDefaultBrowser, R.drawable.ic_desktop)
                                        }
                                        SettingsCategory.APPEARANCE -> {
                                            SettingsItem("应用主题", preferences.theme.label, { picker = "theme" }, R.drawable.ic_settings)
                                            SettingsNote("浅色与深色均支持系统字体大小。支持动态配色的设备会使用壁纸配色。网页本身的颜色由网站和 WebView 决定。")
                                        }
                                        SettingsCategory.PRIVACY -> {
                                            SettingsGroup("内容过滤")
                                            SettingsToggle("广告过滤", "拦截内置规则和自定义列表匹配的请求", isFilterEnabled, onFilterEnabledChange)
                                            SettingsItem("自定义广告过滤规则", "添加和管理过滤列表", onManageCustomFilters, R.drawable.ic_shield)
                                            SettingsGroup("浏览数据")
                                            SettingsItem("清除浏览数据", "选择后确认清除缓存、Cookie 和历史记录", onClearData, R.drawable.ic_delete)
                                            SettingsNote("无痕入口保留在浏览菜单。支持独立存储的 WebView 会隔离登录数据；旧 WebView 使用退出清除模式，可能同时清除普通模式的登录状态。书签与下载文件会保留。")
                                        }
                                        SettingsCategory.VIDEO -> {
                                            val video = preferences.video
                                            SettingsGroup("全屏体验")
                                            SettingsToggle("增强全屏控件", "播放、进度、倍速与锁定；随时切回网页控件", video.enhancedControls,
                                                { updateVideo(video.copy(enhancedControls = it)) })
                                            SettingsToggle("横向视频自动横屏", "竖向视频保持竖屏，退出后恢复方向", video.landscapeFullscreen,
                                                { updateVideo(video.copy(landscapeFullscreen = it)) })
                                            SettingsGroup("全屏手势")
                                            SettingsToggle("亮度与音量手势", "左侧上下滑动调亮度，右侧调媒体音量", video.verticalGestures,
                                                { updateVideo(video.copy(verticalGestures = it)) }, video.enhancedControls)
                                            SettingsToggle("滑动调整进度", "左右滑动预览位置，松手跳转；直播不跳转", video.horizontalSeek,
                                                { updateVideo(video.copy(horizontalSeek = it)) }, video.enhancedControls)
                                            SettingsToggle("长按临时倍速", "松手立即恢复原来的播放速度", video.holdToBoost,
                                                { updateVideo(video.copy(holdToBoost = it)) }, video.enhancedControls)
                                            SettingsItem("长按速度", PlaybackSpeed.label(video.boostRate), { picker = "boost" }, R.drawable.ic_speed)
                                            SettingsNote("单击显示或隐藏控件；双击中间播放或暂停，两侧快退或快进 10 秒。锁定后仅保留解锁入口。")
                                            SettingsGroup("播放速度")
                                            SettingsToggle("记住播放速度", "将选择的速度用于之后播放的视频；临时倍速不保存", video.rememberSpeed,
                                                { updateVideo(video.copy(rememberSpeed = it)) })
                                            SettingsItem("默认播放速度", PlaybackSpeed.label(video.preferredSpeed), { picker = "speed" }, R.drawable.ic_speed)
                                            SettingsNote("视频继续由网页播放，保留网站的登录、清晰度与字幕能力。需要使用网站专属按钮时，点全屏右上角的“网页控件”。")
                                        }
                                        SettingsCategory.ABOUT -> {
                                            val context = LocalContext.current
                                            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                                            Text("Pure 浏览器", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                                            SettingsNote("版本 $version\n支持 Android 10 及以上 · 64 位 ARM 设备")
                                            SettingsItem("开发工具", "查看当前页面的控制台与网络请求", onOpenDeveloperTools, R.drawable.ic_code)
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
        ModalBottomSheet(onDismissRequest = { picker = null }) {
            Box(Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
                when (picker) {
                    "search" -> SearchEngineSettings(currentSearchEngine, availableSearchEngines,
                        onSearchEngineChange, onAddCustomSearchEngine, onRemoveCustomSearchEngine, { picker = null })
                    "home" -> HomepageSettings(currentHomepageMode, currentHomepage,
                        onHomepageModeChange, onHomepageChange, { picker = null })
                    else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                        val title = when (picker) { "theme" -> "应用主题"; "boost" -> "长按速度"; else -> "默认播放速度" }
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                        if (picker == "theme") {
                            ThemeMode.entries.forEach { mode ->
                                HomepageModeItem(mode.label, "", preferences.theme == mode) {
                                    onPreferencesChange(preferences.copy(theme = mode)); picker = null
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
                                    picker = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsCategory(val title: String, val icon: Int) {
    BROWSING("浏览与启动", R.drawable.ic_home),
    APPEARANCE("外观", R.drawable.ic_settings),
    PRIVACY("隐私与过滤", R.drawable.ic_shield),
    DOWNLOADS("下载设置", R.drawable.ic_download),
    VIDEO("视频播放", R.drawable.ic_speed),
    ABOUT("关于", R.drawable.ic_code),
}

@Composable
private fun SettingsToggle(title: String, summary: String, checked: Boolean,
    onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
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
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
    var showAddDialog by remember { mutableStateOf(false) }

    SettingsPage(
        title = "搜索引擎",
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
                    text = engine.name,
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
    var threadDraft by remember(settings.threadCount) {
        mutableFloatStateOf(settings.threadCount.toFloat())
    }

    SettingsPage(title = "下载设置", onBack = onBack) {
        Text(
            text = "保存位置",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        DownloadDirectoryItem(
            title = "系统下载目录",
            subtitle = "保存到设备的 Download 文件夹",
            selected = settings.destinationMode == DownloadDestinationMode.SYSTEM_DOWNLOADS,
            onClick = onUseSystemDirectory,
        )
        DownloadDirectoryItem(
            title = "自定义目录",
            subtitle = if (settings.customTreeUri == null) {
                "选择一个有写入权限的文件夹"
            } else {
                "${settings.customDirectoryLabel ?: "已选择文件夹"} · 点按更换"
            },
            selected = settings.destinationMode == DownloadDestinationMode.CUSTOM_DIRECTORY,
            onClick = onChooseDirectory,
        )

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        Text(
            text = "下载线程数",
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
                    text = "${threadDraft.roundToInt()} 线程",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "范围 1–16；服务器不支持分段时自动改用单线程",
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
    var showDialog by remember { mutableStateOf(false) }

    SettingsPage(title = "主页", onBack = onBack) {
        Text(
            text = "主页样式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )

        HomepageModeItem(
            title = "导航首页",
            subtitle = "显示收藏的网站快捷入口",
            selected = currentMode == HomepageMode.NAVIGATION,
            onClick = { onModeChange(HomepageMode.NAVIGATION) },
        )

        HomepageModeItem(
            title = "固定网址",
            subtitle = "打开指定网页：$current",
            selected = currentMode == HomepageMode.FIXED_URL,
            onClick = { onModeChange(HomepageMode.FIXED_URL) },
        )

        if (currentMode == HomepageMode.FIXED_URL) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            SettingsItem(
                title = "修改固定网址",
                subtitle = current,
                onClick = { showDialog = true },
            )
        }
    }

    if (showDialog) {
        TextInputDialog(
            title = "设置主页",
            label = "主页地址",
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
