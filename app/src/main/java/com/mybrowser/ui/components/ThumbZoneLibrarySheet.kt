package com.mybrowser.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.shell.BrowserBottomSheet
import com.mybrowser.ui.shell.BrowserSheetHeader
import com.mybrowser.ui.shell.LibrarySearchField

/**
 * The thumb-zone library panel: header, fixed search, one scroll viewport, bottom primary action.
 *
 * Search sits directly under the header so the soft keyboard can never squeeze the list out of the
 * sheet, and the primary action sits at the bottom where the thumb already is. The content
 * lifecycle is resolved here — error before loading before empty before content — so a library
 * sheet only supplies results and one retry callback.
 */
@Composable
fun ThumbZoneLibrarySheet(
    title: String,
    query: String,
    searchLabel: String,
    primaryActionText: String,
    onQueryChange: (String) -> Unit,
    onPrimaryAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    primaryActionIconRes: Int = R.drawable.ic_ui_add,
    canExecutePrimaryAction: Boolean = true,
    isLoading: Boolean = false,
    errorText: String? = null,
    onRetry: (() -> Unit)? = null,
    isEmpty: Boolean = false,
    emptyText: String = "",
    filterBar: (@Composable () -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    BrowserBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp),
        ) {
            BrowserSheetHeader(
                title = title,
                onDismiss = onDismiss,
                actions = headerActions,
            )
            LibrarySearchField(
                query = query,
                label = searchLabel,
                onQueryChange = onQueryChange,
            )
            filterBar?.invoke()
            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    errorText != null -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onRetry != null) {
                            BrowserActionPill(
                                text = stringResource(R.string.library_retry),
                                iconRes = R.drawable.ic_ui_refresh,
                                onClick = onRetry,
                            )
                        }
                    }

                    isEmpty && !isLoading -> Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                    else -> content()
                }
            }
            Button(
                onClick = onPrimaryAction,
                enabled = canExecutePrimaryAction,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(primaryActionIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = primaryActionText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
