package com.mybrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mybrowser.core.UrlUtils
import com.mybrowser.R

/**
 * The address bar.
 *
 * Built from [BasicTextField] inside a shaped [Box] rather than the MD3 `TextField`.
 * `TextField` carries label and indicator decorations and a 56dp minimum intended for
 * forms; an omnibar needs to be about 40dp. The surrounding container reproduces the MD3
 * search-field spec instead — `colorSurfaceContainerHighest` on a fully rounded shape —
 * so this is a Material-correct surface, just not the form component.
 */
@Composable
fun Omnibar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    isFocused: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    certificateError: Boolean = false,
    currentUrl: String? = null,
    displayTitle: String = "",
    onSecurityClick: () -> Unit = {},
) {
    val textResources = localizedResources()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val barHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LaunchedEffect(isFocused) {
        if (isFocused) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    val actionLabel = stringResource(
        if (UrlUtils.isNavigableInput(value.text)) {
            R.string.omnibar_visit
        } else {
            R.string.omnibar_search
        },
    )

    fun submitInput() {
        // Keep the keyboard path useful even for an empty field: there is no navigation to
        // perform, but Go should still dismiss editing just like the visible action does.
        if (value.text.isNotBlank()) onNavigate(value.text)
        focusManager.clearFocus()
        onFocusChange(false)
        keyboard?.hide()
    }

    Box(modifier = modifier) {
        // The rounded surface is the editable field itself.  The search/visit action is a
        // sibling of this surface (rather than content inside it), so it never squeezes the
        // text or looks like part of the URL.  It is intentionally absent until the field
        // has focus; the compact, unfocused chrome only needs security and reload controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Hidden while focused: the indicator describes the loaded page, and keeping
                // it visible next to a half-typed URL would be claiming something untrue.
                if (!isFocused) {
                    SecurityIndicator(
                        url = currentUrl,
                        certificateError = certificateError,
                        onClick = onSecurityClick,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }

                if (!isFocused) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onFocusChange(true) }
                            .semantics { contentDescription = textResources.getString(R.string.ui_edit_address) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = displayTitle.ifBlank { stringResource(R.string.omnibar_hint) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        currentUrl?.let(UrlUtils::hostOf)?.takeIf { it.isNotBlank() }?.let { host ->
                            Text(
                                text = host,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = textResources.getString(R.string.ui_address_field) }
                        // IME candidate windows and initial attachment may report
                        // lost focus without the user finishing their editing session.
                        .onFocusChanged { if (it.isFocused) onFocusChange(true) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            submitInput()
                        },
                    ),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.omnibar_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                if (isFocused) {
                    IconButton(onClick = {
                        onClear()
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(R.string.cd_clear),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // Refresh/Stop button inside the address surface when not focused.
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isLoading) R.drawable.ic_stop else R.drawable.ic_reload,
                            ),
                            contentDescription = stringResource(
                                if (isLoading) R.string.cd_stop else R.string.cd_reload,
                            ),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // A visible action keeps navigation usable when a hardware/soft keyboard is not
            // available.  The same URL-vs-search classifier as the navigation path drives the
            // label, so a host reads "访问" and ordinary text reads "搜索".
            if (isFocused) {
                TextButton(
                    onClick = ::submitInput,
                    enabled = value.text.isNotBlank(),
                    modifier = Modifier
                        .height(48.dp)
                        .padding(start = 4.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }

    }
}
