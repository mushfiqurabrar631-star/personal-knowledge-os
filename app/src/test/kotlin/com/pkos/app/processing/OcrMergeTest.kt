package com.pkos.app.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the on-device OCR combination (the native Tesseract engine itself needs a phone). */
class OcrMergeTest {
    @Test fun mergesLatinAndBengaliWithoutDuplicates() {
        assertEquals("Invoice 42\nমাসিক বাজেট", CombinedOcrEngine.merge("Invoice 42", "মাসিক বাজেট"))
        assertEquals("Hello", CombinedOcrEngine.merge("Hello", "Hello"))
        assertEquals("মাসিক বাজেট", CombinedOcrEngine.merge("  ", "মাসিক বাজেট"))
        assertEquals("", CombinedOcrEngine.merge(null, null))
    }

    @Test fun bengaliShareFiltersNoise() {
        assertTrue(TesseractBengaliOcr.bengaliShare("মাসিক বাজেট পরিকল্পনা") > 0.9)
        assertTrue(TesseractBengaliOcr.bengaliShare("Invoice total 42") == 0.0)
        assertEquals(15_552_533L, java.io.File("src/main/assets/tessdata/ben.traineddata").takeIf { it.exists() }?.length() ?: java.io.File("app/src/main/assets/tessdata/ben.traineddata").length())
    }
}
