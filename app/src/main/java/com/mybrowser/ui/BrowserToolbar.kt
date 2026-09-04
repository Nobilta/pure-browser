package com.mybrowser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R

/**
 * Bottom navigation bar: back, forward, home, reload/stop, tabs, menu.
 *
 * Bottom rather than top because it is thumb-reachable, matching what the lightweight
 * browsers settle on.
 *
 * The buttons are a local composable rather than MD3 `IconButton` for one reason: back
 * needs a long-press (clearing SPA history), and `IconButton` takes no long-click. Using
 * the same custom button for all keeps the ripple and disabled treatment uniform
 * instead of mixing two button implementations in one row.
 */
@Composable
fun BrowserToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onBackLongPress: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    tabCount: Int,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(
            icon = R.drawable.ic_back,
            description = R.string.cd_back,
            enabled = canGoBack,
            onClick = onBack,
            onLongClick = onBackLongPress,
        )
        ToolbarButton(
            icon = R.drawable.ic_forward,
            description = R.string.cd_forward,
            enabled = canGoForward,
            onClick = onForward,
        )
        ToolbarButton(
            icon = R.drawable.ic_home,
            description = R.string.cd_home,
            onClick = onHome,
        )
        TabsButton(
            count = tabCount,
            onClick = onTabs,
        )
        ToolbarButton(
            icon = R.drawable.ic_menu,
            description = R.string.cd_menu,
            onClick = onMenu,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ToolbarButton(
    icon: Int,
    description: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            modifier = Modifier.size(22.dp),
            // MD3 specifies disabled content as onSurface at 38%.
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

/**
 * Tabs button showing the current tab count.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.TabsButton(
    count: Int,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
