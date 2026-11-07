package com.mybrowser.ui.translate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mybrowser.R

/** Page-level translation control that survives closing the configuration sheet. */
@Composable
fun TranslationFloatingBar(
    translating: Boolean,
    onStop: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (translating) stringResource(R.string.translate_stop) else stringResource(R.string.translate_restore)
    Surface(
        onClick = { if (translating) onStop() else onRestore() },
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_ui_translate),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(2.dp))
            Icon(
                painterResource(R.drawable.ic_ui_close),
                // The whole pill is one action; the cross is a visual affordance, not a
                // second control, so it must not repeat the action's TalkBack label.
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
