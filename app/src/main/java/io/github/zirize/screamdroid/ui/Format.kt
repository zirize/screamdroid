package io.github.zirize.screamdroid.ui

import java.util.Locale

/**
 * Turning numbers into something readable at a glance.
 *
 * 🔑 **Fixed widths on purpose.** These figures update several times a second, and a value whose
 *    text shifts sideways as it changes is genuinely harder to read than one that does not - so
 *    the clock is always eight characters and the rate always has two decimals.
 * 🔑 [Locale.ROOT] because these are measurements, not prose: a thousands separator that changes
 *    with the phone's language would make the same reading look different on two phones.
 */
object Format {

    /** `02:41:08`, counting past 24 hours rather than wrapping - it is an uptime, not a time. */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    /** Megabits per second, from the kilobit figure the service publishes. */
    fun mbps(kbitPerSecond: Int): String =
        String.format(Locale.ROOT, "%.2f", kbitPerSecond / 1000.0)

    /** Thousands separated, for counters that reach six figures in an evening. */
    fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

    /** `14:02:11`, the wall clock, for the diagnostics log. */
    fun clock(epochMillis: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = epochMillis
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND),
        )
    }
}
