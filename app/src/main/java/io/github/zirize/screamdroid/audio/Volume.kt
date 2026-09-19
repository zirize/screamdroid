package io.github.zirize.screamdroid.audio

/**
 * What the volume slider means.
 *
 * 🔑 **A fader is decibels, not a multiplier.** `AudioTrack.setVolume` takes linear gain, so a
 *    slider wired straight to it spends its top half on changes nobody can hear and drops to
 *    inaudible in the last few per cent. Mapping the slider to a fixed decibel range instead
 *    makes every part of its travel do the same amount of work.
 *
 * 🔑 [RANGE_DB] is the span from silence to full. 40 dB is the usual choice for a short fader:
 *    wide enough to turn the stream down to background level, narrow enough that the bottom of
 *    the slider is still audible rather than a second mute button.
 */
object Volume {

    const val RANGE_DB = 40.0
    const val DEFAULT_PERCENT = 80

    /** Linear gain for [percent] of travel, 0..100. Zero really is zero, not -40 dB. */
    fun gain(percent: Int): Float {
        val p = percent.coerceIn(0, 100)
        if (p == 0) return 0f
        if (p >= 100) return 1f
        val db = RANGE_DB * (p / 100.0) - RANGE_DB
        return Math.pow(10.0, db / 20.0).toFloat()
    }
}
