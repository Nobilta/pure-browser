package com.mybrowser.ui

import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.UrlUtils

/**
 * Activity-owned imperative WebView callbacks rendered by the same Material 3 composition
 * as the browser. Every dismissal, replacement attempt and teardown answers exactly once.
 */
class Dialogs {
    private enum class Kind { CONFIRM, ALERT, PROMPT, CREDENTIALS }
    private class Request(
        val kind: Kind, val title: String, val message: String,
        val positive: String, val negative: String, val defaultValue: String = "",
        val result: (Boolean, String, String) -> Unit,
    )
    private var active by mutableStateOf<Request?>(null)

    private fun show(request: Request) {
        if (active != null) request.result(false, "", "") else active = request
    }

    private fun answer(request: Request, accepted: Boolean, text: String = "", password: String = "") {
        if (active !== request) return
        active = null
        runCatching { request.result(accepted, text, password) }
    }

    fun dismiss() { active?.let { answer(it, false) } }

    fun confirm(context: Context, title: String, message: String,
        positiveText: String = context.getString(R.string.ui_ok),
        negativeText: String = context.getString(R.string.action_cancel),
        onResult: (Boolean) -> Unit,
    ) = show(Request(Kind.CONFIRM, title, message, positiveText, negativeText) { accepted, _, _ -> onResult(accepted) })

    fun alert(context: Context, title: String, message: String, onDismiss: () -> Unit = {}) =
        show(Request(Kind.ALERT, title, message, context.getString(R.string.ui_ok), "") { _, _, _ -> onDismiss() })

    fun prompt(context: Context, title: String, message: String, defaultValue: String?, onResult: (String?) -> Unit) =
        show(Request(Kind.PROMPT, title, message, context.getString(R.string.ui_ok),
            context.getString(R.string.action_cancel), defaultValue.orEmpty()) { accepted, value, _ ->
            onResult(if (accepted) value else null)
        })

    fun credentials(context: Context, host: String, realm: String,
        onResult: (username: String, password: String) -> Unit, onCancel: () -> Unit,
    ) = show(Request(Kind.CREDENTIALS, context.getString(R.string.ui_authentication_required),
        context.getString(R.string.ui_requires_a_login, host, if (realm.isNotBlank()) " (" + realm + ")" else ""),
        context.getString(R.string.ui_sign_in), context.getString(R.string.action_cancel)) { accepted, user, pass ->
        if (accepted) onResult(user, pass) else onCancel()
    })

    @Composable
    fun Render() {
        val request = active ?: return
        val res = localizedResources()
        var value by remember(request) { mutableStateOf(request.defaultValue) }
        var password by remember(request) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { answer(request, false) },
            title = { Text(request.title.take(512)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.message.take(16000))
                    if (request.kind == Kind.PROMPT) OutlinedTextField(value, { value = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (request.kind == Kind.CREDENTIALS) {
                        OutlinedTextField(value, { value = it }, label = { Text(res.getString(R.string.ui_username)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text(res.getString(R.string.ui_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { answer(request, true, value, password) }) { Text(request.positive) } },
            dismissButton = { if (request.kind != Kind.ALERT) TextButton(onClick = { answer(request, false) }) { Text(request.negative) } })
    }
}

/**
 * Adds a bookmark with editable defaults from the current page.
 *
 * The dialog owns the draft so cancelling cannot accidentally mutate browser state. URL
 * validation uses the same classifier as the omnibar; consequently a schemeless host such
 * as `example.com` is accepted while a search phrase or an unsafe javascript URL is not.
 */
@Composable
fun BookmarkEditDialog(
    initialTitle: String,
    initialUrl: String,
    onSave: (title: String, url: String, addToHome: Boolean) -> Unit,
    onDismiss: () -> Unit,
    isEditing: Boolean = false,
) {
    // Key both fields.  A user can open the editor on two pages with the same title but
    // different URLs; keying only one state would otherwise reuse the previous URL draft.
    var bookmarkTitle by remember(initialTitle, initialUrl) { mutableStateOf(initialTitle) }
    var bookmarkUrl by remember(initialTitle, initialUrl) { mutableStateOf(initialUrl) }
    var addToHome by remember(initialTitle, initialUrl) { mutableStateOf(false) }
    val trimmedTitle = bookmarkTitle.trim()
    val trimmedUrl = bookmarkUrl.trim()
    val isUrlValid = UrlUtils.isNavigableInput(trimmedUrl)
    val scheme = UrlUtils.schemeOf(trimmedUrl)
    val canAddToHome = isUrlValid && (scheme.isEmpty() || scheme == "http" || scheme == "https")
    val canSave = trimmedTitle.isNotEmpty() && isUrlValid

    fun save() {
        if (canSave) onSave(trimmedTitle, trimmedUrl, addToHome && canAddToHome)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.library_edit_bookmark else R.string.bookmark_editor_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = bookmarkTitle,
                    onValueChange = { bookmarkTitle = it.take(MAX_BOOKMARK_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.bookmark_edit_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bookmarkUrl,
                    onValueChange = { bookmarkUrl = it.take(MAX_BOOKMARK_URL_LENGTH) },
                    label = { Text(stringResource(R.string.bookmark_edit_url)) },
                    singleLine = true,
                    isError = trimmedUrl.isNotEmpty() && !isUrlValid,
                    supportingText = if (trimmedUrl.isNotEmpty() && !isUrlValid) {
                        { Text(stringResource(R.string.bookmark_invalid_url)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canAddToHome) { addToHome = !addToHome }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = addToHome && canAddToHome,
                        onCheckedChange = { addToHome = it },
                        enabled = canAddToHome,
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = stringResource(R.string.bookmark_add_to_home),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (canAddToHome) {
                                stringResource(R.string.bookmark_add_to_home_hint)
                            } else {
                                stringResource(R.string.bookmark_add_to_home_unavailable)
                            },
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = canSave) {
                Text(stringResource(R.string.bookmark_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private const val MAX_BOOKMARK_TITLE_LENGTH = 512
private const val MAX_BOOKMARK_URL_LENGTH = 8_192

/**
 * Compose text input dialog for settings and other Compose screens.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(context.getString(R.string.ui_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.action_cancel))
            }
        },
    )
}
