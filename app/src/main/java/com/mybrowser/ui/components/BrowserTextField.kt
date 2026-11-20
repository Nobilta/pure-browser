package com.mybrowser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Form input for dialogs: a caption above the snow-mist well, the same surface and focus tokens
 * as library search. The caption stays visible when the field is prefilled, unlike an in-well
 * floating label.
 */
@Composable
fun BrowserTextField(
    value: String,
    label: String?,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    errorMessage: String? = null,
    accessibilityLabel: String? = label,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier.height(IntrinsicSize.Min)) {
        if (!label.isNullOrBlank()) Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .alpha(if (enabled) 1f else 0.38f)
                .padding(start = 4.dp, bottom = 6.dp)
                .clearAndSetSemantics {},
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (focused) colors.surfaceContainerLow else colors.surfaceContainerHigh,
            border = when {
                errorMessage != null -> BorderStroke(1.5.dp, colors.error)
                focused -> BorderStroke(1.5.dp, colors.primary)
                else -> null
            },
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    enabled = enabled,
                    keyboardOptions = keyboardOptions,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
                        .alpha(if (enabled) 1f else 0.38f)
                        .semantics {
                            accessibilityLabel?.takeIf { it.isNotBlank() }?.let { contentDescription = it }
                            errorMessage?.let { error(it) }
                        }.heightIn(min = 48.dp),
                    decorationBox = { input ->
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    text = placeholder,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            input()
                        }
                    },
                )
                trailingIcon?.let { icon ->
                    Box(Modifier.alpha(if (enabled) 1f else 0.38f)) { icon() }
                }
            }
        }
        errorMessage?.let {
            Text(it, Modifier.padding(start = 4.dp, top = 6.dp).clearAndSetSemantics { testTag = "browser_text_field_error" },
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}
