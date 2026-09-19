package io.github.zirize.screamdroid.audio

/**
 * Short linear ramps over 16-bit PCM, so the receiver's own starts and stops are inaudible.
 *
 * 🔴 **Why this exists.** A stream does not stop at a zero crossing. When the buffer runs dry the
 *    last sample played can be anywhere in the waveform, and writing silence straight after it is
 *    a step - which is heard as a click. The same step happens in reverse when audio resumes.
 *    Wi-Fi power saving makes the buffer run dry (see docs/architecture.md), so this is an
 *    everyday case, not an edge one.
 *
 * 🔑 **5 ms, the same length the test signal generator uses for its own gating.** Long enough that
 *    the step becomes a slope well below hearing, short enough that no one perceives the onset as
 *    soft.
 *
 * 🔑 Plain arrays and no Android types: the play thread must not allocate, and the arithmetic is
 *    worth testing on the JVM rather than by ear.
 */
object Ramp {

    const val DEFAULT_MS = 5

    fun frames(sampleRate: Int, ms: Int = DEFAULT_MS): Int = sampleRate * ms / 1000

    /**
     * Fade the start of [pcm] up from silence, in place.
     *
     * Ramps over [rampFrames] frames or over everything there is, whichever is shorter - a short
     * read gets a short fade, which is still better than a step.
     */
    fun fadeIn(pcm: ByteArray, offset: Int, length: Int, channels: Int, rampFrames: Int) {
        if (rampFrames <= 0 || channels <= 0) return
        val bytesPerFrame = channels * 2
        val n = minOf(rampFrames, length / bytesPerFrame)
        for (frame in 0 until n) {
            // 🔑 +1 so the last ramp frame is at full scale and the join with the next frame is
            //    exact; starting at 0 means the first frame really is silent.
            val gainNum = frame + 1
            for (ch in 0 until channels) {
                val at = offset + frame * bytesPerFrame + ch * 2
                val v = sampleAt(pcm, at)
                putSample(pcm, at, (v.toInt() * gainNum / (n + 1)).toShort())
            }
        }
    }

    /**
     * Fill [dst] with a tail that decays from [last] to silence over [rampFrames] frames, then
     * stays silent.
     *
     * 🔑 Padding with a decay rather than with silence is what removes the click at the *start* of
     *    a drought: playback continues from the sample it stopped on instead of jumping to zero.
     */
    fun fillDecay(dst: ByteArray, length: Int, channels: Int, last: ShortArray, rampFrames: Int) {
        if (channels <= 0) return
        val bytesPerFrame = channels * 2
        val totalFrames = length / bytesPerFrame
        val ramp = minOf(rampFrames, totalFrames)
        for (frame in 0 until totalFrames) {
            val remaining = ramp - frame
            for (ch in 0 until channels) {
                val at = frame * bytesPerFrame + ch * 2
                val v = if (remaining > 0 && ramp > 0) {
                    (last[ch].toInt() * remaining / (ramp + 1)).toShort()
                } else {
                    0
                }
                putSample(dst, at, v)
            }
        }
    }

    /** Copy the last frame of [pcm] into [out], so a decay can start where playback stopped. */
    fun readLastFrame(pcm: ByteArray, offset: Int, length: Int, channels: Int, out: ShortArray) {
        if (channels <= 0) return
        val bytesPerFrame = channels * 2
        val frames = length / bytesPerFrame
        if (frames <= 0) {
            out.fill(0, 0, channels)
            return
        }
        val base = offset + (frames - 1) * bytesPerFrame
        for (ch in 0 until channels) out[ch] = sampleAt(pcm, base + ch * 2)
    }

    private fun sampleAt(buf: ByteArray, at: Int): Short =
        (((buf[at + 1].toInt() and 0xFF) shl 8) or (buf[at].toInt() and 0xFF)).toShort()

    private fun putSample(buf: ByteArray, at: Int, v: Short) {
        buf[at] = (v.toInt() and 0xFF).toByte()
        buf[at + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }
}
