package io.github.zirize.screamdroid.service

/**
 * Noticing, after the fact, that this process was not allowed to run.
 *
 * 🔑 **A frozen app cannot report being frozen - only having been frozen.** Nothing inside the
 *    process runs while the system holds it, so there is no moment to log at; the evidence is a
 *    loop that ticks twice a second finding that minutes went by between two passes. The clock
 *    used has to be [android.os.SystemClock.elapsedRealtime], which keeps counting through sleep
 *    - uptime alone would show the gap as never having happened.
 *
 * 🔑 **It cannot say which of the two it was**, a frozen process or a sleeping phone, and does not
 *    try: both mean the same thing to somebody who heard nothing, and the cure is the same one.
 *    See [PowerExemption].
 */
object Suspension {

    /**
     * 🔑 **Seconds, not milliseconds.** A loop this shape is routinely a few hundred milliseconds
     *    late - a busy phone, a garbage collection - and none of that is worth a line in a log
     *    forty entries long. Being gone for whole seconds is not something a running app does.
     */
    const val TOLERANCE_MS = 5_000L

    /**
     * How long the process was away, or 0 if it was simply running.
     *
     * [sinceLastPassMs] is measured across one pass of a loop that sleeps [pollMs].
     */
    fun gapMs(sinceLastPassMs: Long, pollMs: Long): Long =
        if (sinceLastPassMs >= pollMs + TOLERANCE_MS) sinceLastPassMs else 0L
}
