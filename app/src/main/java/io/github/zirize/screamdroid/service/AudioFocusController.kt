package io.github.zirize.screamdroid.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.zirize.screamdroid.audio.InterruptionPolicy

/**
 * Our claim on the speaker, and the two ways of losing it.
 *
 * 🔴 **Audio focus, not `READ_PHONE_STATE`.** The requirement is really "do not talk over
 *    somebody else", and focus is that requirement's own name: no permission, and it covers
 *    alarms, navigation and other media as well as calls (docs/architecture.md).
 *
 * 🔴 **Three signals, because two were not enough - measured, 2026-09-17.** A VoIP call (Google
 *    Talk) arriving on the test phone produced: focus lost **permanently**, `getMode()` still
 *    `MODE_NORMAL`, and the ring played by the system UI as an ordinary notification sound. Both
 *    of the planned signals said "another media app took the speaker", and the session duly shut
 *    itself down - the exact opposite of what this feature is for.
 *
 * 🔑 **The third signal is what is actually making a sound.** `getActivePlaybackConfigurations`
 *    needs no permission and, although it anonymises who is playing, it keeps the **usage** - so
 *    a ringtone and a voice call can be told apart from a music player. That is the discriminator
 *    the other two lacked.
 *
 * 🔴 **Focus is requested only after the foreground service is up.** From target SDK 35 the
 *    system grants focus only to the top app or one with a running foreground service, so a
 *    request made any earlier is simply refused.
 */
