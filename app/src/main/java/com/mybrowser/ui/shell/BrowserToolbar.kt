package com.mybrowser.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.mybrowser.R

/**
 * Bottom navigation bar: back, forward, home, reload/stop, tabs, menu.
 *
 * Bottom rather than top because it is thumb-reachable, matching what the lightweight
 * browsers settle on.
 *
 * The buttons are a local composable rather than MD3 `IconButton` for one reason: back
 * needs a long-press (choosing earlier history), and `IconButton` takes no long-click. Using
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
    isIncognito: Boolean = false,
    onNewTab: () -> Unit = onTabs,
    swipeTabs: Boolean = false,
    onSwitchTab: (Int) -> Unit = {},
    /** The dock already draws its own divider above the address bar. */
    bottomAddressBar: Boolean = false,
) {
    val switchTab by rememberUpdatedState(onSwitchTab)
    val haptics = LocalHapticFeedback.current
    Column(modifier = modifier.fillMaxWidth()) {
    if (!bottomAddressBar) HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(swipeTabs) {
                if (swipeTabs) {
                    var distance = 0f
                    detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
                        onDragEnd = {
                            if (kotlin.math.abs(distance) >= 72.dp.toPx()) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                switchTab(if (distance < 0) 1 else -1)
                            }
                        },
                        onHorizontalDrag = { change, delta -> change.consume(); distance += delta })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(
            icon = R.drawable.ic_ui_back,
            description = R.string.cd_back,
            enabled = canGoBack,
            onClick = onBack,
            onLongClick = onBackLongPress,
        )
        ToolbarButton(
            icon = R.drawable.ic_ui_forward,
            description = R.string.cd_forward,
            enabled = canGoForward,
            onClick = onForward,
        )
        ToolbarButton(
            icon = R.drawable.ic_ui_home,
            description = R.string.cd_home,
            onClick = onHome,
        )
        TabsButton(
            count = tabCount,
            onClick = onTabs,
            onLongClick = onNewTab,
            isIncognito = isIncognito,
        )
        ToolbarButton(
            icon = R.drawable.ic_ui_menu,
            description = R.string.cd_menu,
            onClick = onMenu,
        )
    }
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
    // MD3 specifies disabled content as onSurface at 38%. A button only becomes disabled when the
    // page it acts on changes state, and that change is already visible elsewhere; fading the tint
    // over the effects spring ties the two together instead of the icon blinking.
    val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val enabledTint = MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(
        targetValue = if (enabled) enabledTint else disabledTint,
        animationSpec = BrowserMotion.chromeFade(),
        label = "toolbarButtonTint",
    )
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
            tint = tint,
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
    onLongClick: () -> Unit,
    isIncognito: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val container = if (isIncognito) colors.secondaryContainer else Color.Transparent
    val outline = if (isIncognito) colors.primary else colors.onSurfaceVariant
    val content = if (isIncognito) colors.onSecondaryContainer else colors.onSurfaceVariant
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(R.string.tabs_count, count)
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.new_tab_shortcut),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                .background(container, MaterialTheme.shapes.extraSmall)
                .border(
                    width = 1.75.dp,
                    color = outline,
                    shape = MaterialTheme.shapes.extraSmall,
                )
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = content,
            )
        }
    }
}
