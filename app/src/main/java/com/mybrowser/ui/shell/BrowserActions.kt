package com.mybrowser.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.mybrowser.R
import com.mybrowser.ui.theme.BrowserColors

/** One title, spacing and action layout for every browser-owned sheet/page. */
@Composable
internal fun BrowserSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // A page change keeps the header in place: the title crossfades and the leading inset glides
    // when a page swaps its close button for a back button, instead of both jumping.
    val startPadding by animateDpAsState(
        targetValue = if (onBack == null) 24.dp else 4.dp,
        animationSpec = BrowserMotion.chromeSpatial(),
        label = "sheetHeaderStart",
    )
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .padding(start = startPadding, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { BrowserIconAction(R.drawable.ic_ui_back, stringResource(R.string.cd_back), onClick = it) }
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                fadeIn(BrowserMotion.contentCross()) togetherWith fadeOut(BrowserMotion.contentCross())
            },
            label = "sheetHeaderTitle",
            modifier = Modifier.weight(1f),
        ) { shown ->
            Text(shown, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
        onDismiss?.let { BrowserIconAction(R.drawable.ic_ui_close, stringResource(R.string.ui_close), onClick = it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserIconAction(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(painterResource(icon), label, Modifier.size(24.dp))
        }
    }
}

/**
 * One action in a sheet.
 *
 * [detail] carries the exact thing the action will write — a rule, a file name. It is not a
 * description of the action; the label already does that. An action whose result a user cannot
 * predict from its label has to show the result it is about to produce.
 */
@Composable
internal fun BrowserActionRow(icon: Int, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The one search field every library sheet uses.
 *
 * A 44dp pill on the interactive-pill base, not an MD3 `OutlinedTextField`: the outlined field
 * carries a floating label, a 56dp form height and its own inset, none of which belong in a sheet
 * header. Only real focus draws the 1.5dp accent ring, and the clear action appears only when there
 * is text to clear.
 */
@Composable
internal fun LibrarySearchField(
    query: String,
    label: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (focused) colors.surfaceContainerLow else colors.surfaceContainerHigh,
        border = if (focused) BorderStroke(1.5.dp, colors.primary) else null,
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 44.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = { onQueryChange(it.take(256)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ui_search),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        BrowserIconAction(
                            icon = R.drawable.ic_ui_close,
                            label = stringResource(R.string.cd_clear),
                        ) { onQueryChange("") }
                    }
                }
            },
        )
    }
}

@Composable
internal fun LibrarySelectionBar(
    visible: Boolean,
    allSelected: Boolean,
    onToggleSelectAll: (Boolean) -> Unit,
    countText: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = BrowserMotion.chromeFade()) +
            expandVertically(animationSpec = BrowserMotion.chromeSpatial()),
        exit = fadeOut(animationSpec = BrowserMotion.chromeFade()) +
            shrinkVertically(animationSpec = BrowserMotion.chromeSpatial()),
    ) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val selectAll = stringResource(R.string.library_select_all)
            Checkbox(
                checked = allSelected,
                onCheckedChange = onToggleSelectAll,
                modifier = Modifier.semantics { contentDescription = selectAll },
            )
            Text(countText, Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
internal fun LibraryFooter(loading: Boolean, hasMore: Boolean, onLoadMore: () -> Unit, error: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else if (hasMore || error) TextButton(onClick = onLoadMore) {
            Text(stringResource(if (error) R.string.library_retry else R.string.library_load_more))
        }
    }
}

@Composable
internal fun BrowserDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, BrowserColors.cardBorder),
        content = content,
    )
}

@Composable
internal fun LibraryItemMenu(onOpenNewTab: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BrowserIconAction(R.drawable.ic_ui_more, stringResource(R.string.library_item_actions)) { expanded = true }
        BrowserDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onEdit != null) DropdownMenuItem(text = { Text(stringResource(R.string.library_edit_bookmark)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_ui_edit), null) },
                onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.context_open_new_tab)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_ui_add), null) },
                onClick = { expanded = false; onOpenNewTab() })
            DropdownMenuItem(text = { Text(stringResource(R.string.context_copy_link)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_ui_copy), null) },
                onClick = { expanded = false; onCopy() })
            DropdownMenuItem(text = { Text(stringResource(R.string.cd_delete)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_ui_delete), null) },
                onClick = { expanded = false; onDelete() })
        }
    }
}
