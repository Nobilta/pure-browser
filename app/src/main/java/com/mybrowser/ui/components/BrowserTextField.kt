package com.mybrowser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Compact form input using the same surface and focus tokens as library search. */
@Composable
fun BrowserTextField(value: String, label: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (focused) colors.surfaceContainerLow else colors.surfaceContainerHigh,
        border = if (focused) BorderStroke(1.5.dp, colors.primary) else null,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }.padding(12.dp),
            decorationBox = { input ->
                Box {
                    if (value.isEmpty()) Text(label, color = colors.onSurfaceVariant)
                    input()
                }
            },
        )
    }
}
