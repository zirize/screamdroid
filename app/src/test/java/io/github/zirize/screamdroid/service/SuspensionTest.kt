package io.github.zirize.screamdroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SuspensionTest {

    private val poll = 100L

    /** 🔑 A loop that is merely late is not news - see [Suspension.TOLERANCE_MS]. */
    @Test
    fun beingLateIsNotBeingStopped() {
        assertEquals(0L, Suspension.gapMs(poll, poll))
        assertEquals(0L, Suspension.gapMs(poll + 900, poll))
        assertEquals(0L, Suspension.gapMs(poll + Suspension.TOLERANCE_MS - 1, poll))
    }

    @Test
    fun wholeSecondsAwayAreReportedInFull() {
        assertEquals(poll + Suspension.TOLERANCE_MS, Suspension.gapMs(poll + Suspension.TOLERANCE_MS, poll))
        assertEquals(600_000L, Suspension.gapMs(600_000L, poll))
    }

    /** 🔑 The threshold moves with the loop it measures, so a slower poll is not read as a freeze. */
    @Test
    fun theThresholdFollowsThePollInterval() {
        assertEquals(0L, Suspension.gapMs(5_500, 1_000))
        assertEquals(6_000L, Suspension.gapMs(6_000, 1_000))
    }
}
