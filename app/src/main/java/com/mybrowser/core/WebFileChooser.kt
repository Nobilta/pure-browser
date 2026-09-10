package com.mybrowser.core

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

enum class CaptureKind { PHOTO, VIDEO }

internal fun captureKind(capture: Boolean, multiple: Boolean, accepts: List<String>): CaptureKind? {
    if (!capture || multiple) return null
    val values = accepts.map { it.lowercase(java.util.Locale.ROOT) }
    return when {
        values.isNotEmpty() && values.all { it.startsWith("image/") } -> CaptureKind.PHOTO
        values.isNotEmpty() && values.all { it.startsWith("video/") } -> CaptureKind.VIDEO
        else -> null
    }
}

/** Owns exactly one platform chooser and the document to which its result belongs. */
class WebFileChooser(private val activity: ComponentActivity, private val document: () -> Any?, private val onFailure: () -> Unit) {
    private data class Request(val callback: ValueCallback<Array<Uri>?>, val document: Any?,
        val kind: CaptureKind?, var delivered: Boolean = false, var file: File? = null, var uri: Uri? = null)
    private var pending: Request? = null
    private val directory = File(activity.cacheDir, "web-capture").apply { mkdirs() }
    private val granted = ArrayList<Pair<Uri, File>>()
    private val single = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        finish(uri?.let { arrayOf(it) })
    }
    private val multiple = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        finish(uris.take(64).toTypedArray().takeIf { it.isNotEmpty() })
    }
    private val capture = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pending
        val success = result.resultCode == Activity.RESULT_OK && request?.file?.length()?.let { it > 0 } == true
        finish(if (success) request?.uri?.let { arrayOf(it) } else null)
    }
    private val cameraPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val request = pending ?: return@registerForActivityResult
        if (allowed && isCurrent(request)) launchCapture(request) else finish(null)
    }

    init {
        // Process death cannot leave captured private images indefinitely. Files still in
        // use by this Activity are younger and removed on navigation or close below.
        val before = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        directory.listFiles()?.filter { it.isFile && it.lastModified() < before }?.forEach { it.delete() }
    }

    fun show(callback: ValueCallback<Array<Uri>?>, params: WebChromeClient.FileChooserParams): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        // A second launch while Android still owns a chooser would make the first
        // result indistinguishable from the second. Complete the new request as cancelled.
        if (pending != null) { callback.onReceiveValue(null); return true }
        if (granted.size >= 16) { callback.onReceiveValue(null); onFailure(); return true }
        val accepts = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }
            .filter { it.isNotEmpty() }.take(16).ifEmpty { listOf("*/*") }
        val many = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val request = Request(callback, document(), captureKind(params.isCaptureEnabled, many, accepts))
        pending = request
        try {
            if (request.kind != null) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    launchCapture(request)
                else cameraPermission.launch(Manifest.permission.CAMERA)
            } else if (many) multiple.launch(accepts.toTypedArray()) else single.launch(accepts.toTypedArray())
        } catch (_: Exception) { finish(null); onFailure() }
        return true
    }

    private fun isCurrent(request: Request) = !request.delivered && request.document == document() &&
        !activity.isFinishing && !activity.isDestroyed

    private fun launchCapture(request: Request) {
        if (!isCurrent(request)) { finish(null); return }
        try {
            val photo = request.kind == CaptureKind.PHOTO
            val file = File(directory, UUID.randomUUID().toString() + if (photo) ".jpg" else ".mp4")
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".captures", file)
            request.file = file; request.uri = uri
            val intent = Intent(if (photo) MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("capture", uri)
            if (!photo) intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            capture.launch(intent)
        } catch (_: Exception) { finish(null); onFailure() }
    }

    private fun finish(values: Array<Uri>?) {
        val request = pending ?: return
        pending = null
        val result = values?.filter { it.scheme == "content" }?.toTypedArray()?.takeIf { it.isNotEmpty() && isCurrent(request) }
        if (!request.delivered) {
            request.delivered = true
            request.callback.onReceiveValue(result)
        }
        val file = request.file
        val uri = request.uri
        if (file != null && uri != null) {
            // Camera needs write access only until it returns. WebView reads via our own
            // provider after the callback, so keep the file until this document leaves.
            activity.revokeUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (result == null) {
                activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                file.delete()
            } else granted.add(uri to file)
        }
    }

    fun cancelDocument() {
        pending?.let { request ->
            if (!request.delivered) { request.delivered = true; request.callback.onReceiveValue(null) }
        }
        granted.forEach { (uri, file) ->
            activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            file.delete()
        }
        granted.clear()
    }

    fun close() {
        cancelDocument()
        pending?.let { request ->
            request.uri?.let { activity.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            request.file?.delete()
        }
        pending = null
    }
}
