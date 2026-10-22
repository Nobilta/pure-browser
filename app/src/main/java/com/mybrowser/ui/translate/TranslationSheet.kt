package com.mybrowser.ui.translate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.mybrowser.R
import com.mybrowser.translate.TRANSLATION_SOURCES
import com.mybrowser.translate.TRANSLATION_TARGETS
import com.mybrowser.translate.TranslationProvider
import com.mybrowser.translate.TranslationSettings
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserFullscreenSheet
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.browserSheetInsets

/** The page sheet is intentionally simple; credentials are edited only in Settings. */
enum class TranslationSheetMode { PAGE, SETTINGS }

private val PAGE_PROVIDERS = listOf(TranslationProvider.MYMEMORY, TranslationProvider.GOOGLE)

@Composable
fun TranslationSheet(
    mode: TranslationSheetMode,
    settings: TranslationSettings,
    busy: Boolean,
    progress: Int,
    pageTranslated: Boolean,
    error: String?,
    onSettingsChange: (TranslationSettings) -> Unit,
    onTranslatePage: () -> Unit,
    onRestorePage: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fullscreen = mode == TranslationSheetMode.SETTINGS
    val showAdvanced = fullscreen
    val providerOptions = if (showAdvanced) TranslationProvider.entries else PAGE_PROVIDERS
    val content: @Composable ColumnScope.() -> Unit = {
        TranslationFields(
            mode = mode,
            settings = settings,
            providerOptions = providerOptions,
            busy = busy,
            progress = progress,
            pageTranslated = pageTranslated,
            error = error,
            onSettingsChange = onSettingsChange,
            onTranslatePage = onTranslatePage,
            onRestorePage = onRestorePage,
            onCancel = onCancel,
        )
    }

    // A page tool is a bottom sheet over the page. The same UI opened from Settings is a
    // full-screen settings page: replacing the Settings surface with a translucent page sheet
    // exposed the webpage underneath it. Keeping this route opaque also makes Back return to a
    // solid Settings page instead of leaving the window in panel mode.
    if (fullscreen) {
        BrowserFullscreenSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets())) {
                BrowserSheetHeader(stringResource(R.string.translate_settings_title), onBack = onDismiss)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    } else {
        BackHandler(enabled = busy) { onCancel() }
        BrowserBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                BrowserSheetHeader(
                    stringResource(R.string.translate_title),
                    onDismiss = { if (!busy) onDismiss() },
                )
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.TranslationFields(
    mode: TranslationSheetMode,
    settings: TranslationSettings,
    providerOptions: List<TranslationProvider>,
    busy: Boolean,
    progress: Int,
    pageTranslated: Boolean,
    error: String?,
    onSettingsChange: (TranslationSettings) -> Unit,
    onTranslatePage: () -> Unit,
    onRestorePage: () -> Unit,
    onCancel: () -> Unit,
) {
    var providerMenu by remember { mutableStateOf(false) }
    var targetMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val targetLabel = TRANSLATION_TARGETS.firstOrNull { it.code == settings.targetLanguage }?.label
        ?: settings.targetLanguage
    val sourceLabel = if (settings.sourceLanguage == "auto") stringResource(R.string.translate_auto)
    else TRANSLATION_SOURCES.firstOrNull { it.code == settings.sourceLanguage }?.label ?: settings.sourceLanguage
    val advanced = mode == TranslationSheetMode.SETTINGS

    if (!advanced && settings.provider !in PAGE_PROVIDERS) {
        Text(
            stringResource(R.string.translate_custom_active, providerLabel(settings.provider)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TranslationChoice(
        label = stringResource(R.string.translate_provider),
        value = providerLabel(settings.provider),
        expanded = providerMenu,
        onExpandedChange = { providerMenu = it },
        options = providerOptions.map { it to providerLabel(it) },
        onSelect = {
            onSettingsChange(settings.copy(provider = it))
            providerMenu = false
        },
    )
    TranslationChoice(
        label = stringResource(R.string.translate_target),
        value = targetLabel,
        expanded = targetMenu,
        onExpandedChange = { targetMenu = it },
        options = TRANSLATION_TARGETS.map { it.code to it.label },
        onSelect = {
            onSettingsChange(settings.copy(targetLanguage = it))
            targetMenu = false
        },
    )
    TranslationChoice(
        label = stringResource(R.string.translate_source),
        value = sourceLabel,
        expanded = sourceMenu,
        onExpandedChange = { sourceMenu = it },
        options = TRANSLATION_SOURCES.map { it.code to if (it.code == "auto") stringResource(R.string.translate_auto) else it.label },
        onSelect = {
            onSettingsChange(settings.copy(sourceLanguage = it))
            sourceMenu = false
        },
    )

    if (advanced) {
        when (settings.provider) {
            TranslationProvider.MYMEMORY -> Text(
                stringResource(R.string.translate_mymemory_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranslationProvider.GOOGLE -> Text(
                stringResource(R.string.translate_google_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranslationProvider.AZURE -> {
                SecretField(
                    value = settings.azureKey,
                    label = stringResource(R.string.translate_api_key),
                    show = showKey,
                    onShowChange = { showKey = it },
                    onValueChange = { onSettingsChange(settings.copy(azureKey = it.take(512))) },
                )
                OutlinedTextField(
                    value = settings.azureRegion,
                    onValueChange = { onSettingsChange(settings.copy(azureRegion = it.take(64))) },
                    label = { Text(stringResource(R.string.translate_region)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TranslationProvider.DEEPL -> SecretField(
                value = settings.deeplKey,
                label = stringResource(R.string.translate_api_key),
                show = showKey,
                onShowChange = { showKey = it },
                onValueChange = { onSettingsChange(settings.copy(deeplKey = it.take(512))) },
            )
            TranslationProvider.LIBRETRANSLATE -> {
                OutlinedTextField(
                    value = settings.libreEndpoint,
                    onValueChange = { onSettingsChange(settings.copy(libreEndpoint = it.take(2048))) },
                    label = { Text(stringResource(R.string.translate_endpoint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(
                    value = settings.libreKey,
                    label = stringResource(R.string.translate_api_key),
                    show = showKey,
                    onShowChange = { showKey = it },
                    onValueChange = { onSettingsChange(settings.copy(libreKey = it.take(512))) },
                )
            }
        }
        Text(
            stringResource(R.string.translate_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        when (settings.provider) {
            TranslationProvider.MYMEMORY -> Text(
                stringResource(R.string.translate_mymemory_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranslationProvider.GOOGLE -> Text(
                stringResource(R.string.translate_google_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                stringResource(R.string.translate_other_providers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Text(
        stringResource(R.string.translate_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (busy) {
        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.translate_busy, progress.coerceIn(0, 100)))
    }
    error?.let {
        Text(
            stringResource(R.string.translate_error, it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (!advanced) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onTranslatePage,
                enabled = !busy && settings.supportsFullPage && settings.configured,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.translate_page)) }
            OutlinedButton(
                onClick = onRestorePage,
                enabled = !busy && pageTranslated,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.translate_restore)) }
        }
        if (busy) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.Start)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun SecretField(
    value: String,
    label: String,
    show: Boolean,
    onShowChange: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None
        else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { onShowChange(!show) }) {
                Icon(
                    painterResource(if (show) R.drawable.ic_lock_open else R.drawable.ic_lock),
                    contentDescription = stringResource(
                        if (show) R.string.translate_hide_key else R.string.translate_show_key,
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> TranslationChoice(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key) })
            }
        }
    }
}

private fun providerLabel(provider: TranslationProvider): String = when (provider) {
    TranslationProvider.MYMEMORY -> "MyMemory"
    TranslationProvider.GOOGLE -> "Google"
    TranslationProvider.AZURE -> "Azure Translator"
    TranslationProvider.DEEPL -> "DeepL"
    TranslationProvider.LIBRETRANSLATE -> "LibreTranslate"
}
