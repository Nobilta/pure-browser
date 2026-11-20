package com.mybrowser.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mybrowser.ui.components.BrowserActionPill
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.reader.ReaderStyle
import com.mybrowser.ui.theme.BrowserColors

/**
 * The reader's floating typography bar: size, line spacing and paper, applied to the live article.
 *
 * It is only shown while an article is on screen, and every control writes through to the saved
 * preference so the next article starts with the same typography.
 */
@Composable
internal fun ReaderStyleBar(
    style: ReaderStyle,
    onChange: (ReaderStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, BrowserColors.cardBorder),
        shadowElevation = 2.dp,
    ) {
        val smaller = stringResource(R.string.reader_font_smaller)
        val larger = stringResource(R.string.reader_font_larger)
        val spacing = stringResource(spacingLabel(style.lineHeight))
        val paper = stringResource(paperLabel(style.paper))
        val spacingDescription = stringResource(R.string.reader_line_spacing_value, spacing)
        val paperDescription = stringResource(R.string.reader_paper_value, paper)
        FlowRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrowserActionPill("A−", { onChange(style.copy(size = style.size - ReaderStyle.SIZE_STEP)) },
                description = smaller, enabled = style.size > ReaderStyle.MIN_SIZE)
            BrowserActionPill("A+", { onChange(style.copy(size = style.size + ReaderStyle.SIZE_STEP)) },
                description = larger, enabled = style.size < ReaderStyle.MAX_SIZE)
            BrowserActionPill(spacing, { onChange(style.copy(lineHeight = nextLineHeight(style.lineHeight))) },
                description = spacingDescription)
            BrowserActionPill(paper, { onChange(style.copy(paper = nextPaper(style.paper))) },
                description = paperDescription)
        }
    }
}

private fun spacingLabel(value: Float): Int = when (value) {
    1.5f -> R.string.reader_spacing_tight
    2.0f -> R.string.reader_spacing_loose
    else -> R.string.reader_spacing_normal
}

private fun nextLineHeight(value: Float): Float {
    val index = ReaderStyle.LINE_HEIGHTS.indexOfFirst { it == value }.takeIf { it >= 0 } ?: 1
    return ReaderStyle.LINE_HEIGHTS[(index + 1) % ReaderStyle.LINE_HEIGHTS.size]
}

private fun paperLabel(paper: ReaderStyle.Paper): Int = when (paper) {
    ReaderStyle.Paper.SYSTEM -> R.string.reader_paper_system
    ReaderStyle.Paper.WARM -> R.string.reader_paper_warm
    ReaderStyle.Paper.DARK -> R.string.reader_paper_dark
}

private fun nextPaper(paper: ReaderStyle.Paper): ReaderStyle.Paper = when (paper) {
    ReaderStyle.Paper.SYSTEM -> ReaderStyle.Paper.WARM
    ReaderStyle.Paper.WARM -> ReaderStyle.Paper.DARK
    ReaderStyle.Paper.DARK -> ReaderStyle.Paper.SYSTEM
}
