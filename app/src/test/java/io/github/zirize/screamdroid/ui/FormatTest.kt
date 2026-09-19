package io.github.zirize.screamdroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    /** 🔑 An uptime, not a time of day: it counts past a day rather than wrapping to 00. */
    @Test
    fun durationsAreFixedWidthAndDoNotWrap() {
        assertEquals("00:00:00", Format.duration(0))
        assertEquals("00:00:09", Format.duration(9_400))
        assertEquals("02:41:08", Format.duration((2 * 3600 + 41 * 60 + 8) * 1000L))
        assertEquals("30:00:00", Format.duration(30 * 3600 * 1000L))
    }

    /** A negative reading is a clock that moved, not a negative duration. */
    @Test
    fun aDurationIsNeverNegative() {
        assertEquals("00:00:00", Format.duration(-5_000))
    }

    @Test
    fun theRateIsAlwaysTwoDecimals() {
        assertEquals("1.54", Format.mbps(1_544))
        assertEquals("0.00", Format.mbps(0))
        assertEquals("12.00", Format.mbps(12_000))
    }
}
