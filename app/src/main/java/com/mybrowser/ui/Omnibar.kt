package com.mybrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mybrowser.core.UrlUtils
import com.mybrowser.R
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager
import com.mybrowser.ui.theme.BrowserColors

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
    securityLevel: BrowserState.SecurityLevel,
    modifier: Modifier = Modifier,
    currentUrl: String? = null,
    onSecurityClick: () -> Unit = {},
    bookmarkManager: BookmarkManager? = null,
    historyManager: HistoryManager? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
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
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
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
                        onClick = onSecurityClick,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onFocusChange(it.isFocused) },
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
                    IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
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
                        modifier = Modifier.size(32.dp),
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
                        .height(40.dp)
                        .padding(start = 4.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }

        // Smart suggestions dropdown
        if (isFocused && value.text.isNotEmpty() &&
            bookmarkManager != null && historyManager != null
        ) {
            SmartSuggestions(
                query = value.text,
                bookmarkManager = bookmarkManager,
                historyManager = historyManager,
                onSuggestionClick = { url ->
                    onNavigate(url)
                    focusManager.clearFocus()
                    keyboard?.hide()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 44.dp)
            )
        }
    }
}
