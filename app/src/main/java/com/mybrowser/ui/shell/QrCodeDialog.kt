package com.mybrowser.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.components.QrCode
import com.mybrowser.ui.theme.BrowserColors

/** The current page as a QR code, with the address readable underneath for anything that fails. */
@Composable
fun QrCodeDialog(url: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    var loading by remember(url) { mutableStateOf(true) }
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.Default) { QrCode.encode(url) }
        loading = false
    }
    var copied by remember(url) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1500); copied = false }
    }
    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_encode_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val code = bitmap
                if (code != null) {
                    // White on purpose: a camera must see the quiet zone, not the dialog's surface.
                    Surface(
                        modifier = Modifier.widthIn(max = 236.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = Color.White,
                        border = BorderStroke(1.dp, BrowserColors.cardBorder),
                    ) {
                        Image(
                            bitmap = code.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_encode_title),
                            modifier = Modifier.padding(8.dp).fillMaxWidth().aspectRatio(1f),
                        )
                    }
                } else if (loading) {
                    Box(Modifier.widthIn(max = 236.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else {
                    Text(
                        stringResource(R.string.qr_encode_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = BrowserColors.recessedWell,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(url); copied = true }) {
                Text(stringResource(if (copied) R.string.plain_text_copied else R.string.context_copy_link))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } },
    )
}
