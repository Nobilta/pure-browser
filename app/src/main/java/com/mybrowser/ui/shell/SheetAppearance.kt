package com.mybrowser.ui.shell

import android.os.Build
import android.view.ViewParent
import android.view.WindowManager.LayoutParams
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.mybrowser.R

/** A sheet has its own window; its system bars must follow the app's chosen theme. */
@Composable
internal fun ApplySheetSystemBars(fullscreen: Boolean = false) {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val surface = MaterialTheme.colorScheme.surface.toArgb()
    DisposableEffect(view, light, surface, fullscreen) {
        val window = generateSequence(view as? ViewParent ?: view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        // The route host runs its own exit in Compose; this window animation still covers the
        // overlays that leave by being unmounted, such as a picker opened by a page. Keeping it
        // means no window disappears without a transition while those entries are converted.
        window?.setWindowAnimations(R.style.BrowserSheetAnimation)
        // The pinned Material3 version configures its dialog window after composition.
        // Apply the app preference after that update instead of following the OS theme.
        val apply = Runnable {
            if (fullscreen && window != null) {
                // Dialog initialization can reset the constructor's edge-to-edge
                // flags. Apply them after attachment; content supplies safe insets.
                window.addFlags(LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.clearFlags(LayoutParams.FLAG_DIM_BEHIND)
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                        LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    if (Build.VERSION.SDK_INT >= 30) {
                        setFitInsetsTypes(0)
                        setFitInsetsSides(0)
                    }
                }
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                @Suppress("DEPRECATION")
                window.statusBarColor = surface
                @Suppress("DEPRECATION")
                window.navigationBarColor = surface
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            controller?.isAppearanceLightStatusBars = light
            controller?.isAppearanceLightNavigationBars = light
        }
        view.post(apply)
        onDispose {
            view.removeCallbacks(apply)
            if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller.isAppearanceLightNavigationBars = oldNavigation
        }
    }
}
