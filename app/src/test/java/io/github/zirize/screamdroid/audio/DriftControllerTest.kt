package io.github.zirize.screamdroid.audio

import io.github.zirize.screamdroid.audio.DriftController.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftControllerTest {

    private fun controller() = DriftController(targetMs = 60, maxMs = 150, padBudgetMs = 150)
    private val step = 20

    @Test
    fun waitsUntilTheStartThreshold() {
        val d = controller()
        assertEquals(Action.WAIT, d.decide(0, 0, step).action)
        assertEquals(Action.WAIT, d.decide(59, 59, step).action)
        assertFalse(d.isPlaying)
        assertEquals(Action.PLAY, d.decide(60, 60, step).action)
        assertTrue(d.isPlaying)
    }

    /**
     * 🔑 The flapping test. Once playing, a fill that wanders around the target must not send it
     *    back to buffering - that would be a gap of silence for every ordinary bit of jitter.
     */
    @Test
    fun doesNotFlapAroundTheTarget() {
        val d = controller()
        d.decide(60, 60, step)
        for (fill in intArrayOf(59, 40, 61, 12, 80, 1, 70)) {
            assertEquals("fill=$fill", Action.PLAY, d.decide(fill, fill, step).action)
            assertTrue(d.isPlaying)
        }
    }

    /** Trimming to the ceiling would be back at the ceiling immediately; trim to the target. */
    @Test
    fun overTheCeilingDropsBackToTheTarget() {
        val d = controller()
        d.decide(60, 60, step)
        val decision = d.decide(200, 200, step)
        assertEquals(Action.PLAY, decision.action)
        assertEquals(140, decision.dropMs)
        assertEquals("exactly at the ceiling is not over it", 0, d.decide(150, 150, step).dropMs)
    }

    /**
     * 🔴 A dry buffer is padded, not written off. Over Wi-Fi this sender has stalled for minutes
     *    and recovered on its own; rebuffering at the first empty read turns each one into a gap.
     */
    @Test
    fun padsThroughAShortDroughtAndKeepsPlaying() {
        val d = controller()
        d.decide(60, 60, step)
        repeat(7) { assertEquals(Action.PAD, d.decide(0, 0, step).action) } // 140ms of padding
        assertTrue(d.isPlaying)
        assertEquals("one packet and it is playing again", Action.PLAY, d.decide(30, 30, step).action)
        assertEquals("the drought counter resets", 0, d.paddedMs)
    }

    @Test
    fun givesUpAfterTheePadBudgetAndRebuffers() {
        val d = controller()
        d.decide(60, 60, step)
        repeat(7) { d.decide(0, 0, step) } // 140ms, still within 150
        assertEquals(Action.WAIT, d.decide(0, 0, step).action) // 160ms - past the budget
        assertFalse(d.isPlaying)
        assertEquals("and it refills from the threshold, not from the first byte",
            Action.WAIT, d.decide(59, 59, step).action)
        assertEquals(Action.PLAY, d.decide(60, 60, step).action)
    }

    @Test
    fun resetGoesBackToBuffering() {
        val d = controller()
        d.decide(60, 60, step)
        assertTrue(d.isPlaying)
        d.reset()
        assertFalse(d.isPlaying)
        assertEquals(Action.WAIT, d.decide(59, 59, step).action)
    }

    /**
     * 🔑 The device's buffer is trimmed while the stream plays, which moves the floor the band
     *    sits on, which means a fresh controller several times a minute. Each of those must carry
     *    on rather than rebuffer - nothing was lost, so there is nothing to refill.
     */
    @Test
    fun resumesInsteadOfRebufferingWhenTheBandMoves() {
        val d = controller()
        d.decide(60, 60, step)
        assertTrue(d.isPlaying)

        val moved = DriftController(targetMs = 40, maxMs = 130)
        assertFalse(moved.isPlaying)
        assertEquals(Action.WAIT, moved.decide(20, 20, step).action)

        moved.resume()
        assertTrue(moved.isPlaying)
        assertEquals("must not wait to refill", Action.PLAY, moved.decide(20, 20, step).action)
    }

    /**
     * 🔴 **Padding must not prove to itself that there is still audio to play.** During a drought
     *    the device queue holds nothing but the silence this controller just asked for, so a
     *    drought measured on the total never ends: the budget resets on every pass and the
     *    receiver pads through the whole silence. Measured on the phone 2026-09-18.
     */
    @Test
    fun theDroughtBudgetRunsOutEvenWhileTheDeviceStillHoldsPadding() {
        val d = controller()
        d.decide(60, 60, step)
        assertTrue(d.isPlaying)

        // The ring is dry; the device queue cycles as each pad is written and played out.
        var pads = 0
        var action = Action.PAD
        while (action == Action.PAD && pads < 100) {
            val queued = if (pads % 2 == 0) 10 else 0    // our own padding, nothing else
            action = d.decide(queued, 0, step).action
            if (action == Action.PAD) pads++
        }
        assertEquals("must give up and wait", Action.WAIT, action)
        assertTrue("padded $pads times", pads * step <= 150 + step)
    }

    @Test
    fun rejectsSettingsThatCannotWork() {
        val bad = listOf(
            { DriftController(targetMs = 0, maxMs = 150) },
            { DriftController(targetMs = 60, maxMs = 60) },
            { DriftController(targetMs = 60, maxMs = 30) },
        )
        for (make in bad) {
            try {
                make()
                throw AssertionError("should have been rejected")
            } catch (expected: IllegalArgumentException) {
                // as intended
            }
        }
    }
}
