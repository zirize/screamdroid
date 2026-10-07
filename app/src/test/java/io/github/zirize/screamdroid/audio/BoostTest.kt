package io.github.zirize.screamdroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostTest {

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

    private val rate = 48_000

    @Test
    fun quietSamplesGetExactlyTheGainAskedFor() {
        val buf = pcm(1000, -1000, 0, 0)
        Boost().apply(buf, 0, buf.size, 2, rate, 4f)
        assertEquals(listOf(4000, -4000, 0, 0), samples(buf).toList())
    }

    /** 🔴 A full-scale input boosted 4x must not wrap or clip flat. */
    @Test
    fun loudSamplesStopAtTheCeiling() {
        val buf = pcm(32767, -32768, 20000, -20000)
        Boost().apply(buf, 0, buf.size, 2, rate, 4f)
        val ceiling = (Boost.CEILING * 32768).toInt() + 1
        samples(buf).forEach { assertTrue("$it over the ceiling", it in -ceiling..ceiling) }
        val s = samples(buf)
        assertTrue("positive stays positive", s[0] > 0)
        assertTrue("negative stays negative", s[1] < 0)
    }

    /**
     * 🔴 The regression this class exists for: a stateless curve bent everything above half of
     *    full scale, so a loud song was distorted at any boost. A frame that fits must come out
     *    scaled, never reshaped - the ratio between samples survives.
     */
    @Test
    fun aPeakIsTurnedDownNotBent() {
        val buf = pcm(30000, 15000, 7500, -30000)
        Boost().apply(buf, 0, buf.size, 4, rate, 2f)
        val s = samples(buf)
        assertEquals(0.5, s[1].toDouble() / s[0], 0.001)
        assertEquals(0.25, s[2].toDouble() / s[0], 0.001)
        assertEquals(-1.0, s[3].toDouble() / s[0], 0.001)
    }

    /** 🔑 The channels share one gain, so a peak on the left does not shift the image right. */
    @Test
    fun theChannelsAreTurnedDownTogether() {
        val buf = pcm(32000, 1000)
        Boost().apply(buf, 0, buf.size, 2, rate, 4f)
        val s = samples(buf)
        assertEquals(32.0, s[0].toDouble() / s[1], 0.1)
    }

    /** 🔑 After the peak the gain comes back on its own, across chunks, to the gain asked for. */
    @Test
    fun theGainRecoversAfterThePeak() {
        val boost = Boost()
        val peak = pcm(32767, 32767)
        boost.apply(peak, 0, peak.size, 2, rate, 4f)
        assertTrue("backed off: ${boost.current}", boost.current < 1f)
        val quiet = ByteArray(rate / 1000 * Boost.RELEASE_MS.toInt() * 5 * 4)
        boost.apply(quiet, 0, quiet.size, 2, rate, 4f)
        assertEquals(4f, boost.current, 0.05f)
    }

    @Test
    fun unityOrLessLeavesTheBufferAlone() {
        val buf = pcm(32767, -32768, 123, 0)
        Boost().apply(buf, 0, buf.size, 2, rate, 1f)
        assertEquals(listOf(32767, -32768, 123, 0), samples(buf).toList())
    }
}
