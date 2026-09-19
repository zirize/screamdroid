package io.github.zirize.screamdroid.audio

import io.github.zirize.screamdroid.audio.InterruptionPolicy.Action
import io.github.zirize.screamdroid.audio.InterruptionPolicy.Focus
import io.github.zirize.screamdroid.settings.CallBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 🔑 This is the whole of M5's decision-making, and it is here rather than on the phone because
 *    the alternative is ringing the phone once per combination. What the device still has to
 *    prove is only that the focus callback arrives at all.
 */
class InterruptionPolicyTest {

    private fun decide(
        behavior: CallBehavior = CallBehavior.MUTE,
        focus: Focus = Focus.HELD,
        inCall: Boolean = false,
        duck: Boolean = true,
    ) = InterruptionPolicy.decide(behavior, focus, inCall, duck)

    private fun resolve(callRecently: Boolean, settled: Boolean) =
        InterruptionPolicy.resolveLoss(callRecently, settled)

    @Test
    fun nothingHappeningMeansCarryOn() {
        assertEquals(Action.PLAY, decide())
        assertEquals(Action.PLAY, decide(behavior = CallBehavior.DUCK))
        assertEquals(Action.PLAY, decide(behavior = CallBehavior.IGNORE))
    }

    @Test
    fun aTransientLossMutesOrQuietensAsChosen() {
        assertEquals(Action.MUTE, decide(CallBehavior.MUTE, Focus.LOST_TRANSIENT))
        assertEquals(Action.DUCK, decide(CallBehavior.DUCK, Focus.LOST_TRANSIENT))
    }

    /** 🔑 The second opinion on its own is enough: some dialers never take focus at all. */
    @Test
    fun theCallModeAloneIsEnoughToYield() {
        assertEquals(Action.MUTE, decide(CallBehavior.MUTE, Focus.HELD, inCall = true))
        assertEquals(Action.DUCK, decide(CallBehavior.DUCK, Focus.HELD, inCall = true))
    }

    /**
     * 🔴 "Ignore" wins over everything, the call check included. Somebody who chose it is using
     *    the phone as a speaker and means it.
     */
    @Test
    fun ignoreIsNotOverruledByAnything() {
        assertEquals(Action.PLAY, decide(CallBehavior.IGNORE, Focus.LOST_TRANSIENT))
        assertEquals(Action.PLAY, decide(CallBehavior.IGNORE, Focus.LOST_TRANSIENT_DUCK))
        assertEquals(Action.PLAY, decide(CallBehavior.IGNORE, Focus.HELD, inCall = true))
        // Even a permanent loss: with "ignore" the app never asked for focus, so there is none
        // to lose, and a stray event must not switch the session off.
        assertEquals(Action.PLAY, decide(CallBehavior.IGNORE, Focus.LOST))
    }

    @Test
    fun aPermanentLossEndsTheSession() {
        assertEquals(Action.STOP, decide(CallBehavior.MUTE, Focus.LOST))
        assertEquals(Action.STOP, decide(CallBehavior.DUCK, Focus.LOST))
        // A permanent loss is permanent whatever telephony is doing.
        assertEquals(Action.STOP, decide(CallBehavior.MUTE, Focus.LOST, inCall = true))
    }

    /**
     * ℹ️ This event only arrives when the request said it would rather pause than be ducked -
     *    which is exactly how "quieten for notification sounds" is turned off. So the switch being
     *    off has to mean "carry on", not "pause".
     */
    @Test
    fun aNotificationOnlyQuietensWhenThatIsWanted() {
        assertEquals(Action.DUCK, decide(focus = Focus.LOST_TRANSIENT_DUCK, duck = true))
        assertEquals(Action.PLAY, decide(focus = Focus.LOST_TRANSIENT_DUCK, duck = false))
    }

    /** A call outranks a notification: if both are true, the stream goes quiet, not soft. */
    @Test
    fun aCallOutranksANotification() {
        assertEquals(
            Action.MUTE,
            decide(CallBehavior.MUTE, Focus.LOST_TRANSIENT_DUCK, inCall = true, duck = true),
        )
    }

