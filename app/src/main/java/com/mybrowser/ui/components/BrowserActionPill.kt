package com.mybrowser.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The unified capsule pill for secondary actions and toggleable utility states.
 *
 * - Resting state: borderless snow-mist capsule (`surfaceContainerHigh`) with calm `onSurface`
 *   label and `onSurfaceVariant` icon, avoiding raw Material `OutlinedButton` wireframes and naked
 *   `TextButton` asymmetry.
 * - Active toggle state (`isToggle = true` and `active = true`): filled with `secondaryContainer`
 *   plus a 1dp `primary` accent border (`Role.Switch` semantics) so state is never conveyed by hue
 *   alone, including when temporarily disabled (`0.38f` alpha).
 * - Accessibility: `defaultMinSize(minHeight = 48.dp)` and two-line wrapping under 200% font scale.
 */
@Composable
fun BrowserActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    active: Boolean = false,
    isToggle: Boolean = false,
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
    val baseLabelColor = if (active) colors.onSecondaryContainer else colors.onSurface
    val labelColor = if (enabled) baseLabelColor else baseLabelColor.copy(alpha = 0.38f)
    val iconTint = when {
        !enabled -> labelColor
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

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .then(if (border != null) Modifier.border(border, CircleShape) else Modifier)
            .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconTint,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
