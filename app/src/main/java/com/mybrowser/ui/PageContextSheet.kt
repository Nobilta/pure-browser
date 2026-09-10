package com.mybrowser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.core.PageContextTarget
import com.mybrowser.core.UrlUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageContextSheet(
    target: PageContextTarget,
    onOpen: (String, Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSaveImage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val url = target.linkUrl ?: target.imageUrl.orEmpty()
            Text(target.title.ifBlank { UrlUtils.hostOf(url).orEmpty() },
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
            target.linkUrl?.let { link ->
                BrowserActionRow(R.drawable.ic_add, stringResource(R.string.context_open_new_tab)) { onOpen(link, false) }
                BrowserActionRow(R.drawable.ic_tabs, stringResource(R.string.context_open_background)) { onOpen(link, true) }
                BrowserActionRow(R.drawable.ic_copy, stringResource(R.string.context_copy_link)) { onCopy(link) }
                BrowserActionRow(R.drawable.ic_share, stringResource(R.string.context_share_link)) { onShare(link) }
            }
            target.imageUrl?.let { image ->
                if (target.linkUrl != null) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                BrowserActionRow(R.drawable.ic_open_in_new, stringResource(R.string.context_open_image)) { onOpen(image, false) }
                BrowserActionRow(R.drawable.ic_download, stringResource(R.string.context_save_image)) { onSaveImage(image) }
                BrowserActionRow(R.drawable.ic_copy, stringResource(R.string.context_copy_image_link)) { onCopy(image) }
            }
        }
    }
}