    /**
     * 🔴 **Measured on the test phone, 2026-09-17.** An incoming call's ringtone asks for
     *    permanent `AUDIOFOCUS_GAIN` - the same thing a music player asks for - so a receiver
     *    that stops on a permanent loss switches itself off every time the phone rings. These
     *    four cases are the fix, and they are the reason the audio mode is not optional.
     */
    @Test
    fun aPermanentLossIsNotBelievedUntilTheEvidenceIsIn() {
        // The instant it arrives, nothing is known yet: go quiet, decide later.
        assertEquals(Focus.LOST_TRANSIENT, resolve(callRecently = false, settled = false))
        // A call was ringing just before or is ringing now: it will end, and we will want back in.
        assertEquals(Focus.LOST_TRANSIENT, resolve(callRecently = true, settled = true))
        // Long enough with no call anywhere near it: it really was another app.
        assertEquals(Focus.LOST, resolve(callRecently = false, settled = true))
    }

    /**
     * 🔴 **Both orderings were measured on the same phone, 2026-09-17.** Once the focus loss came
     *    two seconds *after* the ring started; once it came one second *after the ring had already
     *    stopped*. A rule that only looked forward from the loss missed the second one and shut
     *    the session down - which is why the window looks both ways.
     */
    @Test
    fun aRingThatHasAlreadyStoppedStillCounts() {
        assertEquals(Focus.LOST_TRANSIENT, resolve(callRecently = true, settled = false))
        assertEquals(Focus.LOST_TRANSIENT, resolve(callRecently = true, settled = true))
    }

    @Test
    fun theWindowsAreShortEnoughToBeInvisibleAndLongEnoughToCatchTheGap() {
        // Longer than the two-second delivery gap that was measured, and not so long that a
        // session really taken over by another app sits muted holding locks.
        assert(InterruptionPolicy.LOSS_GRACE_MS in 3_000L..8_000L)
        // Long enough to bridge a ring and the call after it - this one is not what governs how
        // promptly sound comes back, and shortening it to that end would flap on at the moment
        // somebody answers.
        assert(InterruptionPolicy.CALL_MEMORY_MS in 1_500L..5_000L)
        // 🔑 What a person actually notices: the pause after hanging up. Short, because nothing
        // else is coming once a conversation has ended - but not so short that one polled reading
        // landing in a gap mid-call puts the stream back on during the call.
        assert(InterruptionPolicy.CALL_TAIL_MS in 800L..2_000L)
        assert(InterruptionPolicy.CALL_TAIL_MS < InterruptionPolicy.CALL_MEMORY_MS)
    }

    /**
     * 🔑 Sharing the speaker is the absence of a focus request, so [Focus] stays `HELD` and none
     *    of the focus branches can fire. What is left is the playback list, and it must move the
     *    stream for an alert without moving it for the music the mode exists to play alongside.
     */
    @Test
    fun whileSharingTheSpeakerAnAlertStillQuietensTheStream() {
        assertEquals(
            Action.DUCK,
            InterruptionPolicy.decide(
                CallBehavior.MUTE, Focus.HELD,
                callRecently = false, duckOnNotification = true, alertSounding = true,
            ),
        )
        // Turned off, an alert changes nothing - the switch means what it says.
        assertEquals(
            Action.PLAY,
            InterruptionPolicy.decide(
                CallBehavior.MUTE, Focus.HELD,
                callRecently = false, duckOnNotification = false, alertSounding = true,
            ),
        )
        // 🔴 A call still silences it, even though no focus was ever asked for: the signal that
        //    found the call does not depend on focus, which is what makes this mode possible.
        assertEquals(
            Action.MUTE,
            InterruptionPolicy.decide(
                CallBehavior.MUTE, Focus.HELD,
                callRecently = true, duckOnNotification = true, alertSounding = true,
            ),
        )
    }

    /** 🚫 Never passed while focus is held: the system is already ducking, and twice is wrong. */
    @Test
    fun anAlertIsIgnoredByDefaultSoTheOrdinaryModeIsUnchanged() {
        assertEquals(Action.PLAY, decide(focus = Focus.HELD))
    }

    @Test
    fun duckingIsAudibleButOutOfTheWay() {
        // A fifth of the amplitude, about -14 dB: still there, no longer competing.
        assert(InterruptionPolicy.DUCK_GAIN > 0f) { "ducking to silence is muting" }
        assert(InterruptionPolicy.DUCK_GAIN < 0.5f) { "ducking has to be audible as a change" }
    }
}
