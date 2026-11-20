package com.mybrowser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.composed
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.mybrowser.R
import com.mybrowser.ui.components.BrowserGroupCard
import com.mybrowser.ui.components.BrowserGroupDivider
import com.mybrowser.ui.components.BrowserListItem
import com.mybrowser.ui.shell.BrowserSheetHeader

/** Shared rows and page layout used by settings categories and site settings. */
@Composable
internal fun SettingsToggle(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String = "",
    enabled: Boolean = true,
    contentPadding: PaddingValues? = null,
) {
    BrowserListItem(
        title = title,
        subtitle = summary,
        subtitleMaxLines = Int.MAX_VALUE,
        enabled = enabled,
        toggleState = checked,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.highlightSetting(title).then(modifier),
        contentPadding = contentPadding,
    )
}

/** Insets for notes that sit outside a ListItem row. */
internal val SettingsRowPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

@Composable
internal fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(SettingsRowPadding))
}

/**
 * One settings group as a rounded card: a small accent label above, then the rows on the card's
 * own surface. Rows are separated by a hairline inset from the edge instead of a full-width rule.
 */
@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val highlighted = title != null && LocalSettingHighlight.current == title
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        BrowserGroupCard(
            modifier = modifier,
            title = title,
            containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            content = content,
        )
    }
}

@Composable
internal fun SettingsDivider(inset: Dp = 16.dp) = BrowserGroupDivider(inset)

@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BrowserSheetHeader(title, onBack = onBack, actions = actions)
        Column(
            Modifier.weight(1f).fillMaxWidth().then(modifier)
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) { content() }
    }
}

@Composable
internal fun SettingsRadioItem(
    title: String,
    subtitle: String = "",
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    BrowserListItem(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = Int.MAX_VALUE,
        selected = selected,
        role = Role.RadioButton,
        onClick = onClick,
        leading = { RadioButton(selected = selected, onClick = null) },
        trailing = trailing,
        modifier = Modifier.highlightSetting(title),
    )
}

@Composable
internal fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconRes: Int? = null,
    selected: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val supporting = if (selected == true) colors.onSecondaryContainer.copy(alpha = 0.78f) else null
    BrowserListItem(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = Int.MAX_VALUE,
        iconRes = iconRes,
        onClick = onClick,
        trailing = {
            Icon(
                painterResource(R.drawable.ic_ui_chevron),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected == true) colors.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        containerColor = if (selected == true) colors.secondaryContainer else Color.Transparent,
        contentColorOverride = if (selected == true) colors.onSecondaryContainer else null,
        supportingColorOverride = supporting,
        leadingColorOverride = if (selected == true) colors.onSecondaryContainer else null,
        selected = selected,
        role = if (selected != null) Role.Tab else null,
        modifier = Modifier.highlightSetting(title),
    )
}

internal val LocalSettingHighlight = staticCompositionLocalOf<String?> { null }
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.highlightSetting(title: String): Modifier = settingHighlight(title, drawBackground = true)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.highlightSettingTarget(title: String): Modifier = settingHighlight(title, drawBackground = false)

private fun Modifier.settingHighlight(title: String, drawBackground: Boolean): Modifier = composed {
    val highlighted = LocalSettingHighlight.current == title
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) { if (highlighted) { kotlinx.coroutines.delay(250); requester.bringIntoView() } }
    bringIntoViewRequester(requester).then(
        if (highlighted && drawBackground) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
    )
}
