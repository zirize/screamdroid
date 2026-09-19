package io.github.zirize.screamdroid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import io.github.zirize.screamdroid.net.ScreamHeader
import io.github.zirize.screamdroid.net.ScreamProtocol

/**
 * The `AudioTrack` implementation of [AudioSink].
 *
 * 🔑 **The blocking write is the clock.** Nothing in this app paces playback with a timer: the
 *    device accepts bytes at exactly the rate it plays them, so the playback thread is throttled
 *    by hardware. A timer would drift against the DAC and there would be nothing to correct it
 *    with.
 *
 * 🔑 **The device's buffer is set after the track is built, not before.** `getMinBufferSize` is a
 *    floor on *creating* a track and the device hands out far more depth than a blocking writer
 *    needs - measured 160 ms over the speaker, 81 over Bluetooth. The size can be set on a live
 *    track, so it is set small at the open, where nothing has been heard yet and it costs nothing.
 *    [DeviceBufferTuner] only ever grows it from there. See [openBufferFrames] and [tick].
 */
class AudioTrackSink : AudioSink {

    private var track: AudioTrack? = null
    private var openFormat: ScreamHeader? = null
    private var framesWritten = 0L

    private var tuner: DeviceBufferTuner? = null

    /**
     * What the search learned last time this format was open, so a reopen after a drought does not
     * start the whole descent again - and, more to the point, does not rediscover the size that
     * breaks up by breaking up at it.
     *
     * 🚫 Deliberately not persisted to disk. The right size depends on which output is connected -
     *    speaker, wire, Bluetooth - and the app is not told when that changes, so a figure
     *    remembered across restarts would be a figure learned about some other output.
     */
    private var learnedFor: ScreamHeader? = null
    private var learnedFrames = 0

