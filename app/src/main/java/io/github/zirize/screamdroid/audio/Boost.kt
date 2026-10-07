package io.github.zirize.screamdroid.audio

/**
 * Gain above unity over 16-bit PCM, with a peak limiter that turns the gain down for a moment
 * instead of bending the waveform.
 *
 * 🔑 **Why the app needs to go past 1.** The phone has one media volume for every app. Somebody
 *    who wants another app quiet and this stream loud turns the phone down - and then needs this
 *    stream to come back up on its own. `AudioTrack.setVolume` stops at 1, so the part above it
 *    is done here, on the samples.
 *
 * 🚫 **Not `LoudnessEnhancer`.** An effect on the session is what the framework refuses a fast
 *    (low-latency) track, so attaching one would either fail or cost the latency this app is
 *    built around. Arithmetic on the samples costs neither.
 *
 * 🔴 **Not a waveshaper.** The first version was stateless: linear to half of full scale, bent
 *    smoothly above it. That is a distortion curve, and a boost feeds it almost everything - at
 *    +20 dB any input above -26 dBFS is past the knee, which is most of a song. Heard on the
 *    phone (2026-10-08) as the sound being squashed the moment the slider crossed 100: even at
 *    101 a full-scale peak came out at three quarters of full scale, bent.
 *
 * 🔑 **A gain that rides, not a curve.** Each frame gets one gain, [gain] or less. When a frame
 *    would go over [CEILING] the gain drops at once to exactly what fits (no look-ahead, so no
 *    added latency); afterwards it climbs back over [RELEASE_MS]. In between the waveform is
 *    scaled, not reshaped, so what is heard is the stream briefly quieter rather than distorted.
 *    The channels share the gain, so a peak on one side does not pull the image to the other.
 *
 * 🔑 Allocation-free: this runs on the play thread. One instance per receiver, because the gain
 *    it is currently at is carried from one chunk to the next.
 */
class Boost {

    /** The gain actually applied to the last frame - [gain] when nothing has had to give. */
    var current: Float = 1f
        private set

    /** The [gain] of the previous call, to tell a limiter that is holding back from one that is not. */
    private var asked: Float = 1f
    private var rate = 0
    private var release = 0f

    /** Multiply [pcm] by [gain] (expected > 1) in place, frame by frame, through the limiter. */
    fun apply(pcm: ByteArray, offset: Int, length: Int, channels: Int, sampleRate: Int, gain: Float) {
        if (gain <= 1f) {
            current = 1f
            asked = 1f
            return
        }
        if (sampleRate != rate) {
            rate = sampleRate
            // Per-frame step that closes 1 - 1/e of the remaining distance in RELEASE_MS.
            release = 1f - Math.exp(-1000.0 / (RELEASE_MS * sampleRate)).toFloat()
        }
        val frameBytes = channels * 2
        val end = offset + length - length % frameBytes
        // 🔑 Not holding a peak down, so a move of the slider takes effect at once; only the
        //    climb back from a peak is slow.
        var g = if (current >= asked) gain else current.coerceAtMost(gain)
        var at = offset
        while (at < end) {
            var peak = 0
            for (c in 0 until channels) {
                val v = sample(pcm, at + c * 2)
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
            }
            g += (gain - g) * release
            val fits = CEILING * FULL_SCALE / peak.coerceAtLeast(1)
            if (g > fits) g = fits
            for (c in 0 until channels) {
                val p = at + c * 2
                val out = Math.round(sample(pcm, p) * g)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[p] = (out and 0xFF).toByte()
                pcm[p + 1] = ((out shr 8) and 0xFF).toByte()
            }
            at += frameBytes
        }
        current = g
        asked = gain
    }

    private fun sample(pcm: ByteArray, at: Int): Int =
        ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort().toInt()

    companion object {
        /** Highest a boosted peak may reach, as a fraction of full scale (-0.5 dBFS). */
        const val CEILING = 0.944f

        /**
         * How long the gain takes to come back after a peak. Long next to a bass cycle (20 ms at
         * 50 Hz), so the gain does not wobble within a waveform; short enough that a loud hit does
         * not leave the quiet after it ducked.
         */
        const val RELEASE_MS = 250.0

        private const val FULL_SCALE = 32768f
    }
}
