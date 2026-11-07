package com.mybrowser.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.UrlUtils

/**
 * The address bar: one 40dp visual capsule on a 48dp touch-height row.
 *
 * Built from [BasicTextField] inside a shaped [Box] rather than the MD3 `TextField`, which carries
 * label and indicator decorations and a 56dp minimum intended for forms. The field rests on the
 * interactive-pill base with no outline; only real focus adds the 1.5dp accent ring, so the resting
 * chrome stays quiet and the editing state is unmistakable.
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
    isHomePage: Boolean = false,
    onScanQr: () -> Unit = {},
    isIncognito: Boolean = false,
    hasPrivateIsolation: Boolean = true,
    isReaderActive: Boolean = false,
    onToggleReader: () -> Unit = {},
) {
    val textResources = localizedResources()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // A field that has never held focus also reports false on its first composition; only a
    // transition away from real focus may end the editing session.
    var hadFocus by remember { mutableStateOf(false) }
    val barHeight = 48.dp
    val pillHeight = 40.dp
    val shape = CircleShape
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
        if (value.text.isNotBlank()) onNavigate(value.text)
        focusManager.clearFocus()
        onFocusChange(false)
        keyboard?.hide()
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = barHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = barHeight)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = pillHeight)
                    .clip(shape)
                    .background(
                        if (isFocused) MaterialTheme.colorScheme.surfaceContainerLow
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    .then(
                        if (isFocused) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                        else Modifier,
                    )
                    .padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                // The indicator describes the loaded page, so it is hidden while editing. On the
                // native homepage there is no connection to report.
                AnimatedVisibility(
                    visible = !isFocused && !isHomePage,
                    enter = fadeIn(BrowserMotion.chromeFade()) +
                        expandHorizontally(BrowserMotion.chromeSpatial(), expandFrom = Alignment.Start),
                    exit = fadeOut(BrowserMotion.chromeFade()) +
                        shrinkHorizontally(BrowserMotion.chromeSpatial(), shrinkTowards = Alignment.Start),
                ) {
                    SecurityIndicator(
                        url = currentUrl,
                        certificateError = certificateError,
                        onClick = onSecurityClick,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }

                if (isIncognito) Row(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                        .clearAndSetSemantics {
                            contentDescription = textResources.getString(
                                if (hasPrivateIsolation) R.string.private_isolated else R.string.private_shared,
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_incognito),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (!hasPrivateIsolation) Text(
                        text = stringResource(R.string.private_shared_short),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                AnimatedContent(
                    targetState = isFocused,
                    transitionSpec = {
                        fadeIn(BrowserMotion.contentCross()) togetherWith
                            fadeOut(BrowserMotion.chromeFade())
                    },
                    contentAlignment = Alignment.CenterStart,
                    label = "omnibarContent",
                    modifier = Modifier.weight(1f),
                ) { editing ->
                    if (!editing) {
                        val host = currentUrl?.let(UrlUtils::hostOf).orEmpty()
                        val label = displayTitle.ifBlank {
                            host.ifBlank { stringResource(R.string.omnibar_hint) }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFocusChange(true) }
                                .semantics { contentDescription = textResources.getString(R.string.ui_edit_address) }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = textResources.getString(R.string.ui_address_field) }
                            // Focus can leave the field without any business callback: a
                            // hardware Tab to Go, accessibility focus, or the window losing focus.
                            // Mirror both directions; otherwise the overlay and Back handler stay
                            // attached to an editor that is no longer focused.
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    hadFocus = true
                                    onFocusChange(true)
                                } else if (hadFocus) {
                                    hadFocus = false
                                    onFocusChange(false)
                                }
                            },
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
                }

                // Editing shows a clear button only while there is text to clear; at rest the
                // slot is always stop/reload, which is the one action the chrome owes the page.
                if (isFocused) {
                    if (value.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onClear()
                                focusRequester.requestFocus()
                                keyboard?.show()
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_close),
                                contentDescription = stringResource(R.string.cd_clear),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // Reading view is a state the page is in, so the exit lives in the chrome it
                    // affects, directly before the reload action.
                    if (!isHomePage && isReaderActive) Box(
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .height(36.dp)
                            .widthIn(min = 44.dp)
                            .clip(CircleShape)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggleReader)
                            .semantics {
                                contentDescription = textResources.getString(R.string.reader_entered)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .widthIn(min = 44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ui_reader),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                text = "×",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                    }
                    // The homepage has no page to reload; the slot keeps the QR entry the
                    // homepage has always offered, and every real page keeps stop/reload.
                    if (isHomePage) IconButton(
                        onClick = onScanQr,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_qr),
                            contentDescription = stringResource(R.string.qr_scan),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(if (isLoading) R.drawable.ic_ui_close else R.drawable.ic_ui_refresh),
                            contentDescription = stringResource(if (isLoading) R.string.cd_stop else R.string.cd_reload),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }

            // A visible action keeps navigation usable when a hardware/soft keyboard is not
            // available. The same URL-vs-search classifier as the navigation path drives the
            // label, so a host reads "访问" and ordinary text reads "搜索".
            AnimatedVisibility(
                visible = isFocused,
                enter = fadeIn(BrowserMotion.chromeFade()) +
                    expandHorizontally(BrowserMotion.localArrive(), expandFrom = Alignment.End),
                exit = fadeOut(BrowserMotion.chromeFade()) +
                    shrinkHorizontally(BrowserMotion.localDepart(), shrinkTowards = Alignment.End),
            ) {
                TextButton(
                    onClick = ::submitInput,
                    enabled = value.text.isNotBlank(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(start = 4.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
