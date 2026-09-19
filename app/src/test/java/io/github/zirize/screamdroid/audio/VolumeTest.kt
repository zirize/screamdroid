package io.github.zirize.screamdroid.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTest {

    @Test
    fun theEndsOfTheSliderAreExact() {
        assertEquals(0f, Volume.gain(0), 0f)
        assertEquals(1f, Volume.gain(100), 0f)
    }

    /**
     * 🔑 Half travel is half the decibel range, not half the amplitude: with a 40 dB fader that is
     *    -20 dB, or a tenth of full scale. A linear slider would put 0.5 here and spend its whole
     *    top half on a change nobody can hear.
     */
    @Test
    fun theMiddleOfTheSliderIsTheMiddleOfTheRange() {
        assertEquals(0.1f, Volume.gain(50), 0.001f)
        assertEquals(0.316f, Volume.gain(75), 0.002f)
    }

    @Test
    fun itNeverLeavesTheRangeTheDeviceAccepts() {
        for (percent in -50..150) {
            val gain = Volume.gain(percent)
            assertTrue("$percent produced $gain", gain in 0f..1f)
        }
    }

    @Test
    fun itOnlyEverGoesUp() {
        var previous = -1f
        for (percent in 0..100) {
            val gain = Volume.gain(percent)
            assertTrue("$percent went down", gain > previous)
            previous = gain
        }
    }
}
