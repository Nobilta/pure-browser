package com.mybrowser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R

/**
 * The one grid tile for the browser menu.
 *
 * It owns the difference between the two kinds of tile the menu shows:
 *
 * - [isToggle] = true is a boolean state. The whole tile is a `Role.Switch`; an active tile is
 *   filled with `secondaryContainer` and ringed with the accent so colour is never the only signal.
 * - [isToggle] = false is an entry point to a panel or picker. It is a `Role.Button` with a chevron
 *   on the right, and it uses the same active treatment when its feature is running in the
 *   background (a cast session, an active translation).
 *
 * Disabled tiles drop every layer of content to the single 38% disabled alpha, and the title is
 * capped at two lines so a 200% font scale or a long translation cannot amputate the label.
 */
@Composable
fun BrowserTileCard(
    iconRes: Int,
    title: String,
    active: Boolean,
    isToggle: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = when {
        !active -> colors.surfaceContainerHigh
        enabled -> colors.secondaryContainer
        else -> colors.secondaryContainer.copy(alpha = 0.38f)
    }
    val border = when {
        !active -> null
        enabled -> BorderStroke(1.dp, colors.primary)
        else -> BorderStroke(1.dp, colors.primary.copy(alpha = 0.38f))
    }
    val baseTitleColor = if (active) colors.onSecondaryContainer else colors.onSurface
    val titleColor = if (enabled) baseTitleColor else baseTitleColor.copy(alpha = 0.38f)
    val subtitleColor = when {
        !enabled -> titleColor
        active -> colors.onSecondaryContainer.copy(alpha = 0.78f)
        else -> colors.onSurfaceVariant
    }
    val iconTint = when {
        !enabled -> titleColor
        active -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val interaction = if (isToggle) {
        Modifier.toggleable(
            value = active,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }
    val shape = MaterialTheme.shapes.medium

    Box(
        modifier = modifier
            .heightIn(min = 54.dp)
            .clip(shape)
            .background(containerColor, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!isToggle) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_chevron),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = subtitleColor,
                )
            }
        }
    }
}
