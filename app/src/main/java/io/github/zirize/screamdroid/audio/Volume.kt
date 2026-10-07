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
 *
 * 🔑 **100 is unity, and the slider goes on past it to [MAX_PERCENT]** - the boost. It is there
 *    for somebody who turned the phone's media volume down to quiet another app and wants this
 *    stream back up (see [Boost]). The travel past 100 keeps the same decibels per step as the
 *    travel below it, so the fader does not change character at the mark.
 */
object Volume {

    const val RANGE_DB = 40.0
    const val BOOST_DB = 20.0
    const val DEFAULT_PERCENT = 80
    const val UNITY_PERCENT = 100

    /**
     * The slider snaps to this. At 40 dB over 100 that is 2 dB a notch - about the smallest change
     * that is plainly heard, so no notch is wasted and none jumps.
     */
    const val STEP_PERCENT = 5

    /** Top of the slider: unity plus [BOOST_DB] at the same dB per step. */
    const val MAX_PERCENT = UNITY_PERCENT + (BOOST_DB / RANGE_DB * UNITY_PERCENT).toInt()

    /**
     * Linear gain for [percent] of travel, 0..[MAX_PERCENT]. Zero really is zero, not -40 dB;
     * above [UNITY_PERCENT] the result is more than 1 and only [Boost] can deliver it.
     */
    fun gain(percent: Int): Float {
        val p = percent.coerceIn(0, MAX_PERCENT)
        if (p == 0) return 0f
        if (p == UNITY_PERCENT) return 1f
        val db = RANGE_DB * (p / 100.0) - RANGE_DB
        return Math.pow(10.0, db / 20.0).toFloat()
    }

    /** [percent] on the nearest notch. */
    fun snap(percent: Float): Int =
        (Math.round(percent / STEP_PERCENT) * STEP_PERCENT).coerceIn(0, MAX_PERCENT)

    /** Notches strictly between the ends, which is what a Compose `Slider` counts as `steps`. */
    const val SLIDER_STEPS = MAX_PERCENT / STEP_PERCENT - 1
}
