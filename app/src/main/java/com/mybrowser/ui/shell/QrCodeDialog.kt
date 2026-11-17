package com.mybrowser.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** The current page as a QR code, with the address readable underneath for anything that fails. */
@Composable
fun QrCodeDialog(url: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val bitmap = remember(url) { QrCode.encode(url) }
    BrowserAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_encode_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (bitmap != null) {
                    // White on purpose: a camera must see the quiet zone, not the dialog's surface.
                    Surface(shape = MaterialTheme.shapes.medium, color = Color.White) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_encode_title),
                            modifier = Modifier.padding(8.dp).size(220.dp),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.qr_encode_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            if (bitmap != null) {
                TextButton(onClick = { onCopy(url) }) { Text(stringResource(R.string.context_copy_link)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } },
    )
}
