package com.mybrowser.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mybrowser.R
import com.mybrowser.download.DownloadDestinationMode
import com.mybrowser.download.DownloadSettings
import com.mybrowser.home.HomepageMode
import com.mybrowser.search.SearchEngine
import kotlin.math.roundToInt

/**
 * Settings with a fixed header and independent, scrollable detail pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    restoreLastSession: Boolean,
    onRestoreLastSessionChange: (Boolean) -> Unit,
    isDefaultBrowser: Boolean,
    onSetDefaultBrowser: () -> Unit,
    currentSearchEngine: SearchEngine,
    availableSearchEngines: List<SearchEngine>,
    onSearchEngineChange: (SearchEngine) -> Unit,
    onAddCustomSearchEngine: (name: String, template: String) -> Unit = { _, _ -> },
    onRemoveCustomSearchEngine: (SearchEngine) -> Unit = {},
    currentHomepageMode: HomepageMode,
    currentHomepage: String,
    onHomepageModeChange: (HomepageMode) -> Unit,
    onHomepageChange: (String) -> Boolean,
    onManageCustomFilters: () -> Unit,
    downloadSettings: DownloadSettings,
    onUseSystemDownloadDirectory: () -> Unit,
    onChooseDownloadDirectory: () -> Unit,
    onDownloadThreadCountChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSection by remember { mutableStateOf<SettingsSection?>(null) }

    Dialog(
        onDismissRequest = { if (selectedSection != null) selectedSection = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (selectedSection) {
            SettingsSection.SEARCH_ENGINE -> {
                SearchEngineSettings(
                    current = currentSearchEngine,
                    available = availableSearchEngines,
                    onChange = onSearchEngineChange,
                    onAddCustom = onAddCustomSearchEngine,
                    onRemoveCustom = onRemoveCustomSearchEngine,
                    onBack = { selectedSection = null },
                )
            }
            SettingsSection.HOMEPAGE -> {
                HomepageSettings(
                    currentMode = currentHomepageMode,
                    current = currentHomepage,
                    onModeChange = onHomepageModeChange,
                    onChange = onHomepageChange,
                    onBack = { selectedSection = null },
                )
            }
            SettingsSection.DOWNLOADS -> {
                DownloadSettingsPage(
                    settings = downloadSettings,
                    onUseSystemDirectory = onUseSystemDownloadDirectory,
                    onChooseDirectory = onChooseDownloadDirectory,
                    onThreadCountChange = onDownloadThreadCountChange,
                    onBack = { selectedSection = null },
                )
            }
            null -> {
                MainSettings(
                    restoreLastSession = restoreLastSession,
                    onRestoreLastSessionChange = onRestoreLastSessionChange,
                    isDefaultBrowser = isDefaultBrowser,
                    onSetDefaultBrowser = onSetDefaultBrowser,
                    onBack = onDismiss,
                    currentSearchEngine = currentSearchEngine,
                    currentHomepageMode = currentHomepageMode,
                    currentHomepage = currentHomepage,
                    onOpenSearchEngine = { selectedSection = SettingsSection.SEARCH_ENGINE },
                    onOpenHomepage = { selectedSection = SettingsSection.HOMEPAGE },
                    downloadSettings = downloadSettings,
                    onOpenDownloads = { selectedSection = SettingsSection.DOWNLOADS },
                    onManageCustomFilters = onManageCustomFilters,
                )
            }
        }
        }
        }
    }
}

private enum class SettingsSection {
    SEARCH_ENGINE,
    HOMEPAGE,
    DOWNLOADS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainSettings(
    restoreLastSession: Boolean,
    onRestoreLastSessionChange: (Boolean) -> Unit,
    isDefaultBrowser: Boolean,
    onSetDefaultBrowser: () -> Unit,
    onBack: () -> Unit,
    currentSearchEngine: SearchEngine,
    currentHomepageMode: HomepageMode,
    currentHomepage: String,
    downloadSettings: DownloadSettings,
    onOpenSearchEngine: () -> Unit,
    onOpenHomepage: () -> Unit,
    onOpenDownloads: () -> Unit,
    onManageCustomFilters: () -> Unit,
) {
    SettingsPage(title = "设置", onBack = onBack) {
        SettingsGroup("常规")
        SettingsItem(
            title = "默认浏览器",
            subtitle = if (isDefaultBrowser) "已设为默认" else "尚未设为默认",
            iconRes = R.drawable.ic_desktop,
            onClick = onSetDefaultBrowser,
        )
        SettingsItem(
            title = "搜索引擎",
            subtitle = currentSearchEngine.name,
            onClick = onOpenSearchEngine,
            iconRes = R.drawable.ic_search,
        )

        SettingsGroup("启动")
        SettingsItem(
            title = "主页",
            subtitle = if (currentHomepageMode == HomepageMode.NAVIGATION) {
                "导航首页"
            } else {
                currentHomepage
            },
            onClick = onOpenHomepage,
            iconRes = R.drawable.ic_home,
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .toggleable(restoreLastSession, role = Role.Switch, onValueChange = onRestoreLastSessionChange)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_history), null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("启动时恢复上次网页", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (restoreLastSession) "下次启动：继续浏览普通标签页" else "下次启动：主页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = restoreLastSession, onCheckedChange = null)
        }
        SettingsGroup("内容与下载")
        SettingsItem(
            title = "下载设置",
            subtitle = "${downloadSettings.destinationLabel} · ${downloadSettings.threadCount} 线程",
            onClick = onOpenDownloads,
            iconRes = R.drawable.ic_download,
        )
        SettingsItem(
            title = "自定义广告过滤规则",
            subtitle = "管理自定义过滤列表",
            onClick = onManageCustomFilters,
            iconRes = R.drawable.ic_shield,
        )
    }
}

@Composable
private fun SettingsGroup(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
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
