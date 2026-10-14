package com.mybrowser.ui.shell

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

/** One accessible selection target for the whole row; its checkbox is only a visual indicator. */
fun Modifier.libraryItemSelection(
    selecting: Boolean,
    selected: Boolean,
    selectionLabel: String,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
): Modifier = this
    .then(if (selecting) Modifier.semantics { toggleableState = ToggleableState(selected) } else Modifier)
    .combinedClickable(
        role = if (selecting) Role.Checkbox else null,
        onClick = { if (selecting) onToggleSelection() else onOpen() },
        onLongClick = onToggleSelection,
        onLongClickLabel = selectionLabel,
    )
