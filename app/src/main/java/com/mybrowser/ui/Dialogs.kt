package com.mybrowser.ui

import androidx.compose.ui.platform.LocalContext
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
 * Platform AlertDialog helpers.
 *
 * Uses android.app.AlertDialog rather than the AppCompat or Material one, because
 * neither of those dependencies is present. The tradeoff is that styling follows the
 * platform theme instead of ours; acceptable for dialogs, which are rare and modal.
 */
object Dialogs {

    /**
     * Confirmation with a fixed title. [onResult] is invoked exactly once, including on
     * dismiss, which matters for WebView callbacks that leak or hang the page if they
     * are never answered.
     */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        positiveText: String = context.getString(R.string.ui_ok),
        negativeText: String = context.getString(R.string.action_cancel),
        onResult: (Boolean) -> Unit,
    ) {
        var answered = false
        fun answer(value: Boolean) {
            if (!answered) {
                answered = true
                onResult(value)
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> answer(true) }
            .setNegativeButton(negativeText) { _, _ -> answer(false) }
            // Covers back-press and outside-tap, both of which otherwise leave the
            // WebView's handler dangling forever.
            .setOnDismissListener { answer(false) }
            .show()
    }

    fun alert(
        context: Context,
        title: String,
        message: String,
        onDismiss: () -> Unit = {},
    ) {
        var done = false
        fun finish() {
            if (!done) {
                done = true
                onDismiss()
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(context.getString(R.string.ui_ok)) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    /** Single-field prompt. Passes null on cancel. */
    fun prompt(
        context: Context,
        title: String,
        message: String,
        defaultValue: String?,
        onResult: (String?) -> Unit,
    ) {
        var answered = false
        fun answer(value: String?) {
            if (!answered) {
                answered = true
                onResult(value)
            }
        }

        val input = EditText(context).apply {
            setText(defaultValue.orEmpty())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(wrap(context, input))
            .setPositiveButton(context.getString(R.string.ui_ok)) { _, _ -> answer(input.text.toString()) }
            .setNegativeButton(context.getString(R.string.action_cancel)) { _, _ -> answer(null) }
            .setOnDismissListener { answer(null) }
            .show()
    }

    /** Two-field credential prompt for HTTP Basic auth. */
    fun credentials(
        context: Context,
        host: String,
        realm: String,
        onResult: (username: String, password: String) -> Unit,
        onCancel: () -> Unit,
    ) {
        var answered = false

        val user = EditText(context).apply {
            hint = context.getString(R.string.ui_username)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pass = EditText(context).apply {
            hint = context.getString(R.string.ui_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(user)
            addView(pass)
        }

        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.ui_authentication_required))
            .setMessage(context.getString(R.string.ui_requires_a_login, host, if (realm.isNotEmpty()) " ($realm)" else ""))
            .setView(container)
            .setPositiveButton(context.getString(R.string.ui_sign_in)) { _, _ ->
                if (!answered) {
                    answered = true
                    onResult(user.text.toString(), pass.text.toString())
                }
            }
            .setNegativeButton(context.getString(R.string.action_cancel)) { _, _ ->
                if (!answered) {
                    answered = true
                    onCancel()
                }
            }
            .setOnDismissListener {
                if (!answered) {
                    answered = true
                    onCancel()
                }
            }
            .show()
    }

    /** Simple list chooser, for the menu and similar. */
    fun list(
        context: Context,
        title: String?,
        items: List<String>,
        onSelected: (Int) -> Unit,
    ) {
        AlertDialog.Builder(context)
            .apply { if (title != null) setTitle(title) }
            .setItems(items.toTypedArray()) { _, which -> onSelected(which) }
            .show()
    }

    private fun wrap(context: Context, child: android.view.View): ViewGroup =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(child)
        }

    /** Multi-line body for the SSL detail sheet. */
    fun detailText(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            textSize = 13f
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
