package io.github.zirize.screamdroid.net

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the two audio threads are allowed to say about themselves.
 *
 * 🔑 **Atomic counters and nothing else.** Neither thread may allocate, lock or log, so they
 *    cannot build a status object or post one anywhere; they add to a number. The UI reads the
 *    numbers on its own schedule. That is also why nothing here is a timestamped event log -
 *    an event list would mean allocating per packet.
 */
class ReceiverStats {
    val packets = AtomicLong()
    val bytes = AtomicLong()

    /** Packets that were not a Scream header, or too short to hold one. */
    val malformed = AtomicLong()

    /** Bytes the ring had no room for - the receiver outran playback. */
    val overflowBytes = AtomicLong()

    /** Times the sender changed format mid-stream. */
    val formatChanges = AtomicLong()

    /** Times the buffer ran dry while playing, and silence was fed instead. */
    val underruns = AtomicLong()

    /** Milliseconds thrown away to bring latency back to target. */
    val droppedMs = AtomicLong()

    /** Packets discarded on purpose - during a format change, or while another app has focus. */
    val discarded = AtomicLong()

    /** `SystemClock.elapsedRealtime()` of the last packet, or 0. */
    val lastPacketAt = AtomicLong()

    /** Latency right now: the ring plus what the device still holds, in milliseconds. */
    val fillMs = AtomicInteger()

    /** The part of that which is inside the audio device and can no longer be trimmed. */
    val deviceMs = AtomicInteger()

    /**
     * Loudest sample written since somebody last looked, per side, 0..[Peaks.FULL_SCALE].
     *
     * 🔑 **Read with `getAndSet(0)`, which is what makes the meter fall back down.** The playback
     *    thread only ever raises these; the reader takes the high-water mark and leaves zero
     *    behind, so a poll that sees no writes reports silence without anybody having to decide
     *    when the stream stopped.
     */
    val peakLeft = AtomicInteger()
    val peakRight = AtomicInteger()

    /**
     * Raise [peakLeft] / [peakRight] to [left] / [right] if they are louder.
     *
     * 🔑 Compare-and-set rather than get-then-set: the reader zeroes these from another thread,
     *    and a plain read-modify-write would now and then drop a peak into the gap. 🚫 No
     *    `accumulateAndGet` - it takes a capturing lambda, and the playback thread may not
     *    allocate.
     */
    fun reportPeaks(left: Int, right: Int) {
        var cur = peakLeft.get()
        while (left > cur && !peakLeft.compareAndSet(cur, left)) cur = peakLeft.get()
        cur = peakRight.get()
        while (right > cur && !peakRight.compareAndSet(cur, right)) cur = peakRight.get()
    }

    fun reset() {
        packets.set(0); bytes.set(0); malformed.set(0); overflowBytes.set(0)
        formatChanges.set(0); underruns.set(0); droppedMs.set(0); discarded.set(0)
        lastPacketAt.set(0); fillMs.set(0); deviceMs.set(0)
        peakLeft.set(0); peakRight.set(0)
    }
}
