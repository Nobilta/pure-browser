package com.mybrowser.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.data.BookmarkIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BookmarkIcon(url: String) {
    val context = LocalContext.current
    val bitmap = produceState<android.graphics.Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) { BookmarkIcons.read(context, url) }
    }.value
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(24.dp))
    else Icon(painterResource(R.drawable.ic_ui_globe), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
