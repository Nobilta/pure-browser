package com.mybrowser.ui.shell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The activity's feedback state, so a dialog-hosted sheet can show the same snackbar.
 *
 * Sheets are separate Dialog windows: a host only in the activity window would sit behind the
 * scrim and a settings confirmation would be invisible. Both hosts observe one state; the visible
 * one is the sheet's while it is open and the activity's afterwards.
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }
