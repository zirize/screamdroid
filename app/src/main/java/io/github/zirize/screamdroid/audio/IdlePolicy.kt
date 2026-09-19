package io.github.zirize.screamdroid.audio

/**
 * How long silence has to last before the receiver starts letting go of things.
 *
 * 🔴 **This exists because "silent" and "not arriving" are the same thing on the wire.** The
 *    sender stops transmitting after half a second of actual silence (docs/protocol.md), so a gap
 *    is the normal case, not a fault - which means the receiver cannot treat one as a
 *    disconnection, and equally cannot hold a wake lock, a low-latency Wi-Fi lock and an open
 *    audio device forever on the chance that audio comes back.
 *
 * 🔑 **Each step gives up something that is cheap to get back and expensive to keep.** Pausing the
 *    device frees the audio path but resumes instantly; releasing it frees the hardware and costs
 *    one reopen; dropping the Wi-Fi mode saves the battery that low-latency mode is designed to
 *    spend. One arriving packet undoes all of it.
 */
object IdlePolicy {

    enum class Tier {
        /** Audio is flowing, or has only just stopped. */
        ACTIVE,

        /** Long enough to pause the device, short enough that it resumes instantly. */
        STALE,

        /** Long enough to hand the audio device back. */
        IDLE,

        /** Long enough that low-latency Wi-Fi is no longer worth the battery. */
        DEEP_IDLE,
    }

    const val STALE_AFTER_MS = 1_500L
    const val IDLE_AFTER_MS = 60_000L

    /** 🔑 Ten minutes is also when the system stops a foreground service that plays nothing. */
    const val DEEP_IDLE_AFTER_MS = 600_000L

    /**
     * Awake mode: [Tier.STALE] is pushed out to where [Tier.IDLE] takes over, so the step that
     * pauses the device never happens and the audio path keeps running between sounds.
     *
     * 🔴 **Because pausing the device is not free to undo.** A paused track has to be started
     *    again, which takes about a tenth of a second, and the sound that restarts it spends that
     *    time piling up unheard - see ScreamReceiver's play loop for what that used to cost. For a
     *    desktop being listened to, sounds arrive seconds apart, so a 1.5 s step means paying that
     *    on nearly every one.
     *
     * 🔑 **It is a battery trade, which is why a charger turns it on by itself** and battery is
     *    the only case worth a setting: the receiver goes on feeding the device through the quiet,
     *    so the audio path and its wake-ups are held for as long as a minute after the last
     *    sound.
     */
    const val AWAKE_STALE_AFTER_MS = IDLE_AFTER_MS

    fun tierFor(
        msSinceLastPacket: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Tier = when {
        msSinceLastPacket >= DEEP_IDLE_AFTER_MS -> Tier.DEEP_IDLE
        msSinceLastPacket >= IDLE_AFTER_MS -> Tier.IDLE
        msSinceLastPacket >= staleAfterMs -> Tier.STALE
        else -> Tier.ACTIVE
    }
}
