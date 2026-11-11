package com.mybrowser.ui.shell

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.theme.BrowserColors

/**
 * What the picker is showing over the page.
 *
 * Two states rather than one nullable selection, because "up and waiting" and "an element is
 * chosen" are different things to say: the first is a prompt with nothing to write yet, and only
 * the second can be written down.
 */
sealed interface ElementPickerState {
    /** Up and waiting for the user to point at something. */
    data object Picking : ElementPickerState

    /** An element is chosen, with the text that would be written and how much of the page it covers. */
    data class Chosen(
        val selector: String,
        val matchCount: Int,
        val canParent: Boolean,
        val canChild: Boolean,
    ) : ElementPickerState
}

/**
 * The bar that runs a pick, over the page and out of its layout.
 *
 * It floats rather than taking a row: a bar that resized the page would reflow it, and the element
 * being outlined would move out from under the outline every time the bar changed height.
 *
 * The selector is shown exactly as it will be written. Two rules that hide the same thing on
 * screen can differ in how much of the page they take with them, and the match count beside it is
 * the only place that difference is visible before the rule is stored.
 */
@Composable
internal fun ElementPickerBar(
    state: ElementPickerState,
    onParent: () -> Unit,
    onChild: () -> Unit,
    onBlock: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val chosen = state as? ElementPickerState.Chosen
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, BrowserColors.cardBorder),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.picker_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                BrowserIconAction(R.drawable.ic_ui_close, stringResource(R.string.ui_close), onClick = onCancel)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onParent, enabled = chosen?.canParent == true) {
                    Icon(painterResource(R.drawable.ic_ui_arrow_up),
                        stringResource(R.string.picker_parent), Modifier.size(20.dp))
                }
                IconButton(onClick = onChild, enabled = chosen?.canChild == true) {
                    Icon(painterResource(R.drawable.ic_ui_arrow_down),
                        stringResource(R.string.picker_child), Modifier.size(20.dp))
                }
                if (chosen == null) {
                    Text(
                        text = stringResource(R.string.picker_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                } else {
                    Text(
                        text = chosen.selector,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Text(
                        text = pluralStringResource(R.plurals.picker_matches, chosen.matchCount, chosen.matchCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onBlock(false) },
                    enabled = chosen != null,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.picker_block_site), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                BrowserActionPill(
                    text = stringResource(R.string.picker_block_all),
                    enabled = chosen != null,
                    onClick = { onBlock(true) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}
