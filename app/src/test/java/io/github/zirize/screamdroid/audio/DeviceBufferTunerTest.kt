package io.github.zirize.screamdroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBufferTunerTest {

    /**
     * The figures measured on the test phone's speaker: 7696 frames offered (160 ms), opened at
     * 1392 (29 ms), 5 ms of granularity, half a second of warm-up, a second and a half of patience.
     */
    private fun tuner(start: Int = 7696, opened: Int = 1392) =
        DeviceBufferTuner(
            startFrames = start,
            settleFrames = 24_000L,
            patienceFrames = 72_000L,
            granularityFrames = 240,
        ).also { it.applied(opened) }

    /** Play [frames] of clean audio, applying whatever the tuner asks for. */
    private fun play(t: DeviceBufferTuner, frames: Long, underruns: Int = 0) {
        var left = frames
        while (left > 0) {
            val chunk = minOf(left, 1152L).toInt()
            t.played(chunk)
            left -= chunk
            val want = t.next(underruns)
            if (want > 0) t.applied(want)
        }
    }

    /** Play past the warm-up window, so what the device reports counts again. */
    private fun warmedUp(t: DeviceBufferTuner) = repeat(30) { t.played(1152) }

    @Test
    fun leavesACleanStreamCompletelyAlone() {
        val t = tuner()
        play(t, 5_000_000L)
        assertEquals(1392, t.frames)
        assertEquals(0, t.growths)
        assertTrue(!t.surrendered)
    }

    /**
     * 🔴 The audio path reports an underrun every time it wakes up, and this sender goes quiet
     *    after every alert sound, so a tuner that believed those reports would grow on every
     *    burst until nothing was left of the trimming.
     */
    @Test
    fun ignoresTheUnderrunThatEveryStartReports() {
        val t = tuner()
        var underruns = 0
        repeat(30) {
            t.applied(t.frames)                 // a drought, then the device starts again
            underruns++                         // ... which always reports one
            repeat(20) { t.played(1152); assertEquals(0, t.next(underruns)) }
        }
        assertEquals(1392, t.frames)
        assertEquals(0, t.growths)
    }

    /**
     * 🔴 **Growing is not free and shrinking was.** A break-up on a stream that falls quiet every
     *    second or two must not be paid for with a gap of its own - the next silence is along in a
     *    moment, and growing there costs nothing.
     */
    @Test
    fun waitsForASilenceBeforeGrowing() {
        val t = tuner()
        warmedUp(t)
        assertEquals("must not grow mid-burst", 0, t.next(1))
        assertEquals(1392, t.frames)
        assertTrue(t.owesGrowth)

        val grown = t.atRest()
        assertTrue("$grown must be above 1392", grown > 1392)
        assertTrue("$grown is not a small step", grown - 1392 <= 1392 / 4 + 240)
    }

    /** A stream that never falls quiet still gets help - it just has to pay for it. */
    @Test
    fun growsWithoutASilenceWhenTheBreakUpsNeverStop() {
        val t = tuner()
        repeat(70) { t.played(1152) }           // past patienceFrames of unbroken audio
        val grown = t.next(1)
        assertTrue("$grown must be above 1392", grown > 1392)
    }

    /** Nothing is owed after a growth, so the next quiet moment leaves the device alone. */
    @Test
    fun growsOncePerBreakUpAndNoMore() {
        val t = tuner()
        warmedUp(t)
        t.next(1)
        val grown = t.atRest()
        t.applied(grown)
        assertEquals(grown, t.atRest())
        assertEquals(grown, t.atRest())
        assertEquals(1, t.growths)
    }

    /**
     * 🔴 The limit. Past it the trimming has bought nothing worth the interruptions it took to
     *    keep, so the device's own size goes back once and nothing is touched again.
     */
    @Test
    fun handsTheDeviceItsOwnSizeBackRatherThanCreepingUpForever() {
        val t = tuner()
        var underruns = 0
        var guard = 0
        while (!t.surrendered && guard++ < 100) {
            warmedUp(t)
            t.next(++underruns)                 // a break-up at every size, every time
            val want = t.atRest()
            if (want != t.frames) t.applied(want)
        }
        assertTrue("never gave up", t.surrendered)
        assertEquals(7696, t.frames)

        // And from here it is inert: nothing more is asked of the device, ever.
        repeat(200) { t.played(1152); assertEquals(0, t.next(++underruns)) }
        assertEquals(7696, t.atRest())
    }

    /** Never past what the device offered, even if the ceiling would allow it. */
    @Test
    fun neverAsksForMoreThanTheDeviceOffered() {
        val t = tuner(start = 2000, opened = 1900)
        var guard = 0
        while (!t.surrendered && guard++ < 50) {
            warmedUp(t)
            t.next(guard)
            val want = t.atRest()
            assertTrue("asked for $want, over 2000", want <= 2000)
            if (want != t.frames) t.applied(want)
        }
    }

    /** The opening size has to hold one of the player's writes, or every write blocks halfway. */
    @Test
    fun opensAboveOneWriteChunkAndFarUnderWhatTheDeviceOffered() {
        val chunkFrames = 1152 * 4 / 4        // 4 packets of 1152 bytes, 4 bytes a frame
        val opened = DeviceBufferTuner.openSize(chunkFrames, 16, 7696, 240)
        assertTrue("$opened must hold a write of $chunkFrames", opened > chunkFrames)
        assertTrue("$opened must be far under 7696", opened < 7696 / 2)
    }

    /** A device whose own floor is above the rule still gets a size it will take. */
    @Test
    fun respectsADeviceFloorAboveTheRule() {
        assertEquals(2000, DeviceBufferTuner.openSize(1152, 2000, 7696, 240))
        assertEquals(900, DeviceBufferTuner.openSize(1152, 16, 900, 240))
    }
}
