package com.mybrowser.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.mybrowser.R
import com.mybrowser.home.HomeShortcut
import com.mybrowser.home.ShortcutIconChange
import com.mybrowser.home.ShortcutIconLoader
import com.mybrowser.home.ShortcutSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HomeShortcutEditor(
    shortcut: HomeShortcut,
    onSave: suspend (String, String, String, ShortcutIconChange) -> ShortcutSaveResult,
    onRemove: suspend (HomeShortcut) -> Boolean,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(shortcut.title) }
    var url by rememberSaveable { mutableStateOf(shortcut.url) }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var imageRevision by rememberSaveable { mutableIntStateOf(0) }
    var useTextIcon by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var imageError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ShortcutSaveResult?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            image = null
            imageError = false
            useTextIcon = false
            imageUri = uri.toString()
            imageRevision++
            result = null
        }
    }
    LaunchedEffect(imageUri, imageRevision) {
        image = null
        imageError = false
        val uri = imageUri ?: return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching { ShortcutIconLoader.load(resolver, uri.toUri()) }.getOrNull()
        }
        image = loaded
        imageError = loaded == null
    }
    val loadingImage = imageUri != null && image == null && !imageError
    val icon = when {
        useTextIcon -> null
        image != null -> image
        else -> shortcut.icon
    }
    val dismiss = { if (!saving) onDismiss() }
    val errorText = when (result) {
        ShortcutSaveResult.INVALID_URL -> R.string.home_shortcut_invalid_url
        ShortcutSaveResult.DUPLICATE_URL -> R.string.home_shortcut_duplicate_url
        ShortcutSaveResult.NOT_FOUND -> R.string.home_shortcut_not_found
        ShortcutSaveResult.ICON_ERROR -> R.string.home_shortcut_icon_failed
        ShortcutSaveResult.FAILED -> R.string.home_shortcut_operation_failed
        else -> null
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirmRemoval = false },
            title = { Text(stringResource(R.string.ui_remove_from_homepage)) },
            text = {
                Column {
                    Text(stringResource(R.string.ui_remove_from_the_homepage_its_bookmark_will_be, shortcut.title))
                    if (errorText != null) Text(stringResource(errorText), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        if (onRemove(shortcut)) onDismiss()
                        else result = ShortcutSaveResult.FAILED
                        saving = false
                    }
                }) { Text(stringResource(R.string.ui_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { confirmRemoval = false; result = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = dismiss,
            modifier = Modifier.imePadding(),
            properties = DialogProperties(decorFitsSystemWindows = false),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_shortcut_edit), modifier = Modifier.weight(1f))
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                        BrowserIconAction(R.drawable.ic_delete, stringResource(R.string.ui_remove_from_homepage_13d6f7), !saving) {
                            result = null
                            confirmRemoval = true
                        }
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            HomeShortcutIcon(title, url, icon, Modifier.size(64.dp))
                            if (loadingImage) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            TextButton(enabled = !saving, onClick = {
                                runCatching { picker.launch(arrayOf("image/*")) }.onFailure { imageError = true }
                            }) {
                                Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_shortcut_choose_icon))
                            }
                            TextButton(enabled = !saving && (!useTextIcon || imageUri != null), onClick = {
                                imageUri = null
                                image = null
                                useTextIcon = true
                                imageError = false
                                result = null
                            }) { Text(stringResource(R.string.home_shortcut_text_icon)) }
                        }
                    }
                    if (imageError) Text(stringResource(R.string.home_shortcut_icon_failed), color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(value = title, onValueChange = { title = it.take(128); result = null },
                        label = { Text(stringResource(R.string.bookmark_edit_title)) }, singleLine = true,
                        enabled = !saving, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = url, onValueChange = { url = it.take(8192); result = null },
                        label = { Text(stringResource(R.string.bookmark_edit_url)) }, singleLine = true,
                        enabled = !saving, isError = result == ShortcutSaveResult.INVALID_URL || result == ShortcutSaveResult.DUPLICATE_URL,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth())
                    if (errorText != null) Text(stringResource(errorText), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && !loadingImage && !(imageUri != null && imageError) &&
                    title.isNotBlank() && url.isNotBlank(), onClick = {
                    val change = when {
                        useTextIcon -> ShortcutIconChange.UseText
                        image != null -> ShortcutIconChange.Replace(checkNotNull(image))
                        else -> ShortcutIconChange.Keep
                    }
                    saving = true
                    scope.launch {
                        result = onSave(shortcut.id, title, url, change)
                        saving = false
                        if (result == ShortcutSaveResult.SAVED) onDismiss()
                    }
                }) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.bookmark_save))
                }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
