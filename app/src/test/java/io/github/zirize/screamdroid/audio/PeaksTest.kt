package io.github.zirize.screamdroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeaksTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun eachSideIsMeasuredOnItsOwn() {
        val out = IntArray(2)
        // L R L R - the right side is the loud one.
        Peaks.scanStereo(pcm(100, -9000, -300, 400), 0, 8, channels = 2, out = out)
        assertEquals(300, out[0])
        assertEquals(9000, out[1])
    }

    /** 🔑 A mono stream reports the same channel twice, so the meter is symmetric, not half-empty. */
    @Test
    fun monoFillsBothSides() {
        val out = IntArray(2)
        Peaks.scanStereo(pcm(500, -1200, 30), 0, 6, channels = 1, out = out)
        assertEquals(1200, out[0])
        assertEquals(1200, out[1])
    }

    @Test
    fun onlyTheFirstTwoChannelsOfASurroundStreamAreLookedAt() {
        val out = IntArray(2)
        // One 4-channel frame: the loudest sample is in channel 3 and must not reach the meter.
        Peaks.scanStereo(pcm(100, 200, 30000, 31000), 0, 8, channels = 4, out = out)
        assertEquals(100, out[0])
        assertEquals(200, out[1])
    }

    @Test
    fun anEmptyBlockIsSilence() {
        val out = intArrayOf(999, 999)
        Peaks.scanStereo(pcm(), 0, 0, channels = 2, out = out)
        assertEquals(0, out[0])
        assertEquals(0, out[1])
    }

    /** 🔑 -32768 has no positive twin in 16 bits, and negating it overflows back to itself. */
    @Test
    fun theMostNegativeSampleReadsAsFullScale() {
        val out = IntArray(2)
        Peaks.scanStereo(pcm(-32768, 0), 0, 4, channels = 2, out = out)
        assertEquals(Peaks.FULL_SCALE, out[0])
    }

    @Test
    fun theScaleIsDecibelsSoQuietAudioIsStillVisible() {
        assertEquals(0f, Peaks.fraction(0), 0f)
        assertEquals(1f, Peaks.fraction(Peaks.FULL_SCALE), 0.001f)
        // -20 dBFS is ordinary listening level; on a linear meter it would be a tenth of the bar.
        val quarterScale = Peaks.fraction(Peaks.FULL_SCALE / 10)
        assertTrue("-20 dBFS should fill about half the meter, was $quarterScale",
            quarterScale in 0.5f..0.62f)
        // Below the floor nothing is drawn at all, rather than a permanent sliver.
        assertEquals(0f, Peaks.fraction(1), 0f)
    }

    @Test
    fun louderAlwaysMeansTaller() {
        var previous = -1f
        for (amplitude in listOf(200, 500, 1_000, 4_000, 16_000, 32_767)) {
            val value = Peaks.fraction(amplitude)
            assertTrue("$amplitude did not raise the meter", value > previous)
            previous = value
        }
    }
}
