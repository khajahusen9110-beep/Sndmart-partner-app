package com.example.util

import org.junit.Assert.*
import org.junit.Test

/** The size search of ImageCompressor, with a pretend encoder (size grows with the picture size and the quality). */
class ImageCompressorTest {
    // bytes ~ pixels * quality / 40: a 1024 px picture at quality 60 is about 1.5 MB, so a photo needs to shrink a lot to reach 20 KB
    private fun fake(side: Int, q: Int): Int = (side.toLong() * side * q / 40).toInt()

    @Test fun smallPicture_keepsItsSizeAndTopQuality() {
        // a 200 px icon is already tiny: the biggest allowed side (200) at the best quality
        val (side, q) = ImageCompressor.choose(200, ImageCompressor.TARGET_BYTES) { s, qq -> fake(s, qq) / 20 }
        assertEquals(200, side); assertEquals(90, q)
    }

    @Test fun bigPhoto_endsUnderTheTarget_atAGoodQuality() {
        val (side, q) = ImageCompressor.choose(4000, ImageCompressor.TARGET_BYTES) { s, qq -> fake(s, qq) / 30 }
        assertTrue("size ${fake(side, q) / 30}", fake(side, q) / 30 <= ImageCompressor.TARGET_BYTES)
        assertTrue("quality $q", q >= 60) // the first floor works for this encoder, so no ugly quality
        // and it is the best picture that fits: the next bigger side would not fit even at the lowest quality of its floor
        val bigger = listOf(1024, 896, 768, 640, 560, 480, 420, 360, 320, 280, 240, 200).filter { it > side }.lastOrNull()
        if (bigger != null) assertTrue(fake(bigger, 60) / 30 > ImageCompressor.TARGET_BYTES)
    }

    @Test fun stubbornPhoto_lowersQualityBeforeGivingUp_andStillFits() {
        // very detailed: only a low quality fits
        val enc = { s: Int, qq: Int -> (s.toLong() * s * qq / 6).toInt() / 20 }
        val (side, q) = ImageCompressor.choose(2000, ImageCompressor.TARGET_BYTES, enc)
        assertTrue(enc(side, q) <= ImageCompressor.TARGET_BYTES)
    }

    @Test fun impossibleEncoder_returnsTheSmallestSettings_insteadOfLooping() {
        val (side, q) = ImageCompressor.choose(3000, ImageCompressor.TARGET_BYTES) { _, _ -> 10_000_000 }
        assertEquals(200, side); assertEquals(18, q)
    }

    @Test fun smallerMaxSide_isRespected() {
        val (side, _) = ImageCompressor.choose(500, ImageCompressor.TARGET_BYTES) { s, qq -> fake(s, qq) / 500 }
        assertTrue(side <= 500)
    }

    @Test fun targetIsTwentyKilobytes() = assertEquals(20480, ImageCompressor.TARGET_BYTES)
}
