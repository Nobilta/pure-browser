package com.mybrowser.qr

import android.graphics.Matrix
import android.util.Size
import android.view.Surface

/** Center-crop a Camera2 TextureView without stretching or applying sensor rotation twice. */
internal fun qrPreviewTransform(viewWidth: Int, viewHeight: Int, bufferSize: Size,
    sensorOrientation: Int, displayRotation: Int): Matrix {
    val matrix = Matrix()
    if (viewWidth <= 0 || viewHeight <= 0) return matrix

    // Camera2's SurfaceTexture transform already rotates the sensor buffer and mirrors
    // front cameras. TextureView stretches that oriented image to its bounds. Undo only
    // the stretch here; applying sensor rotation or mirroring again reverses the preview.
    // https://developer.android.com/media/camera/camera2/camera-preview#textureview
    val sensorSwapsSides = sensorOrientation % 180 != 0
    val width = (if (sensorSwapsSides) bufferSize.height else bufferSize.width).toFloat()
    val height = (if (sensorSwapsSides) bufferSize.width else bufferSize.height).toFloat()
    val displayDegrees = when (displayRotation) {
        Surface.ROTATION_90 -> 90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> 270f
        else -> 0f
    }
    val displaySwapsSides = displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
    val scale = maxOf(viewWidth / if (displaySwapsSides) height else width,
        viewHeight / if (displaySwapsSides) width else height)
    matrix.setScale(width / viewWidth, height / viewHeight)
    matrix.postTranslate(-width / 2, -height / 2)
    matrix.postRotate(-displayDegrees)
    matrix.postScale(scale, scale)
    matrix.postTranslate(viewWidth / 2f, viewHeight / 2f)
    return matrix
}
