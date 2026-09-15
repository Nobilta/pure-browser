package com.mybrowser.ui.shell

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Observe locale changes with the Compose version used by this project. */
@Composable
internal fun localizedResources(): Resources {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        context.createConfigurationContext(configuration).resources
    }
}
