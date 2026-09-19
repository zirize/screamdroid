package io.github.zirize.screamdroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The last few things that happened, for the diagnostics screen.
 *
 * 🔑 **Written from outside the audio threads, never inside them.** Those may not allocate, so
 *    they cannot append to a list; everything here is noticed by the service's publish loop from
 *    the counters moving, or raised by a deliberate act like muting. That is also why the list is
 *    short and the events are rare by construction.
 *
 * 🔑 **Kinds, not sentences.** The text is a string resource so the log is translated with the
 *    rest of the app, and so that this file stays free of anything to translate.
 */
object EventLog {

    enum class Kind {
        STARTED,
        STOPPED,
        REBOUND,
        FORMAT_CHANGED,
        DEVICE_BUFFER,
        UNDERRUN,
        DEVICE_RELEASED,
        RESUMED,

        /** The system froze or slept the process; see [Suspension]. */
        SUSPENDED,
        MUTED,
        UNMUTED,
        CALL_MUTED,
        FOCUS_MUTED,
        DUCKED,
        INTERRUPTION_OVER,
        STOPPED_BY_FOCUS,
        BLOCKED,
        COUNTERS_RESET,
        ERROR,
    }

    /** [atMillis] is wall-clock time, because it is shown as a clock reading. */
    data class Entry(val atMillis: Long, val kind: Kind, val detail: String? = null)

    /** Enough to cover the last few hours of a stream that is behaving, and bounded either way. */
    const val MAX = 40

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first - the order it is read in. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(kind: Kind, detail: String? = null) {
        val entry = Entry(System.currentTimeMillis(), kind, detail)
        _entries.value = (listOf(entry) + _entries.value).take(MAX)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
