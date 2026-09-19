package io.github.zirize.screamdroid.audio

/**
 * Keeps the audio device's buffer as small as it can be without breaking the sound up.
 *
 * 🔴 **Most of the latency is not ours.** `AudioTrack` hands out a buffer far deeper than a
 *    blocking writer needs - measured on the test phone, 160 ms over the speaker and 81 ms over
 *    Bluetooth - and a blocking writer keeps it near full, so that depth *is* the delay. It is
 *    also the one part [AudioSink] cannot trim once audio is in it, which is why the cushion
 *    belongs in the ring instead, where [DriftController] can act on it.
 *
 * 🔑 **`getMinBufferSize` is a floor on *creating* a track, not on running it.** The size can be
 *    set on a live track, and [AudioTrackSink] asks the device for its own minimum rather than
 *    guessing at a burst size.
 *
 * 🔴 **Small at the open, and only ever bigger afterwards.** An earlier version searched downwards
 *    while the stream played - halving, proving, halving again - and every step of that search was
 *    audible: measured on the phone's speaker 2026-09-18, the descent cost four trims in the first
 *    sixteen seconds and the listener heard each one, while the size it arrived at was clean for
 *    the three minutes that followed. The arriving was the whole cost, so this does not walk: it
 *    opens at the size it wants. Nothing has been heard yet at an open, so that costs nothing.
 *
 * 🔴 **Growing is not free and shrinking was.** Every frame added to the buffer has to be supplied
 *    by the ring, and the sender only ever sends in real time - so the stream must be quiet for
 *    exactly as long as the buffer grew, and splitting the growth into smaller steps does not
 *    change that total. What does change it is *when*: at a silence there is nothing to interrupt.
 *    This sender falls quiet after every alert sound, so the next free moment is always close.
 *
 * 🚫 Nothing here allocates or logs. It is called from the playback thread on every write.
 */
class DeviceBufferTuner(
    /** What the device handed out when the track was built - the way back, and the last resort. */
    val startFrames: Int,
    /**
     * How much audio must play after a start or a resize before what the device reports counts.
     *
     * 🔴 **Underruns around a start are not evidence about the buffer.** The audio path takes a
     *    moment to wake up and reports the gap as an underrun whatever the size is - and with the
     *    sender suppressing silence the device restarts every couple of seconds.
     */
    val settleFrames: Long,
    /**
     * How long the stream has to keep breaking up before growing is worth a gap of its own.
     *
     * 🔑 Counted in frames played *without* a lull - see [sinceStartFrames]. A stream that stops
     *    every second or two never reaches this, and does not need to.
     */
    val patienceFrames: Long,
    /** The smallest step worth taking. */
    val granularityFrames: Int,
    /**
     * How big the buffer may grow before the whole idea is abandoned.
     *
     * 🔴 **Not a cap that can starve the stream.** Growing past here does not stop at here - it
     *    means this device was not worth trimming, so its own size is handed back once and never
     *    touched again. The worst case is exactly what the app did before any of this existed,
     *    reached in one step instead of by creeping there.
     */
    val ceilingFrames: Int = startFrames / 2,
) {
    /** What the device is set to right now. */
    var frames: Int = startFrames
        private set

    /** Frames played since the device last started or was resized - the warm-up window. */
    var sinceStartFrames: Long = 0L
        private set

    /** A break-up said this size is too small, and the buffer has not been grown yet. */
    var owesGrowth: Boolean = false
        private set

    /** This device was not worth trimming: its own size stands, for good. */
    var surrendered: Boolean = false
        private set

    /** How many times the buffer had to be grown. */
    var growths: Int = 0
        private set

    private var underruns: Int = 0

    /** Audio reached the device. */
    fun played(count: Int) {
        if (count > 0) sinceStartFrames += count
    }

    /**
     * Real audio is not reaching the device right now - see AudioSink.starved.
     *
     * 🔑 It re-arms the warm-up window: the size is not on trial here, it simply cannot be judged
     *    for the next moment.
     */
    fun interrupted() {
        sinceStartFrames = 0L
    }

    /**
     * @param deviceUnderruns `AudioTrack.getUnderrunCount()`, which only ever rises
     * @return a bigger size to set right now, or 0 to leave the device alone
     */
    fun next(deviceUnderruns: Int): Int {
        if (surrendered) return 0
        if (sinceStartFrames < settleFrames) {
            // Still waking up. Take whatever it reports as the new baseline rather than a verdict.
            underruns = deviceUnderruns
            return 0
        }
        if (deviceUnderruns <= underruns) return 0
        underruns = deviceUnderruns

        // 🔴 An underrun at the size the device chose itself says nothing about the buffer -
        //    nothing has been taken away, so the cause is a late thread or a gap on the wire.
        if (frames >= startFrames) return 0

        val breakingContinuously = sinceStartFrames >= patienceFrames
        sinceStartFrames = 0L
        owesGrowth = true
        // Only a stream that keeps breaking up without ever falling quiet is worth a gap of its
        // own. Anything else waits for the silence that is already coming.
        return if (breakingContinuously) atRest() else 0
    }

    /**
     * The size to set while the device is empty - at an open, or after a pause.
     *
     * 🔑 **The one moment growing the buffer costs nothing.** There is nothing in the device to
     *    run out of, and the receiver refills from its threshold anyway, so the extra depth is
     *    bought with silence that was already there.
     */
    fun atRest(): Int {
        if (surrendered) return startFrames
        if (!owesGrowth) return frames
        owesGrowth = false
        val step = maxOf(granularityFrames, frames / 4)
        val grown = frames + step
        if (grown > ceilingFrames) {
            // Past here the trimming has bought nothing worth the interruptions it took to keep.
            surrendered = true
            return startFrames
        }
        growths++
        return grown.coerceAtMost(startFrames)
    }

    /** What the device actually gave for the size it was last asked for. */
    fun applied(actualFrames: Int) {
        if (actualFrames <= 0) return
        frames = actualFrames
        sinceStartFrames = 0L
    }

    companion object {
        /**
         * The size to open the device at: one of the player's writes, plus a margin.
         *
         * 🔑 **That is all the device's own buffer has to be.** It exists so a write does not have
         *    to wait in the middle of itself; every frame beyond that is delay the ring could be
         *    holding instead, where DriftController can trim it and the buffer presets mean
         *    something. Measured on the phone 2026-09-18: at this size three minutes of music
         *    played with no dropouts and no adjustment at all, against 160 ms of device buffer if
         *    the device is left to choose.
         *
         * 🔴 A device buffer under one write makes every write block halfway through itself, so
         *    the write frames are the floor here, not a target.
         */
        fun openSize(
            writeChunkFrames: Int,
            probedMinFrames: Int,
            offeredFrames: Int,
            granularityFrames: Int,
        ): Int = (writeChunkFrames + granularityFrames)
            .coerceAtLeast(probedMinFrames)
            .coerceAtMost(offeredFrames)
    }
}
