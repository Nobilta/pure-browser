package com.mybrowser.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.PageFailure
import com.mybrowser.core.PageFailureKind
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.ui.theme.BrowserColors

/**
 * The page could not load: centred directly on the pure-white/deep-navy canvas without an outer
 * box wrapper. The failure's URL and details sit in recessed code wells, the primary action is
 * retry, and the two secondary actions share identical capsule geometry.
 */
@Composable
fun PageRecovery(failure: PageFailure, onRetry: () -> Unit, onHome: () -> Unit, onSite: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_warning),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(if (failure.kind == PageFailureKind.RENDERER) R.string.recovery_crash else R.string.recovery_network),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.recovery_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            CodeBlock(text = failure.url, modifier = Modifier.padding(top = 18.dp))
            if (failure.details.isNotBlank()) {
                CodeBlock(text = failure.details, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = onRetry,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.recovery_retry))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrowserActionPill(
                    text = stringResource(R.string.recovery_home),
                    iconRes = R.drawable.ic_ui_home,
                    onClick = onHome,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                BrowserActionPill(
                    text = stringResource(R.string.site_settings),
                    iconRes = R.drawable.ic_ui_settings,
                    onClick = onSite,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = BrowserColors.recessedWell,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            maxLines = 6,
        )
    }
}

/**
 * Consent for a page's hand-off to another app.
 *
 * [appLabel] is the resolved application name; when it is null the prompt falls back to the
 * raw scheme, which is the only honest description of an unknown or ambiguous target.
 */
@Composable
fun ExternalAppDialog(
    origin: String,
    appLabel: String?,
    scheme: String,
    privateSession: Boolean,
    onAnswer: (Boolean, Boolean) -> Unit,
) {
    var rememberChoice by remember(origin, scheme) { mutableStateOf(false) }
    BrowserAlertDialog(onDismissRequest = { onAnswer(false, false) },
        title = { Text(if (appLabel != null) stringResource(R.string.external_title_app, appLabel) else stringResource(R.string.external_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (appLabel != null) stringResource(R.string.external_message_app, origin) else stringResource(R.string.external_message, origin, scheme))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(rememberChoice, role = Role.Checkbox) { rememberChoice = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberChoice, null)
                Text(stringResource(if (privateSession) R.string.site_remember_private else R.string.site_remember))
            }
        } },
        confirmButton = { TextButton(onClick = { onAnswer(true, rememberChoice) }) { Text(stringResource(R.string.site_allow)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false, rememberChoice) }) { Text(stringResource(R.string.site_block)) } })
}

data class BackHistoryEntry(val offset: Int, val title: String, val url: String)
@Composable
fun BackHistoryDialog(entries: List<BackHistoryEntry>, onSelect: (Int) -> Unit, onExitSite: () -> Unit, onDismiss: () -> Unit) {
    BrowserAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.back_history)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            entries.forEach { entry ->
                TextButton(onClick = { onSelect(entry.offset) }, modifier = Modifier.fillMaxWidth()) {
                    Text(entry.title.ifBlank { entry.url }, maxLines = 2)
                }
            }
            if (entries.isEmpty()) Text(stringResource(R.string.back_history_empty))
        } },
        confirmButton = { TextButton(onClick = onExitSite) { Text(stringResource(R.string.exit_current_site)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
