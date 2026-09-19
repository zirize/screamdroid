package io.github.zirize.screamdroid.audio

import io.github.zirize.screamdroid.net.ScreamHeader

/**
 * Where decoded audio goes.
 *
 * 🔑 An interface because the playback path is the one part that may have to be swapped: if four
 *    tracks or a lower latency ever force a move from `AudioTrack` to AAudio, only this changes.
 *    It also keeps the device out of the receiver's logic, which is what makes the rest testable.
 */
interface AudioSink {

    /** Open (or reopen) the device for [header]. Must be safe to call when already open. */
    fun open(header: ScreamHeader)

    /** Blocking write. Returns bytes accepted, or a negative value on error. */
    fun write(data: ByteArray, offset: Int, length: Int): Int

    /**
     * Frames handed to the device that have not been played yet.
     *
     * 🔴 **Without this the receiver measures the wrong thing.** The device has a buffer of its
     *    own, and audio sitting in it is latency exactly as much as audio sitting in the ring -
     *    but it is latency the ring cannot see. A receiver that watches only its own buffer calls
     *    a stream starved while the speaker still has 80 ms to play, and then "helpfully" writes
     *    silence into the middle of it.
     */
    fun queuedFrames(): Int

    /**
     * Stop the device but keep it open, discarding whatever it still holds.
     *
     * 🔑 The cheap half of going idle: the audio path is freed, and the next write resumes with no
     *    reopen. What it discards is already stale - nothing reaches this call until the stream has
     *    been quiet for over a second.
     */
    fun pause()

    /**
     * Real audio is not being fed to the device right now - the ring is dry, or muted, or waiting
     * to refill. Called on every turn that this is true, not once at the start of one.
     *
     * 🔴 **The device underruns in a drought whatever size its buffer is**, and the sink cannot
     *    tell that apart from a buffer that is too small - it sees writes, or the lack of them,
     *    either way. Without this the buffer search reads every gap on the wire as "too small"
     *    and gives back everything it found: measured 2026-09-17, a gated signal walked 41 ms
     *    straight back to 81 four times over; and again 2026-09-18 on the speaker, where the
     *    *waiting* state was still missing, 42 ms crept back to 158.
     *
     * 🔑 **Every step back up is a refill, and a refill is a gap the listener hears.** That is why
     *    this matters more than it looks: the cost of a wrong verdict is not a lost opportunity,
     *    it is a click.
     */
    fun starved()

    fun release()

    /**
     * Playback gain, 0..1, applied to everything written from here on.
     *
     * 🔑 **The app's own fader, not the system volume.** Turning the phone down turns every app
     *    down; this turns down the one stream, which is what somebody reaches for when the PC is
     *    louder than the room wants. It survives reopening the device - see [open].
     */
    var gain: Float

    val isOpen: Boolean

    /**
     * Has the device produced a single frame since it was opened?
     *
     * 🔑 It takes the audio path a moment to wake up, and audio keeps arriving meanwhile. While
     *    this is still false, nothing has been heard yet - which is what makes it safe to throw
     *    the whole backlog away instead of cutting a hole in the middle of it.
     */
    val hasStarted: Boolean

    /**
     * How much audio the device's own buffer holds, in milliseconds, or 0 when it is not open.
     *
     * 🔴 **This is a floor no setting can go under.** A blocking writer keeps the device's buffer
     *    near full, so latency can never fall below it. A preset asking for less does not get
     *    less - it gets a receiver that sits above its own ceiling and trims over and over, which
     *    is audible. That is why the receiver raises its target to meet this rather than ignoring
     *    it.
     *
     * 🔑 **It moves while the stream plays.** The implementation is allowed to trim the device's
     *    buffer down towards what it will actually take, so a reader must not cache this - see
     *    [deviceBufferInitialMs] and DeviceBufferTuner.
     */
    val deviceBufferMs: Int

    /**
     * What the device handed out when the track was built, before any trimming, or 0 when it is
     * not open.
     *
     * 🔑 Kept only so the difference can be seen. "48 ms" means nothing on its own; "48 ms, and
     *    the device wanted to give us 82" is the whole of what the trimming bought, and it is the
     *    number to look at when somebody asks whether the latency work did anything.
     */
    val deviceBufferInitialMs: Int
}
