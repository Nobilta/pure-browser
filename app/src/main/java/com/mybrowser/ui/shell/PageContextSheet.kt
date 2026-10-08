package com.mybrowser.ui.shell

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
import com.mybrowser.filter.BlockRules

/**
 * What a long press on the page offers.
 *
 * The blocking rows show the rule they are about to write. A rule is text the user will have to
 * recognise later, in a list they can only edit as text, so the moment it is created is the one
 * chance to show them what it says — and the only way to tell which of two similar rows was the
 * broad one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageContextSheet(
    target: PageContextTarget,
    onOpen: (String, Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSaveImage: (String) -> Unit,
    onBlockResource: (String) -> Unit,
    onBlockHost: (String) -> Unit,
    onHideHere: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BrowserBottomSheet(onDismissRequest = onDismiss) {
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
            // One address, so one set of block rows: see PageContextTarget.blockAddress for why the
            // image wins when the press was on one that is also a link.
            val address = target.blockAddress
            val addressRule = BlockRules.addressRule(address)
            val hostRule = BlockRules.hostRule(address)
            if (addressRule != null || hostRule != null || target.imageUrl != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                addressRule?.let { rule ->
                    BrowserActionRow(R.drawable.ic_block, stringResource(R.string.context_block_address), rule) { onBlockResource(rule) }
                }
                hostRule?.let { rule ->
                    BrowserActionRow(R.drawable.ic_shield, stringResource(R.string.context_block_host), rule) { onBlockHost(rule) }
                }
                target.imageUrl?.let { image ->
                    BrowserActionRow(R.drawable.ic_close, stringResource(R.string.context_hide_this_page), resourceName(image)) { onHideHere(image) }
                }
            }
        }
    }
}

/** The name a URL gives its file; the host when it has no path to name one by. */
private fun resourceName(url: String): String {
    val path = url.substringAfter("://", url).substringBefore('?').substringBefore('#')
    return path.substringAfterLast('/', "").ifBlank { UrlUtils.hostOf(url).orEmpty() }
}
