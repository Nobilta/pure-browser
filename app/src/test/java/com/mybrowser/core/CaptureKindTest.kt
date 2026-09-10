package com.mybrowser.core

import org.junit.Assert.*
import org.junit.Test

class CaptureKindTest {
    @Test fun captureNeedsAnExplicitSingleMediaTypeAndSingleSelection() {
        assertEquals(CaptureKind.PHOTO, captureKind(true, false, listOf("image/jpeg", "image/png")))
        assertEquals(CaptureKind.VIDEO, captureKind(true, false, listOf("video/*")))
        assertNull(captureKind(false, false, listOf("image/*")))
        assertNull(captureKind(true, true, listOf("image/*")))
        assertNull(captureKind(true, false, listOf("image/*", "video/*")))
        assertNull(captureKind(true, false, listOf("*/*")))
    }
}
