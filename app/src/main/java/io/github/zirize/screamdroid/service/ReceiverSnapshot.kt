package io.github.zirize.screamdroid.service

import io.github.zirize.screamdroid.audio.IdlePolicy
import io.github.zirize.screamdroid.net.ScreamHeader
import io.github.zirize.screamdroid.net.ScreamReceiver
import io.github.zirize.screamdroid.settings.Settings

/**
 * Why the receiver is on but not listening.
 *
 * 🔑 **"Nothing is arriving" and "we are refusing to listen" must not look the same.** Both are
 *    silence, and only one of them is something the person can do anything about.
 */
enum class Blocked {
    NONE,

    /** On mobile data with "receive on mobile data" off. */
    MOBILE_DATA,
}

/**
 * Why the stream is silent while the receiver is still reading it.
 *
 * 🔑 **Muting by hand and being muted by a call are the same machinery and different news.** Both
 *    keep draining the socket and throwing it away; only one of them ends by itself, and that is
 *    the sentence a person needs on the screen. Distinguishing them here is what keeps the screen
 *    from having to guess.
 * 🔑 [CALL] and [FOCUS] are both "something else is being heard", and they are separated because
 *    only one of them can be named: the audio mode says whether telephony is in a call, and
 *    audio focus - which is what actually notices - cannot say who took it.
 */
enum class PauseCause {
    NONE,

    /** Somebody pressed mute. */
    USER,

    /** Telephony says there is a call. */
    CALL,

    /** Something else took the speaker: an alarm, navigation, another app's playback. */
    FOCUS,
}

/**
 * Everything anybody outside the audio threads is allowed to know, taken at one instant.
 *
 * 🔑 One object rather than a handful of observable fields: the notification and the screen must
 *    never show a state from one moment and a format from another, and a single immutable
 *    snapshot makes that impossible rather than merely unlikely.
 */
data class ReceiverSnapshot(
    val running: Boolean = false,
    val paused: Boolean = false,
    val pauseCause: PauseCause = PauseCause.NONE,
    val blocked: Blocked = Blocked.NONE,
    /** Playing, but quieter, because something else is being heard over it. */
    val ducked: Boolean = false,
    val state: ScreamReceiver.State = ScreamReceiver.State.STOPPED,
    val idleTier: IdlePolicy.Tier = IdlePolicy.Tier.ACTIVE,
    val settings: Settings = Settings(),
    val effectiveTargetMs: Int = 0,
    val effectiveMaxMs: Int = 0,
    val sender: String? = null,
    val senderPort: Int = 0,
    val format: ScreamHeader? = null,
    val latencyMs: Int = 0,
    val deviceMs: Int = 0,
    /** What the audio device's buffer is set to now, and what it was at open, in milliseconds. */
    val deviceBufferMs: Int = 0,
    val deviceBufferInitialMs: Int = 0,
    val packets: Long = 0,
    val packetsPerSecond: Int = 0,
    val kbitPerSecond: Int = 0,
    val underruns: Long = 0,
    val droppedMs: Long = 0,
    val overflowBytes: Long = 0,
    val malformed: Long = 0,
    val discarded: Long = 0,
    val formatChanges: Long = 0,
    /** How long this listening session has been up. Survives a port change - see ScreamReceiver. */
    val uptimeMs: Long = 0,
    /** How long the stream has been muted, or 0 when it is not. */
    val mutedForMs: Long = 0,
    /** Loudest sample since the last poll, per side, as a fraction of the meter. */
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    val error: String? = null,
) {
    /** Sound is actually coming out, as opposed to merely being switched on. */
    val playing: Boolean
        get() = running && !paused && state == ScreamReceiver.State.PLAYING

    /** Silenced by something else rather than by the person holding the phone. */
    val interrupted: Boolean
        get() = pauseCause == PauseCause.CALL || pauseCause == PauseCause.FOCUS
}

/**
 * Why the session is not running any more.
 *
 * 🔑 **A receiver that switched itself off owes an explanation.** Giving up the speaker for good
 *    is the right thing to do when another media app takes it - holding a wake lock and a Wi-Fi
 *    lock to discard audio indefinitely is not - but "it is just off now" is exactly the kind of
 *    silent mystery this app exists to avoid.
 */
enum class StopReason { NONE, FOCUS_LOST }
