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

    @Test
    fun quietSamplesGetExactlyTheGainAskedFor() {
        val buf = pcm(1000, -1000, 0)
        Boost.apply(buf, 0, buf.size, 4f)
        val s = samples(buf)
        assertEquals(4000, s[0])
        assertEquals(-4000, s[1])
        assertEquals(0, s[2])
    }

    /** 🔴 The whole point of the limiter: a full-scale input boosted 4x must not wrap or clip flat. */
    @Test
    fun loudSamplesBendBelowFullScaleInsteadOfWrapping() {
        val buf = pcm(32767, -32768, 20000)
        Boost.apply(buf, 0, buf.size, 4f)
        val s = samples(buf)
        assertTrue("positive stays positive: ${s[0]}", s[0] in 16384..32767)
        assertTrue("negative stays negative: ${s[1]}", s[1] in -32768..-16384)
        assertTrue("and the order survives", s[2] < s[0])
    }

    @Test
    fun theCurveIsSmoothAtTheKneeAndRisesAllTheWay() {
        val k = Boost.KNEE
        assertEquals(k, Boost.limit(k), 1e-6f)
        // slope 1 just past the knee: no corner to hear
        assertEquals(0.001f, Boost.limit(k + 0.001f) - k, 0.0001f)
        var previous = 0f
        var x = 0f
        while (x < 10f) {
            val y = Boost.limit(x)
            assertTrue("rises at $x", y >= previous)
            assertTrue("below full scale at $x", y < 1f)
            previous = y
            x += 0.01f
        }
    }

    @Test
    fun unityOrLessLeavesTheBufferAlone() {
        val buf = pcm(32767, -32768, 123)
        Boost.apply(buf, 0, buf.size, 1f)
        assertEquals(listOf(32767, -32768, 123), samples(buf).toList())
    }
}