class AudioFocusController(
    context: Context,
    private val onChanged: () -> Unit,
) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var held: AudioFocusRequest? = null

    /**
     * 🔑 Written on the main thread by the listener and read by the service's single worker, which
     *    is why it is volatile and why the listener does nothing else: everything that touches the
     *    receiver happens on that one thread.
     */
    @Volatile
    var focus: InterruptionPolicy.Focus = InterruptionPolicy.Focus.HELD
        private set

    /**
     * True while telephony says there is a call, whoever is running it.
     *
     * 🔴 **`MODE_RINGTONE` counts.** A call takes the speaker while it is still ringing, and on
     *    the test phone that is the moment focus is lost - waiting for the call to be answered
     *    would mean deciding what happened after the evidence had gone.
     */
    val inCallMode: Boolean
        get() = when (audioManager?.mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }

    /** True once the request has been granted and not yet lost for good. */
    val holdsRequest: Boolean get() = held != null

    /**
     * True while something on this device is ringing or carrying a voice call.
     *
     * 🔑 **Usage, not identity.** Without `MODIFY_AUDIO_ROUTING` the system hands back anonymised
     *    configurations - no package, no session - but the usage survives, and the usage is the
     *    whole question here.
     * 🚫 Our own stream is `USAGE_MEDIA`, which is not in this list, so there is nothing to
     *    exclude and no need to identify ourselves among them.
     */
    val callAudioActive: Boolean
        get() = runCatching {
            audioManager?.activePlaybackConfigurations
                ?.any { it.audioAttributes.usage in CALL_USAGES } == true
        }.getOrDefault(false)

    /**
     * Every usage currently making a sound, for the log.
     *
     * 🔑 Worth printing at the moment focus is lost: it is the one line that says which of the
     *    three signals could have classified it, and it is how the ringtone case was found.
     */
    fun activeUsages(): String = runCatching {
        audioManager?.activePlaybackConfigurations
            ?.joinToString(",") { it.audioAttributes.usage.toString() }
            ?: "?"
    }.getOrDefault("?")

    /** Any of the three signals saying the speaker is wanted for a conversation. */
    val callInProgress: Boolean
        get() = inCallMode || callAudioActive

    /**
     * True while a conversation is actually running - **a ring alone does not count.**
     *
     * 🔑 **The difference decides how long to wait before coming back.** A ring might be about to
     *    become a call, and the handover between them is a gap in the signals that has to be
     *    ridden out. Once a conversation has really been happening, its end is the end: nothing
     *    else is coming, so the wait can be short. See InterruptionPolicy.CALL_TAIL_MS.
     */
    val conversationActive: Boolean
        get() = when (audioManager?.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
            else -> runCatching {
                audioManager?.activePlaybackConfigurations
                    ?.any { it.audioAttributes.usage in CONVERSATION_USAGES } == true
            }.getOrDefault(false)
        }

    /**
     * True while a notification, an alarm or a spoken prompt is sounding.
     *
     * 🔑 **Only consulted while sharing the speaker.** With focus held the system ducks the app
     *    itself and never says so, which is both smoother and free; without focus nobody is going
     *    to do it, so the same playback list that finds a ringtone finds these too.
     */
    val alertAudioActive: Boolean
        get() = runCatching {
            audioManager?.activePlaybackConfigurations
                ?.any { it.audioAttributes.usage in ALERT_USAGES } == true
        }.getOrDefault(false)

    /** What last took the speaker, for the log: which of the two signals noticed it. */
    @Volatile
    var lastSignal: String? = null
        private set

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        focus = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> InterruptionPolicy.Focus.HELD
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> InterruptionPolicy.Focus.LOST_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> InterruptionPolicy.Focus.LOST_TRANSIENT_DUCK
            AudioManager.AUDIOFOCUS_LOSS -> InterruptionPolicy.Focus.LOST
            else -> focus
        }
        if (focus != InterruptionPolicy.Focus.HELD) lastSignal = "focus"
        Log.i(TAG, "focus change $change -> $focus (mode in call: $inCallMode)")
        onChanged()
    }

    /**
     * Ask for the speaker.
     *
     * 🔑 [pauseWhenDucked] is how "quieten for notification sounds" is turned **off**. Left false,
     *    the system ducks us itself for a notification and never says so - which is the wanted
     *    behaviour and costs no code. Set true, the system ducks nothing and hands us the event
     *    instead, and we ignore it. There is no third setting that means "never duck me".
     */
    fun request(pauseWhenDucked: Boolean) {
        val manager = audioManager ?: return
        abandon()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                // 🔑 The same attributes the track is opened with. Focus is granted against what
                //    the audio claims to be, so a mismatch here would have the system ducking a
                //    stream that is not the one playing.
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(pauseWhenDucked)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        val result = manager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            held = request
            focus = InterruptionPolicy.Focus.HELD
        } else {
            // 🔑 Refused, and we play anyway: somebody switched this on deliberately, and going
            //    quiet with no explanation would be the worse answer. It is logged because a
            //    refusal here would explain a phone that never ducks for anything.
            Log.w(TAG, "focus refused ($result) - playing without it")
        }
    }

    fun abandon() {
        val manager = audioManager ?: return
        held?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        held = null
        focus = InterruptionPolicy.Focus.HELD
    }

    /**
     * Called when one of the secondary signals noticed a call that focus alone could not classify.
     *
     * 🔑 Recorded and shown in the diagnostics log, because "which signal actually fired" is the
     *    only way to know later whether all three still earn their place.
     */
    fun noteCallSignal() {
        if (lastSignal == null || lastSignal == "focus") {
            lastSignal = if (inCallMode) "mode" else "ringtone"
        }
    }

    fun clearSignal() {
        lastSignal = null
    }

    private companion object {
        const val TAG = "AudioFocus"

        /** 🔑 What a conversation sounds like to the system, whoever is running it. */
        val CALL_USAGES = setOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        )

        /** 🔑 The same list **without the ring** - see [conversationActive]. */
        val CONVERSATION_USAGES = setOf(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        )

        /**
         * What wants to be heard *over* other sound for a moment.
         *
         * 🚫 `USAGE_MEDIA` is deliberately absent: music from another app is the thing this mode
         *    exists to play alongside, not something to get out of the way of.
         * ℹ️ An alarm ducks rather than silences here. In the ordinary mode it takes focus and
         *    silences the stream; somebody who asked to hear several things at once gets the
         *    reading that matches what they asked for.
         */
        val ALERT_USAGES = setOf(
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ASSISTANT,
        )
    }
}
