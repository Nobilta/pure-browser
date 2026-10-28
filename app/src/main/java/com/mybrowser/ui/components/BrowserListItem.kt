package com.mybrowser.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The one menu/settings row.
 *
 * Material 3's ListItem owns the height (56dp one line, 72dp two lines), content insets and text
 * styles; this wrapper only adds the browser rules around it: an optional leading icon, a
 * destructive colour, switch semantics for a whole-row toggle, and long-press. Menus and settings
 * previously had separate rows and drifted apart in height, icon size and supporting-text style.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserListItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    iconRes: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    toggleState: Boolean? = null,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        destructive -> colors.error
        else -> colors.onSurface
    }
    val supportingColor = when {
        !enabled -> contentColor
        destructive -> colors.error
        else -> colors.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }
    val shaped = modifier.clip(MaterialTheme.shapes.large)
    val interactive = when {
        !enabled -> shaped
        selected != null -> shaped.selectable(
            selected = selected,
            enabled = true,
            role = role ?: Role.RadioButton,
            onClick = onClick,
        )
        toggleState != null -> shaped.toggleable(
            value = toggleState,
            enabled = true,
            role = Role.Switch,
            interactionSource = interaction,
            indication = ripple(),
            onValueChange = { onClick() },
        )
        onLongClick != null -> shaped.combinedClickable(
            interactionSource = interaction,
            indication = ripple(),
            role = role,
            onClick = onClick,
            onLongClick = onLongClick,
        )
        else -> shaped.clickable(
            interactionSource = interaction,
            indication = ripple(),
            enabled = true,
            role = role,
            onClick = onClick,
        )
    }
    ListItem(
        modifier = interactive,
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
        },
        supportingContent = if (subtitle.isNotEmpty()) {
            {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else null,
        leadingContent = leading ?: iconRes?.let {
            {
                Icon(
                    painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = contentColor,
                )
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = contentColor,
            supportingColor = supportingColor,
            leadingIconColor = contentColor,
        ),
    )
}
