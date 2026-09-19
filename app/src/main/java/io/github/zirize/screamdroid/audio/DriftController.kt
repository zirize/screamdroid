package io.github.zirize.screamdroid.audio

/**
 * Decides, from one number - how full the ring is - whether to play, to pad, to wait, and how much
 * to throw away.
 *
 * 🔑 **A pure state machine, deliberately.** It touches no socket and no audio device, so the
 *    awkward cases (a stall that lasts minutes, a buffer that creeps full over an hour) can be
 *    played through in a unit test in microseconds instead of being waited for on a phone.
 *
 * Why each rule exists:
 * - **Start only at [startThresholdMs].** Starting the moment a first packet lands means playing
 *   with no cushion at all, and the next bit of jitter is an underrun.
 * - **Drop back to [targetMs], not to [maxMs].** The sender's clock and the phone's DAC never run
 *   at exactly the same rate, so the buffer drifts one way over minutes. Trimming to the ceiling
 *   would just put it back at the ceiling a moment later - trimming to the target buys the whole
 *   margin again.
 * - 🔴 **Pad before giving up.** A gap is not a disconnection. Over Wi-Fi this sender has produced
 *   stalls lasting minutes that cleared on their own, and a receiver that rebuffers at the first
 *   empty read turns every one of them into a restart. So silence is fed while the buffer is dry
 *   and only a drought longer than [padBudgetMs] counts as "this stream has stopped".
 */
class DriftController(
    val targetMs: Int,
    val maxMs: Int,
    val startThresholdMs: Int = targetMs,
    /** How long to keep padding a dry buffer before rebuffering instead. */
    val padBudgetMs: Int = maxMs,
) {
    init {
        require(targetMs > 0) { "targetMs must be positive" }
        require(maxMs > targetMs) { "maxMs must be above targetMs" }
        require(startThresholdMs > 0) { "startThresholdMs must be positive" }
        require(padBudgetMs >= 0) { "padBudgetMs must not be negative" }
    }

    enum class Action {
        /** Not enough held yet: play nothing, let it fill. */
        WAIT,

        /** Play from the buffer. */
        PLAY,

        /** The buffer is dry but the stream is not written off: feed silence. */
        PAD,
    }

    data class Decision(val action: Action, val dropMs: Int = 0)

    var isPlaying: Boolean = false
        private set

    /** How long the buffer has been dry in the current drought. */
    var paddedMs: Int = 0
        private set

    /**
     * @param fillMs total latency: what the ring holds *plus* what the device still has to play
     * @param ringMs what the ring alone holds - what there is left to play
     * @param stepMs how much the caller is about to play or pad in one go - what a pad costs
     *
     * 🔴 **Two numbers because they answer two different questions, and one of them used to
     *    answer both wrongly.** How much delay to trim is about the total, device queue included
     *    (that was learned the hard way - see AudioSink.queuedFrames). Whether the stream has run
     *    dry is about the ring alone: the device queue during a drought holds *this class's own
     *    padding*, so measuring the total let the padding prove to itself that there was still
     *    audio to play. [padBudgetMs] then never ran out and the receiver padded through the whole
     *    silence instead of settling into [Action.WAIT] - measured 2026-09-18, latency cycling
     *    between 2 and 10 ms for a full minute after the sender had stopped, with the audio device
     *    written to throughout.
     */
    fun decide(fillMs: Int, ringMs: Int, stepMs: Int): Decision {
        if (!isPlaying) {
            if (fillMs < startThresholdMs) return Decision(Action.WAIT)
            isPlaying = true
            paddedMs = 0
        }

        if (ringMs <= 0) {
            paddedMs += stepMs
            if (paddedMs > padBudgetMs) {
                // Long enough to call it stopped. Refill from the threshold rather than dribbling
                // out whatever arrives next.
                reset()
                return Decision(Action.WAIT)
            }
            return Decision(Action.PAD)
        }

        paddedMs = 0
        if (fillMs > maxMs) return Decision(Action.PLAY, dropMs = fillMs - targetMs)
        return Decision(Action.PLAY)
    }

    /**
     * Carry on playing, without waiting to refill first.
     *
     * 🔑 **For a band that moves under a stream that is already running.** The device's own buffer
     *    is trimmed while audio plays (DeviceBufferTuner), which changes the floor the target sits
     *    on, which means a new controller several times a minute. Building one in the usual state
     *    would make each of those a rebuffer - a gap of silence and a fade - for a change the
     *    listener has no reason to hear. Nothing has been lost, so nothing has to be refilled.
     */
    fun resume() {
        isPlaying = true
        paddedMs = 0
    }

    /** Back to buffering from empty - after a format change, a focus loss, or a long drought. */
    fun reset() {
        isPlaying = false
        paddedMs = 0
    }
}
