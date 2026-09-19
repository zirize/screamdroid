package io.github.zirize.screamdroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RampTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(buf: ByteArray): IntArray =
        IntArray(buf.size / 2) { (((buf[it * 2 + 1].toInt() and 0xFF) shl 8) or (buf[it * 2].toInt() and 0xFF)).toShort().toInt() }

    @Test
    fun fadeInRisesFromNearSilenceToFullScale() {
        // 4 stereo frames, every sample at 1000
        val buf = pcm(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)
        Ramp.fadeIn(buf, 0, buf.size, channels = 2, rampFrames = 4)
        val s = samples(buf)
        assertEquals("both channels ramp together", s[0], s[1])
        assertTrue("starts quiet", s[0] < 300)
        assertTrue("rises", s[0] < s[2] && s[2] < s[4] && s[4] < s[6])
        assertTrue("ends near full scale", s[6] > 700)
    }

    @Test
    fun fadeInLeavesWhatIsPastTheRampAlone() {
        val buf = pcm(1000, 1000, 1000, 1000, 1000, 1000)
        Ramp.fadeIn(buf, 0, buf.size, channels = 2, rampFrames = 1)
        val s = samples(buf)
        assertEquals("frame 1 is untouched", 1000, s[2])
        assertEquals(1000, s[4])
    }

    /** A short read gets a short fade - still better than a step. */
    @Test
    fun fadeInOverFewerFramesThanAsked() {
        val buf = pcm(1000, 1000)
        Ramp.fadeIn(buf, 0, buf.size, channels = 2, rampFrames = 100)
        assertTrue(samples(buf)[0] < 1000)
    }

    /**
     * 🔑 The point of the whole file: a pad starts from the sample playback stopped on and slides
     *    to zero, instead of jumping there.
     */
    @Test
    fun decayStartsNearTheLastSampleAndReachesSilence() {
        val dst = ByteArray(8 * 2 * 2) // 8 stereo frames
        Ramp.fillDecay(dst, dst.size, channels = 2, last = shortArrayOf(-8000, 8000), rampFrames = 4)
        val s = samples(dst)
        assertTrue("left starts near where it stopped", s[0] < -5000)
        assertTrue("right too, with its own sign", s[1] > 5000)
        assertTrue("and it falls", s[0] < s[2] && s[2] < s[4])
        assertEquals("silent once the ramp is over", 0, s[8])
        assertEquals(0, s[15])
    }

    @Test
    fun decayOfSilenceIsSilence() {
        val dst = ByteArray(4 * 2 * 2)
        Ramp.fillDecay(dst, dst.size, channels = 2, last = shortArrayOf(0, 0), rampFrames = 2)
        assertTrue(samples(dst).all { it == 0 })
    }

    @Test
    fun readsTheLastFrameOfAChunk() {
        val buf = pcm(1, 2, 3, 4, 5, 6)
        val out = ShortArray(2)
        Ramp.readLastFrame(buf, 0, buf.size, channels = 2, out = out)
        assertEquals(5.toShort(), out[0])
        assertEquals(6.toShort(), out[1])
    }

    @Test
    fun readingAnEmptyChunkGivesSilence() {
        val out = shortArrayOf(9, 9)
        Ramp.readLastFrame(ByteArray(0), 0, 0, channels = 2, out = out)
        assertEquals(0.toShort(), out[0])
        assertEquals(0.toShort(), out[1])
    }

    @Test
    fun frameCountFollowsTheSampleRate() {
        assertEquals(240, Ramp.frames(48000))
        assertEquals(220, Ramp.frames(44100))
    }
}