    /**
     * 🔑 Kept here as well as on the track, because [open] builds a **new** `AudioTrack` - after a
     *    format change or an idle release the device would otherwise come back at full volume.
     */
    @Volatile
    override var gain: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            field = clamped
            runCatching { track?.setVolume(clamped) }
        }

    override val isOpen: Boolean get() = track != null

    override val hasStarted: Boolean get() = (track?.playbackHeadPosition ?: 0) > 0

    /**
     * 🔑 **Published, not measured on demand.** The playback thread reads this on every pass and
     *    the service polls it for the screen; caching what the last resize produced keeps both off
     *    the device and makes the two readers agree.
     */
    @Volatile
    override var deviceBufferMs: Int = 0
        private set

    @Volatile
    override var deviceBufferInitialMs: Int = 0
        private set

    override fun open(header: ScreamHeader) {
        if (openFormat == header && track != null) return
        release()

        val channelMask = androidChannelMask(header)
        val minBuffer = AudioTrack.getMinBufferSize(header.sampleRate, channelMask, ENCODING)
        if (minBuffer <= 0) {
            Log.w(TAG, "device refuses ${header.sampleRate}Hz ${header.channels}ch (min=$minBuffer)")
            return
        }

        // 🔑 Twice the minimum, and no more. This is the track's *capacity*, which is fixed for the
        //    life of the track and cannot be raised afterwards - so it has to leave room to back
        //    off into. What is actually in use is set below and moves while the stream plays.
        val bufferBytes = minBuffer * 2

        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(header.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        // 🔑 Not play() yet - see write(). Starting the device before there is anything to play
        //    means it warms up against an empty buffer while packets keep arriving, and that
        //    warm-up shows up as latency nobody asked for: measured 2026-09-17, it put the stream
        //    over the ceiling at every start and the controller trimmed it back with an audible
        //    cut.
        track = built
        openFormat = header
        framesWritten = 0L
        built.setVolume(gain)

        val startFrames = built.bufferSizeInFrames
        deviceBufferInitialMs = framesToMs(startFrames, header.sampleRate)
        deviceBufferMs = deviceBufferInitialMs
        tuner = buildTuner(built, header, startFrames)

        Log.i(
            TAG,
            "open ${header.sampleRate}Hz ${header.channels}ch mask=0x%x capacity=$bufferBytes B "
                .format(channelMask) +
                "offered=$startFrames frames (${deviceBufferInitialMs} ms) " +
                (tuner?.let { "opened at ${it.frames} frames (${deviceBufferMs} ms)" }
                    ?: "not resizable"),
        )
    }

    /**
     * Put the device on the size we want it at and set up the watch, or return null if this
     * device will not be resized.
     *
     * 🔑 **The minimum is asked for, not guessed at.** Asking for zero frames gets back whatever
     *    the device clamps that to, which is its own floor - there is no public API for the burst
     *    size, and a guessed one would be wrong on exactly the devices that matter.
     */
    private fun buildTuner(t: AudioTrack, header: ScreamHeader, startFrames: Int): DeviceBufferTuner? {
        val probed = runCatching { t.setBufferSizeInFrames(0) }.getOrDefault(-1)
        if (probed <= 0 || startFrames <= 0) {
            Log.w(TAG, "buffer not resizable (probe=$probed)")
            runCatching { t.setBufferSizeInFrames(startFrames) }
            return null
        }

        val granularity = maxOf(1, header.sampleRate / 200)      // 5 ms
        val carried = if (learnedFor == header) learnedFrames else 0
        val want = if (carried in 1..startFrames) {
            carried
        } else {
            DeviceBufferTuner.openSize(
                writeChunkFrames = ScreamProtocol.MAX_PAYLOAD_BYTES * WRITE_CHUNK_PACKETS /
                    header.bytesPerFrame,
                probedMinFrames = probed,
                offeredFrames = startFrames,
                granularityFrames = granularity,
            )
        }
        val actual = runCatching { t.setBufferSizeInFrames(want) }.getOrDefault(-1)
        if (actual <= 0) {
            Log.w(TAG, "buffer would not take $want frames")
            runCatching { t.setBufferSizeInFrames(startFrames) }
            return null
        }

        val tuner = DeviceBufferTuner(
            startFrames = startFrames,
            // Half a second of warm-up, well clear of what waking the audio path costs.
            settleFrames = header.sampleRate.toLong() / 2,
            // 🔑 A second and a half of unbroken audio. This sender falls quiet long before that
            //    between alert sounds, so growth normally rides on a silence; a stream that really
            //    does run without stopping still gets help, just not for free.
            patienceFrames = header.sampleRate.toLong() * 3 / 2,
            granularityFrames = granularity,
        )
        tuner.applied(actual)
        deviceBufferMs = framesToMs(actual, header.sampleRate)
        learnedFor = header
        learnedFrames = actual
        return tuner
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        val t = track ?: return 0
        if (framesWritten == 0L) {
            t.play()
            restart(t)
        }
        val written = t.write(data, offset, length)
        val frame = openFormat?.bytesPerFrame ?: return written
        if (written > 0) {
            val frames = written / frame
            framesWritten += frames
            tick(t, frames)
        }
        return written
    }

    /**
     * The device is starting again after [pause] - or for the first time since [open].
     *
     * 🔴 **The size has to be set again here.** `flush()` hands the buffer back at whatever the
     *    device likes, so a size found before a drought does not necessarily survive it: measured
     *    on the phone 2026-09-17, a gated signal came back at 81 ms every time. Re-asking is a
     *    no-op where it did survive.
     */
    private fun restart(t: AudioTrack) {
        val tuner = tuner ?: return
        val header = openFormat ?: return
        // 🔑 **The device is empty here, which is the one moment the buffer may grow.** Anywhere
        //    else the frames added have to come out of the ring, and the ring is only filled in
        //    real time - so growing mid-stream is a hole in the sound of exactly that size.
        //    See DeviceBufferTuner.atRest.
        resize(t, header, tuner, tuner.atRest())
    }

    /**
     * One turn of the buffer search, on the back of a write that has just succeeded.
     *
     * 🚫 No allocation, no lock and no logging: this runs on the playback thread. Both device
     *    calls read an int out of the shared control block, and the tuner is a state machine over
     *    four fields.
     *
     * 🔑 **Resizing while audio is playing is free.** Lowering the size does not throw anything
     *    away - the frames already in the device play out and the writer simply blocks sooner, so
     *    the delay moves from the device into the ring, where [DriftController] can trim it with
     *    the fade it already applies to every other cut.
     */
    private fun tick(t: AudioTrack, frames: Int) {
        val tuner = tuner ?: return
        val header = openFormat ?: return
        tuner.played(frames)
        val want = tuner.next(t.underrunCount)
        if (want > 0 && want != tuner.frames) resize(t, header, tuner, want)
    }

    private fun resize(t: AudioTrack, header: ScreamHeader, tuner: DeviceBufferTuner, want: Int) {
        val actual = runCatching { t.setBufferSizeInFrames(want) }.getOrDefault(-1)
        if (actual <= 0) {
            this.tuner = null
            return
        }
        tuner.applied(actual)
        deviceBufferMs = framesToMs(actual, header.sampleRate)
        learnedFor = header
        learnedFrames = actual
    }

    /**
     * 🔑 `getPlaybackHeadPosition()` is a 32-bit frame counter that wraps, and so is the low half
     *    of what has been written. Subtracting them as Ints is right across the wrap; widening
     *    either one first would not be.
     */
    override fun queuedFrames(): Int {
        val t = track ?: return 0
        val queued = framesWritten.toInt() - t.playbackHeadPosition
        return if (queued > 0) queued else 0
    }

    override fun starved() {
        tuner?.interrupted()
    }

    override fun pause() {
        val t = track ?: return
        runCatching { t.pause(); t.flush() }
        // 🔑 Zeroing this is what makes the next write call play() again, and it keeps the queue
        //    figure honest: flush() throws away frames that were counted as written.
        framesWritten = 0L
    }

    override fun release() {
        track?.let {
            // 🔴 stop(), not pause()+flush(). Flushing throws away whatever has not reached the
            // speaker yet - including the fade the player writes on its way out, which is the
            // one thing standing between closing the device and a click. stop() plays it out.
            runCatching {
                it.stop()
                Thread.sleep(DRAIN_MS)
            }
            it.release()
            framesWritten = 0L
        }
        track = null
        openFormat = null
        tuner = null
        deviceBufferMs = 0
        deviceBufferInitialMs = 0
    }

    private companion object {
        const val TAG = "AudioTrackSink"
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Long enough for the closing fade to reach the speaker before the device goes away. */
        const val DRAIN_MS = 20L

        /** How much audio has to play clean before a buffer size is believed. */
        const val PROVE_SECONDS = 2L

        /** How many packets' worth the receiver hands over in one write - see ScreamReceiver. */
        const val WRITE_CHUNK_PACKETS = 4

        fun framesToMs(frames: Int, sampleRate: Int): Int =
            if (sampleRate <= 0) 0 else (frames.toLong() * 1000L / sampleRate).toInt()

        /**
         * 🔑 `SPEAKER_*` shifted two bits is `CHANNEL_OUT_*` (see ScreamHeader), which covers every
         *    layout a sender describes properly. A sender that leaves the mask empty - or gives one
         *    this device will not take - still has a channel *count*, so fall back to that.
         */
        fun androidChannelMask(header: ScreamHeader): Int {
            val shifted = header.androidChannelMask
            if (shifted != 0 && Integer.bitCount(shifted) == header.channels) return shifted
            return when (header.channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                4 -> AudioFormat.CHANNEL_OUT_QUAD
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }
        }
    }
}
