package io.github.zirize.screamdroid.settings

/**
 * What the receiver does when something else wants the speaker.
 *
 * 🔑 **Three answers because the right one depends on the room, not on the app.** Somebody
 *    listening to a PC at a desk wants the stream out of the way of a call; somebody using the
 *    phone as a speaker across the room may want it to carry on.
 *
 * ℹ️ Stored and shown from M4 on; the audio-focus machinery that acts on it arrives with M5
 *    (docs/architecture.md, "When a call arrives").
 */
enum class CallBehavior {
    /** Mute for the duration - keep draining the socket, throw it away, resume live. */
    MUTE,

    /** Stay on, quietly, so the stream is still there underneath. */
    DUCK,

    /** Carry on unchanged. */
    IGNORE,
}
