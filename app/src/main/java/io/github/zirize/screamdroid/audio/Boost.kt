package io.github.zirize.screamdroid.audio

/**
 * Gain above unity over 16-bit PCM, with a soft limiter so the top of the waveform bends
 * instead of clipping.
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
 * 🔑 **Linear below [KNEE], bent above it.** Quiet passages get exactly the gain asked for; only
 *    peaks that would have gone over full scale are squeezed, smoothly (the curve's slope is 1 at
 *    the knee, so there is no corner to hear) and never past full scale. Stateless, so it is a
 *    distortion on loud peaks rather than a pumping compressor - the trade for not adding a
 *    look-ahead delay.
 *
 * 🔑 Plain arrays and no Android types: this runs on the play thread, which must not allocate.
 */
object Boost {

    /** Fraction of full scale where the limiter starts to bend. */
    const val KNEE = 0.5f

    /** Multiply [pcm] by [gain] (expected > 1) in place, through the soft limiter. */
    fun apply(pcm: ByteArray, offset: Int, length: Int, gain: Float) {
        if (gain <= 1f) return
        var at = offset
        val end = offset + (length and 1.inv())
        while (at < end) {
            val v = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort()
            val out = (limit(v / FULL_SCALE * gain) * FULL_SCALE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[at] = (out and 0xFF).toByte()
            pcm[at + 1] = ((out shr 8) and 0xFF).toByte()
            at += 2
        }
    }

    /** The limiter curve on a sample in full-scale units; |result| stays below 1. */
    fun limit(x: Float): Float {
        val a = if (x < 0f) -x else x
        if (a <= KNEE) return x
        val room = 1f - KNEE
        val u = (a - KNEE) / room
        val bent = KNEE + room * u / (1f + u)
        return if (x < 0f) -bent else bent
    }

    private const val FULL_SCALE = 32768f
}
