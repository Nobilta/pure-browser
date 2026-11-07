package com.mybrowser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mybrowser.ui.theme.BrowserColors

/**
 * The one grouped card primitive, shared by settings and browser sheets.
 *
 * On a pure-white canvas, structural cards stay pure white inside and define their boundary via a
 * crisp 1dp porcelain hairline ([BrowserColors.cardBorder]), while section headers stay calm
 * slate-grey (`onSurfaceVariant`) so accent blue is reserved for active states.
 */
@Composable
fun BrowserGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Aligned with the text edge of an icon-less BrowserListItem row.
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, BrowserColors.cardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun BrowserGroupDivider(inset: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = inset),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
