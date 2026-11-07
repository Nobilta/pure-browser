package com.mybrowser.ui.shell

import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mybrowser.R
import com.mybrowser.ui.theme.BrowserColors

/**
 * The app's alert dialog: Material 3's layout, colours and slots, plus the entrance that the pinned
 * Material 3 (1.4.0) does not provide — there `AlertDialog` appears without any transition, and the
 * `BasicAlertDialog` override hook that could add one is internal to the library.
 *
 * The container's scale is spatial and its opacity is an effect, so each runs on the spec Material 3
 * gives that kind of change instead of sharing one progress value. The exit belongs to the platform:
 * a caller removes this dialog by dropping it from the composition, and the dialog window's own
 * animation covers that, so there is nothing here to retain.
 *
 * Only the presentation is this wrapper's business — the platform dim still belongs to the window
 * and appears with it, and the answer callback still runs exactly once, at commit.
 */
@Composable
internal fun BrowserAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    buttonsInColumn: Boolean = false,
) {
    val shown = remember { Animatable(0f) }
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { shown.animateTo(1f, BrowserMotion.contentCross()) }
        scale.animateTo(1f, BrowserMotion.localArrive())
    }
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        Surface(
            modifier = Modifier.graphicsLayer {
                alpha = shown.value
                // A dialog's container is one of the surfaces Material 3 lets scale as it arrives;
                // the geometry is the app's, the spring it runs on is the scheme's.
                val grown = 0.96f + 0.04f * scale.value
                scaleX = grown
                scaleY = grown
            },
            shape = AlertDialogDefaults.shape,
            // A dialog floats on the 32% scrim: it is the brightest surface in the hierarchy,
            // framed by a crisp 1dp structural hairline.
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, BrowserColors.cardBorder),
            tonalElevation = 0.dp,
        ) {
            // Paddings, type scale and content colours mirror AlertDialogContent so a migrated
            // dialog is pixel-identical to the library's at rest.
            Column(Modifier.padding(24.dp)) {
                icon?.let {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.iconContentColor) {
                        Box(Modifier.padding(bottom = 16.dp).align(Alignment.CenterHorizontally)) { it() }
                    }
                }
                title?.let {
                    CompositionLocalProvider(
                        LocalContentColor provides AlertDialogDefaults.titleContentColor,
                        LocalTextStyle provides MaterialTheme.typography.headlineSmall,
                    ) {
                        Box(
                            Modifier.padding(bottom = 16.dp)
                                .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally),
                        ) { it() }
                    }
                }
                text?.let {
                    CompositionLocalProvider(
                        LocalContentColor provides AlertDialogDefaults.textContentColor,
                        LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    ) {
                        Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp).align(Alignment.Start)) { it() }
                    }
                }
                if (buttonsInColumn) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dismissButton?.let { it() }
                        confirmButton()
                    }
                } else {
                    Box(Modifier.align(Alignment.End)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            dismissButton?.let { it() }
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BrowserConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = stringResource(R.string.action_cancel),
    enabled: Boolean = true,
) {
    BrowserAlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text(dismissText)
            }
        },
    )
}
