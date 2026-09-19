package io.github.zirize.screamdroid.audio

import io.github.zirize.screamdroid.audio.IdlePolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

class IdlePolicyTest {

    @Test
    fun aPacketJustNowMeansActive() {
        assertEquals(Tier.ACTIVE, IdlePolicy.tierFor(0))
        assertEquals(Tier.ACTIVE, IdlePolicy.tierFor(1_499))
    }

    /** 🔑 The boundaries are the whole content of this class, so each one is pinned. */
    @Test
    fun eachStepBeginsExactlyWhenItSays() {
        assertEquals(Tier.STALE, IdlePolicy.tierFor(1_500))
        assertEquals(Tier.STALE, IdlePolicy.tierFor(59_999))
        assertEquals(Tier.IDLE, IdlePolicy.tierFor(60_000))
        assertEquals(Tier.IDLE, IdlePolicy.tierFor(599_999))
        assertEquals(Tier.DEEP_IDLE, IdlePolicy.tierFor(600_000))
        assertEquals(Tier.DEEP_IDLE, IdlePolicy.tierFor(Long.MAX_VALUE))
    }

    /**
     * 🔴 One packet must undo every step at once. A receiver that unwound a tier at a time would
     *    take three gaps to come back from a long quiet spell, and the sender gives no warning
     *    that audio is about to resume.
     */
    @Test
    fun oneArrivingPacketReturnsStraightToActive() {
        assertEquals(Tier.DEEP_IDLE, IdlePolicy.tierFor(3_600_000))
        assertEquals(Tier.ACTIVE, IdlePolicy.tierFor(0))
    }

    /**
     * 🔴 **Awake mode must skip STALE entirely, not merely delay it.** STALE is the step that
     *    pauses the audio device, and a paused device is what a short sound arrives too late to
     *    wake. Anything short of "never, until IDLE takes the device back anyway" would leave a
     *    window where clicks are still lost.
     */
    @Test
    fun awakeModeNeverReachesStale() {
        val awake = IdlePolicy.AWAKE_STALE_AFTER_MS
        assertEquals(Tier.ACTIVE, IdlePolicy.tierFor(1_500, awake))
        assertEquals(Tier.ACTIVE, IdlePolicy.tierFor(59_999, awake))
        // 🔑 And it gives up nothing further out: the device is handed back on time.
        assertEquals(Tier.IDLE, IdlePolicy.tierFor(60_000, awake))
        assertEquals(Tier.DEEP_IDLE, IdlePolicy.tierFor(600_000, awake))
    }

    @Test
    fun tiersOnlyEverGoOneWayAsTimePasses() {
        var previous = Tier.ACTIVE
        for (ms in longArrayOf(0, 1_000, 1_500, 30_000, 60_000, 300_000, 600_000, 900_000)) {
            val tier = IdlePolicy.tierFor(ms)
            assert(tier.ordinal >= previous.ordinal) { "went backwards at ${ms}ms" }
            previous = tier
        }
        assertEquals(Tier.DEEP_IDLE, previous)
    }
}
