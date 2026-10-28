package com.mybrowser.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentGenerationTest {
    @Test fun aReloadOfTheSameUrlInvalidatesAnEarlierCapture() {
        val generation = ReaderDocumentGeneration()
        val captured = generation.capture()
        assertTrue(generation.isCurrent(captured))
        generation.onPageStarted() // same URL reload still starts a new document
        assertFalse(generation.isCurrent(captured))
    }
}
