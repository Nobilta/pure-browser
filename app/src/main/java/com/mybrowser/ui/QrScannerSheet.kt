package com.mybrowser.ui

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mybrowser.R
import com.mybrowser.qr.QrCameraController
import com.mybrowser.qr.QrDecoder
import com.mybrowser.qr.qrWebUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Camera and photo scanning use the same offline decoder and result policy. */
@Composable
internal fun QrScannerSheet(onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = localizedResources()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf(false) }
    var cameraRevision by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var navigating by remember { mutableStateOf(false) }
    val accept: (String) -> Unit = { value ->
        if (result == null && !navigating) {
            flash = false
            val url = qrWebUrlOrNull(value)
            if (url == null) result = value else { navigating = true; onOpenUrl(url) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permitted = allowed
        requested = true
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                val text = withContext(Dispatchers.IO) { QrDecoder().image(context.contentResolver, uri) }
                if (text == null) error = resources.getString(R.string.qr_image_no_code) else accept(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = resources.getString(R.string.qr_image_failed) }
            finally { busy = false }
        }
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
            if (event == Lifecycle.Event.ON_PAUSE) flash = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!permitted && !requested) { requested = true; permission.launch(Manifest.permission.CAMERA) }
    }
    BrowserFullscreenSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(browserSheetInsets())) {
            BrowserSheetHeader(stringResource(if (result == null) R.string.qr_scan else R.string.qr_result_title), onBack = onDismiss)
            if (result != null) {
                val clipboard = LocalClipboard.current
                var copied by remember(result) { mutableStateOf(false) }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                    Text(result.orEmpty(), Modifier.fillMaxSize().verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    TextButton(onClick = { result = null; error = null; cameraError = false; cameraRevision++ }) { Text(stringResource(R.string.qr_scan_again)) }
                    FilledTonalButton(onClick = { scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(resources.getString(R.string.qr_result_title), result.orEmpty())))
                        copied = true
                    } }) {
                        Text(stringResource(if (copied) R.string.qr_copied else R.string.qr_copy))
                    }
                }
            } else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
                val controlsHeight = maxHeight * .5f
                val camera: @Composable (Modifier) -> Unit = { modifier ->
                    Surface(modifier.clip(MaterialTheme.shapes.large), color = Color.Black) {
                        if (permitted && !cameraError && !busy && !navigating) key(cameraRevision) {
                            CameraPreview(flash, onResult = accept, onError = { cameraError = true; flash = false }, onFlashAvailable = { hasFlash = it })
                        }
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                busy -> CircularProgressIndicator(Modifier.size(36.dp))
                                !permitted || cameraError -> Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(if (cameraError) R.string.qr_camera_unavailable else R.string.qr_permission_summary),
                                        color = Color.White, style = MaterialTheme.typography.bodyLarge)
                                }
                                else -> Box(Modifier.size(minOf(maxWidth, maxHeight) * .7f)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large))
                            }
                        }
                    }
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.qr_scan_hint), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.qr_scan_privacy), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        if (busy) Text(stringResource(R.string.qr_reading_image), style = MaterialTheme.typography.bodySmall)
                        if (!permitted) FilledTonalButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.qr_allow_camera)) }
                        if (!permitted && requested) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                        }) { Text(stringResource(R.string.qr_camera_settings)) }
                        if (cameraError) TextButton(onClick = { cameraError = false; cameraRevision++ }) { Text(stringResource(R.string.qr_retry)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { flash = false; imagePicker.launch(arrayOf("image/*")) }, enabled = !busy) {
                                Text(stringResource(R.string.qr_pick_image))
                            }
                            if (permitted && hasFlash && !cameraError) TextButton(onClick = { flash = !flash }, enabled = !busy) {
                                Text(stringResource(if (flash) R.string.qr_flash_off else R.string.qr_flash_on))
                            }
                        }
                    }
                }
                if (landscape) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    camera(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(280.dp).fillMaxHeight())
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    camera(Modifier.weight(1f).fillMaxWidth())
                    controls(Modifier.fillMaxWidth().heightIn(max = controlsHeight))
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(flash: Boolean, onResult: (String) -> Unit, onError: () -> Unit, onFlashAvailable: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    val flashAvailable by rememberUpdatedState(onFlashAvailable)
    val controller = remember(context) { QrCameraController(context, { result(it) }, { error() }, { flashAvailable(it) }) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.resume()
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.resume()
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    LaunchedEffect(controller, flash) { controller.setFlash(flash) }
    AndroidView(factory = { controller.preview }, modifier = Modifier.fillMaxSize())
}
