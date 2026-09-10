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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import com.mybrowser.core.UrlUtils
import com.mybrowser.R
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager

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
    bookmarkManager: BookmarkManager? = null,
    historyManager: HistoryManager? = null,
    suggestionsAbove: Boolean = false,
) {
    val textResources = localizedResources()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val barHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val density = LocalDensity.current
    val rootView = LocalView.current.rootView
    var windowHeight by remember { mutableStateOf(rootView.height) }
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val bottomInset = maxOf(WindowInsets.safeDrawing.getBottom(density), WindowInsets.ime.getBottom(density))
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val gap = with(density) { 4.dp.roundToPx() }
    val availableHeight = with(density) {
        (if (suggestionsAbove) anchor.top - topInset - gap
        else windowHeight - bottomInset - anchor.bottom - gap).coerceAtLeast(0f).toDp().coerceAtMost(400.dp)
    }
    val suggestionPosition = remember(suggestionsAbove, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val y = if (suggestionsAbove) anchorBounds.top - popupContentSize.height - gap else anchorBounds.bottom + gap
                return IntOffset(anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
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
        keyboard?.hide()
    }

    Box(modifier = modifier.onGloballyPositioned {
        anchor = it.boundsInWindow()
        windowHeight = rootView.height
    }) {
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
                    IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
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

        // Smart suggestions dropdown
        if (isFocused && value.text.isNotEmpty() &&
            bookmarkManager != null && availableHeight >= 48.dp
        ) {
            Popup(popupPositionProvider = suggestionPosition,
                properties = PopupProperties(focusable = false, dismissOnBackPress = false),
                onDismissRequest = { focusManager.clearFocus(); keyboard?.hide() }) {
            SmartSuggestions(
                query = value.text,
                bookmarkManager = bookmarkManager,
                historyManager = historyManager,
                onFillSuggestion = { text -> onValueChange(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) },
                onSuggestionClick = { url ->
                    onNavigate(url)
                    focusManager.clearFocus()
                    keyboard?.hide()
                },
                maxHeight = availableHeight,
                modifier = Modifier.width(with(density) { anchor.width.toDp() }),
            )
            }
        }
    }
}
