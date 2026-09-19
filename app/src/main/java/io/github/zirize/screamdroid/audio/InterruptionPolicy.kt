package io.github.zirize.screamdroid.audio

import io.github.zirize.screamdroid.settings.CallBehavior

/**
 * What to do when something else wants the speaker.
 *
 * 🔑 **A pure function, because the alternative is testing it by ringing the phone.** The inputs
 *    are a setting, an audio-focus state and whether the telephony stack says there is a call;
 *    every combination of those can be checked on the JVM, which leaves exactly one thing that
 *    has to be verified on a real device: that the focus callback arrives at all.
 *
 * 🔑 **Two signals, not one.** Audio focus is the right primary answer (docs/architecture.md):
 *    no permissions, and it covers alarms and navigation as well as calls. But some VoIP and
 *    manufacturer dialers are reported not to take focus, so the audio mode is read as well -
 *    either one is enough to yield.
 */
object InterruptionPolicy {

    /** What the system last told us about our claim on the speaker. */
    enum class Focus {
        /** We have it. */
        HELD,

        /** Something took it for a while - a call, an alarm, another app's playback. */
        LOST_TRANSIENT,

        /**
         * Something wants to be heard over us for a moment - a notification sound.
         *
         * ℹ️ Only ever delivered when the request declared it would rather pause than be ducked;
         *    otherwise the system ducks us itself and says nothing.
         */
        LOST_TRANSIENT_DUCK,

        /**
         * Something took it for good.
         *
         * 🔴 **This does not mean "another media app started", however much it looks like it.**
         *    Measured on the test phone, 2026-09-17: an **incoming call's ringtone** asks for
         *    `AUDIOFOCUS_GAIN`, permanent, not the transient one the plan assumed - so acting on
         *    this event directly switched the session off every time the phone rang, which is the
         *    exact opposite of what the feature is for. See [resolveLoss].
         */
        LOST,
    }

    /**
     * What a permanent focus loss **actually** was.
     *
     * 🔴 **It is ambiguous at the instant it arrives**, because on at least one phone an incoming
     *    call takes focus the same way a music player does.
     *
     * 🔴 **And the evidence can arrive either side of it.** Measured 2026-09-17: on one call the
     *    focus loss came **two seconds after** the ring started, on another it came **one second
     *    after the ring had already stopped** - so looking only at what is ringing *now*, or only
     *    at what rings *after* the loss, misses it either way. [callRecently] is therefore a
     *    window, not an instant: a call seen shortly before counts just as much as one seen after.
     *
     * @param settled whether long enough has passed to trust that no call is coming.
     */
    fun resolveLoss(callRecently: Boolean, settled: Boolean): Focus =
        if (callRecently || !settled) Focus.LOST_TRANSIENT else Focus.LOST

    /**
     * How long a call signal keeps counting after it stops, **while only a ring has been seen.**
     *
     * 🔑 **It does two jobs**: it lets a focus loss be matched to a ring that has already ended,
     *    and it stops the stream flapping back on in the gap between a ring and the call that
     *    follows it. Both are the same uncertainty - *is something still coming?* - so they are
     *    one window.
     * 🔴 **Do not shorten this to make the sound come back sooner.** The gap it rides out is the
     *    moment somebody answers, and a stream that flaps on there is worse than one that waits.
     *    What actually wanted shortening is [CALL_TAIL_MS].
     */
    const val CALL_MEMORY_MS = 3_000L

    /**
     * The same window **once a conversation has actually been running** - see
     * AudioFocusController.conversationActive.
     *
     * 🔑 **Nothing more is coming after a conversation ends**, so there is no handover to ride
     *    out - only the moment or two the telephony stack takes to put the mode back and stop
     *    reporting the call. That is all this has to cover, and it is what a person notices:
     *    the pause after hanging up, and three seconds of it was reported as too slow
     *    (2026-09-18).
     * 🔴 **Not much shorter than this.** These signals are polled, and one reading that lands in
     *    a gap mid-call would put the stream back on *during the call* - far worse than waiting.
     */
    const val CALL_TAIL_MS = 1_200L

    /**
     * How long to wait before believing that a permanent loss really was another app.
     *
     * 🔑 **Measured: the loss arrived two seconds after the ring began** (2026-09-17), so this has
     *    to be comfortably longer than that gap and not merely longer than zero.
     */
    const val LOSS_GRACE_MS = 5_000L

    enum class Action {
        /** Carry on. */
        PLAY,

        /** Stay on, quieter. */
        DUCK,

        /** Silence, but keep draining the socket - see ScreamReceiver.paused. */
        MUTE,

        /** Give up the session entirely. */
        STOP,
    }

    /**
     * 🔴 **[CallBehavior.IGNORE] wins over everything, including the call check.** Somebody who
     *    chose "ignore" is using the phone as a speaker and means it; yielding anyway because
     *    telephony said so would make the setting a lie.
     */
    fun decide(
        behavior: CallBehavior,
        focus: Focus,
        callRecently: Boolean,
        duckOnNotification: Boolean,
        alertSounding: Boolean = false,
    ): Action {
        if (behavior == CallBehavior.IGNORE) return Action.PLAY
        if (focus == Focus.LOST) return Action.STOP
        if (focus == Focus.LOST_TRANSIENT || callRecently) {
            return if (behavior == CallBehavior.DUCK) Action.DUCK else Action.MUTE
        }
        // 🔑 [alertSounding] only ever arrives while sharing the speaker: the rest of the time
        //    the app holds focus and the **system** does the ducking, and doing it here as well
        //    would turn one duck into two.
        if (focus == Focus.LOST_TRANSIENT_DUCK || alertSounding) {
            return if (duckOnNotification) Action.DUCK else Action.PLAY
        }
        return Action.PLAY
    }

    /**
     * How far down "quieter" is, as a multiplier on whatever the fader is set to.
     *
     * 🔑 A fifth of the amplitude, about -14 dB: far enough under a notification or a voice to
     *    stop competing with it, near enough that the stream is still there rather than gone.
     */
    const val DUCK_GAIN = 0.2f
}
