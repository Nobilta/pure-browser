package com.mybrowser.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Camera and QR work share a worker, acquireLatestImage drops frames under load. */
internal class QrCameraController(context: Context, private val onResult: (String) -> Unit,
    private val onError: () -> Unit, private val onFlashAvailable: (Boolean) -> Unit) : AutoCloseable {
    val preview = TextureView(context)
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("qr-camera").apply { start() }
    private val worker = Handler(thread.looper)
    private val decoder = QrDecoder()
    @Volatile private var closed = false
    @Volatile private var active = false
    private var texture: SurfaceTexture? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var request: CaptureRequest.Builder? = null
    private var opening = false
    private var pendingOpens = 0
    private var generation = 0
    private var delivered = false
    private var flash = false
    private var lastDecode = 0L
    private var luminance = ByteArray(0)
    @Volatile private var previewSize = Size(640, 480)
    @Volatile private var sensorOrientation = 90
    @Volatile private var frontFacing = false

    init {
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                worker.post { texture = surface; openIfReady() }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateTransform()
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                worker.post { texture = null; stopCapture() }
                return true
            }
        }
    }

    fun resume() { if (!closed) worker.post { active = true; openIfReady() } }
    fun pause() { if (!closed) worker.post { active = false; stopCapture() } }
    fun setFlash(enabled: Boolean) { if (!closed) worker.post {
        flash = enabled
        repeatRequest()
    } }

    @Suppress("MissingPermission")
    private fun openIfReady() {
        val surfaceTexture = texture ?: return
        if (closed || !active || opening || camera != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail(); return
        }
        try {
            val ids = manager.cameraIdList
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: ids.firstOrNull() ?: error("No camera")
            val characteristics = manager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No camera streams")
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val analysisSize = sizes.filter { it.width <= 1280 && it.height <= 960 }
                .minByOrNull { abs(it.width * it.height - 640 * 480) } ?: sizes.minByOrNull { it.width * it.height }
                ?: error("No analysis stream")
            previewSize = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                .minByOrNull { abs(it.width * it.height - analysisSize.width * analysisSize.height) } ?: analysisSize
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            frontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            main.post { if (!closed) { onFlashAvailable(hasFlash); updateTransform() } }
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(surfaceTexture)
            reader = ImageReader.newInstance(analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ source -> readFrame(source) }, worker)
            }
            val owner = generation
            opening = true
            pendingOpens++
            try { manager.openCamera(id, object : CameraDevice.StateCallback() {
                private var pending = true
                private fun openedOrFailed() {
                    if (pending) { pending = false; pendingOpens-- }
                    finishWorkerIfClosed()
                }
                override fun onOpened(device: CameraDevice) {
                    if (closed || !active || owner != generation) { device.close(); openedOrFailed(); return }
                    openedOrFailed()
                    opening = false
                    camera = device
                    createSession(device, owner)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); openedOrFailed(); if (owner == generation) fail() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); openedOrFailed(); if (owner == generation) fail() }
            }, worker) } catch (error: Exception) { pendingOpens--; throw error }
        } catch (_: Exception) { fail() }
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice, owner: Int) {
        try {
            val targets = listOfNotNull(previewSurface, reader?.surface)
            request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach(::addTarget)
                val modes = manager.getCameraCharacteristics(device.id).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    if (closed || !active || owner != generation) { configured.close(); return }
                    session = configured
                    repeatRequest()
                }
                override fun onConfigureFailed(configured: CameraCaptureSession) { configured.close(); if (owner == generation) fail() }
            }, worker)
        } catch (_: Exception) { fail() }
    }

    private fun repeatRequest() {
        val builder = request ?: return
        val capture = session ?: return
        try {
            builder.set(CaptureRequest.FLASH_MODE, if (flash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            capture.setRepeatingRequest(builder.build(), null, worker)
        } catch (_: Exception) { fail() }
    }

    private fun readFrame(source: ImageReader) {
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed || !active || delivered || now - lastDecode < 180) return
            lastDecode = now
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            if (luminance.size != width * height) luminance = ByteArray(width * height)
            val buffer = plane.buffer
            for (y in 0 until height) {
                if (plane.pixelStride == 1) {
                    buffer.position(y * plane.rowStride)
                    buffer.get(luminance, y * width, width)
                } else for (x in 0 until width) luminance[y * width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
            }
            decoder.luminance(luminance, width, height)?.let { text ->
                delivered = true
                main.post { if (!closed && active) onResult(text) }
            }
        } catch (_: Exception) { /* Bad/closing frames are discarded; the next frame can retry. */ }
        finally { image.close() }
    }

    private fun updateTransform() {
        val width = preview.width.toFloat()
        val height = preview.height.toFloat()
        if (width <= 0 || height <= 0) return
        val displayDegrees = when (preview.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val rotation = (sensorOrientation + (if (frontFacing) displayDegrees else -displayDegrees) + 360) % 360
        val bufferWidth = previewSize.width.toFloat()
        val bufferHeight = previewSize.height.toFloat()
        val rotated = rotation % 180 != 0
        val scale = maxOf(width / if (rotated) bufferHeight else bufferWidth, height / if (rotated) bufferWidth else bufferHeight)
        preview.setTransform(Matrix().apply {
            setScale(bufferWidth / width, bufferHeight / height)
            postTranslate(-bufferWidth / 2, -bufferHeight / 2)
            postRotate(rotation.toFloat())
            postScale(scale * if (frontFacing) -1 else 1, scale)
            postTranslate(width / 2, height / 2)
        })
    }

    private fun stopCapture() {
        generation++
        opening = false
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        request = null
        delivered = false
    }

    private fun fail() {
        stopCapture()
        main.post { if (!closed && active) onError() }
    }

    private fun finishWorkerIfClosed() {
        // An asynchronous open must deliver its terminal callback so its device can be
        // closed even when the user leaves the scanner before onOpened arrives.
        if (closed && pendingOpens == 0) thread.quitSafely()
    }

    override fun close() {
        if (closed) return
        closed = true
        preview.surfaceTextureListener = null
        worker.post { active = false; stopCapture(); texture = null; luminance = ByteArray(0); finishWorkerIfClosed() }
    }
}
