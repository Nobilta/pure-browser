package com.mybrowser.ui.devtools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.devtools.BodyPreview
import com.mybrowser.devtools.BodyReason
import com.mybrowser.devtools.BodyState
import com.mybrowser.devtools.DevToolsBudgets
import com.mybrowser.ui.theme.BrowserColors

internal fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

internal fun copyText(context: Context, text: String, message: String? = null) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val result = runCatching {
        clipboard?.setPrimaryClip(ClipData.newPlainText("Pure", text))
    }.isSuccess
    Toast.makeText(context, message ?: context.getString(
        if (result) R.string.dev_copied else R.string.dev_copy_failed
    ), Toast.LENGTH_SHORT).show()
}

@Composable
internal fun CopyButton(text: String, label: String? = null, enabled: Boolean = true) {
    val context = LocalContext.current
    TextButton(onClick = { copyText(context, text) }, enabled = enabled && text.isNotEmpty()) {
        Text(label ?: stringResource(R.string.dev_copy))
    }
}

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, BrowserColors.cardBorder),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                actions()
            }
            content()
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String, copyable: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(104.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectableText(value.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
        if (copyable) CopyButton(value)
    }
}

@Composable
internal fun BodyStateLine(body: BodyPreview) {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val color = when (body.state) {
        BodyState.FAILED -> MaterialTheme.colorScheme.error
        BodyState.TRUNCATED, BodyState.EVICTED -> BrowserColors.warning
        BodyState.COLLECTING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(resources.getString(body.state.label), color = color,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            body.reason.labelOrNull?.let {
                Spacer(Modifier.width(8.dp))
                Text(resources.getString(it), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (body.binary) {
            Text(
                resources.getString(
                    R.string.dev_body_binary,
                    body.contentType.ifBlank { resources.getString(R.string.dev_storage_unknown) },
                    if (body.bytes > 0) com.mybrowser.core.formatBytes(body.bytes) else resources.getString(R.string.dev_storage_unknown),
                ),
                Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Header list with one copy action for the whole block. */
internal fun headerText(headers: List<com.mybrowser.devtools.HeaderEntry>): String =
    headers.joinToString("\n") { "${it.name}: ${it.value}" }

@Composable
internal fun HeaderList(headers: List<com.mybrowser.devtools.HeaderEntry>, note: String?) {
    if (headers.isEmpty()) {
        Text(note ?: stringResource(R.string.dev_network_no_headers),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        SelectableText(headerText(headers), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall)
        note?.let {
            Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReasonText(reason: BodyReason) {
    reason.labelOrNull?.let {
        Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun highlightColor(token: SourceToken, palette: List<Color>): Color = palette[token.ordinal]

internal fun annotatedBlock(block: SourceBlock, palette: List<Color>): AnnotatedString =
    buildAnnotatedString {
        val text = block.text.removeSuffix("\n").removeSuffix("\r")
        append(text)
        block.spans.forEach { span ->
            val end = minOf(span.end, text.length)
            if (span.start < end) addStyle(
                SpanStyle(
                    color = highlightColor(span.token, palette),
                    fontWeight = if (span.token == SourceToken.KEYWORD) FontWeight.Medium else null,
                ),
                span.start,
                end,
            )
        }
    }

/** Renders highlighted code rows inside an existing LazyColumn. */
@Composable
internal fun SourceBlockRow(block: SourceBlock, palette: List<Color>, showLine: Boolean = true) {
    Row(Modifier.fillMaxWidth()) {
        if (showLine) Text(
            if (block.continuation) "↳" else block.line.toString(),
            Modifier.width(48.dp).padding(end = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                annotatedBlock(block, palette),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun rememberSourcePalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    BrowserColors.secure,
    MaterialTheme.colorScheme.onSurfaceVariant,
    MaterialTheme.colorScheme.primary,
    BrowserColors.warning,
    MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Splits plain text into bounded rows so one huge line cannot dominate layout. */
internal fun plainTextBlocks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val rows = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + DevToolsBudgets.DOM_TEXT_PREVIEW_CHARS, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start + 200) end = newline + 1
        }
        if (end < text.length && end > start + 1 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        rows += text.substring(start, end)
        start = end
    }
    return rows
}

@Composable
internal fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
