package io.github.zirize.screamdroid.audio

/**
 * The loudest sample in a block, per channel - what the level meter is drawn from.
 *
 * 🔑 **Measured where the audio actually is, not guessed from the packet rate.** A packet counter
 *    says something is arriving; it cannot say whether it is music or digital silence, and those
 *    look identical on the wire. The meter has to answer "is sound coming out of the speaker",
 *    so it reads the samples on their way to the device.
 *
 * 🔑 Plain arrays, no allocation, no Android types: this runs on the playback thread for every
 *    chunk, and the arithmetic is worth testing on the JVM rather than by watching bars move.
 */
object Peaks {

    /** Full scale for 16-bit PCM. `-32768` is folded onto this, which costs nothing audible. */
    const val FULL_SCALE = 32767

    /**
     * Quietest level the meter draws at all.
     *
     * 🔑 A linear meter is useless: music sits around -20 dBFS and would never leave the bottom
     *    eighth of the bar. The scale below is decibels, and this is where it starts - low enough
     *    to show a quiet passage, high enough that dither and room noise do not light it up.
     */
    const val FLOOR_DB = -48.0

    /**
     * Peak of channels 0 and 1 over [length] bytes of 16-bit PCM, written into [out].
     *
     * A mono stream reports the same channel twice, so the meter is symmetric rather than
     * half-empty. Anything past the second channel is not looked at - the meter has two ends.
     */
    fun scanStereo(pcm: ByteArray, offset: Int, length: Int, channels: Int, out: IntArray) {
        out[0] = 0
        out[1] = 0
        if (channels <= 0) return
        val bytesPerFrame = channels * 2
        val frames = length / bytesPerFrame
        if (frames <= 0) return

        val right = if (channels >= 2) 1 else 0
        var l = 0
        var r = 0
        for (frame in 0 until frames) {
            val base = offset + frame * bytesPerFrame
            val a = abs16(pcm, base)
            if (a > l) l = a
            val b = if (right == 0) a else abs16(pcm, base + 2)
            if (b > r) r = b
        }
        out[0] = l
        out[1] = r
    }

    /**
     * Turn a peak amplitude into the fraction of the meter to fill, 0..1.
     *
     * 🔑 Decibels, not amplitude, because that is what hearing does: halving the amplitude is a
     *    small step down, and a linear bar would spend its whole length on the top few decibels.
     */
    fun fraction(peak: Int): Float {
        if (peak <= 0) return 0f
        val db = 20.0 * kotlin.math.log10(peak.toDouble() / FULL_SCALE)
        if (db <= FLOOR_DB) return 0f
        return ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    private fun abs16(buf: ByteArray, at: Int): Int {
        val v = (((buf[at + 1].toInt() and 0xFF) shl 8) or (buf[at].toInt() and 0xFF)).toShort().toInt()
        // 🔑 -32768 has no positive twin in 16 bits; folding it onto full scale is the one place
        //    this is not exact, and it is a single LSB at the loudest sample there can be.
        return if (v == Short.MIN_VALUE.toInt()) FULL_SCALE else if (v < 0) -v else v
    }
}
