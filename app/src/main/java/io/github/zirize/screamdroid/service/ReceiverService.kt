package io.github.zirize.screamdroid.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.zirize.screamdroid.audio.AudioTrackSink
import io.github.zirize.screamdroid.audio.IdlePolicy
import io.github.zirize.screamdroid.audio.InterruptionPolicy
import io.github.zirize.screamdroid.audio.Peaks
import io.github.zirize.screamdroid.audio.Volume
import io.github.zirize.screamdroid.net.ReceiverStats
import io.github.zirize.screamdroid.net.ScreamReceiver
import io.github.zirize.screamdroid.settings.CallBehavior
import io.github.zirize.screamdroid.settings.Settings
import io.github.zirize.screamdroid.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What keeps the audio alive once nobody is looking at the app.
 *
 * 🔴 **Not an optimisation - the only way this works at all.** Without a foreground service the
 *    system stops the process soon after the screen goes off, and with the screen off Wi-Fi power
 *    saving starts dropping UDP even before that. The locks below are what stop the second thing;
 *    being a foreground service is what stops the first.
 *
 * 🔑 **The service owns the receiver, and the screen only watches.** The activity comes and goes -
 *    rotation, back button, the launcher - and audio must not. So state flows one way: the service
 *    publishes [snapshot], the notification and the screen both read it, and nothing owns the
 *    receiver but this.
 *
 * 🔑 **The session outlives the receiver.** Changing the port replaces the receiver underneath;
 *    the counters, the uptime and the buffer history belong to the session and carry across, so
 *    "up for two hours, no dropouts" does not reset because somebody looked at the settings.
 */
class ReceiverService : Service() {

    private var receiver: ScreamReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lowLatencyWifi = true
    private var appliedSettings = Settings()
    /**
     * 🔴 **One thread on purpose.** Two loops now touch [receiver] - the publish loop, which
     *    creates and drops it as the network comes and goes, and the settings collector, which
     *    replaces it when the socket has to change. On a multi-threaded dispatcher those two can
     *    run at the same instant and leave a receiver nobody stops, or stop one twice.
     *    `limitedParallelism(1)` makes them take turns instead; neither does anything long enough
     *    for that to cost anything.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val settingsRepository by lazy { SettingsRepository(this) }

    /** 🔑 One set of counters per session, handed to each receiver in turn. */
    private val stats = ReceiverStats()
    private var sessionStartedAt = 0L
    private var mutedSince = 0L
    private var mutedCause = PauseCause.NONE
    private var focusController: AudioFocusController? = null
    private var ducked = false
    private var lastInCallMode = false
    /** When focus was lost for good, 0 when it is not. See [applyInterruption]. */
    private var lostFocusAt = 0L
    /** 🔴 Kept from **before** a loss as well as after it - see InterruptionPolicy.resolveLoss. */
    private var lastCallAt = 0L
    private var lossWasCall = false

    /** True while the only network is a metered one. Written by [networkCallback]. */
    @Volatile private var meteredOnly = false
    private var connectivity: ConnectivityManager? = null

    /** True while a charger is attached. Written by [powerCallback]. See [applyKeepAwake]. */
    @Volatile private var charging = false
    private var watchingCharging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReceiverNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> setPaused(true, PauseCause.USER)
            ACTION_RESUME -> setPaused(false, PauseCause.NONE)
            ACTION_RESET_STATS -> resetCounters()
            ACTION_TOGGLE_SHARE -> toggleShare()
            else -> startReceiving()
        }
        // 🔴 Every path has to reach startForeground quickly, including the action intents that
        //    arrive while already running - the system gives a started service seconds, not
        //    leniency, and a missed call is an immediate crash.
        startForeground(ReceiverNotification.ID, ReceiverNotification.build(this, _snapshot.value))
        return START_NOT_STICKY
    }

    private fun startReceiving() {
        if (sessionStartedAt != 0L) return
        stats.reset()
        sessionStartedAt = SystemClock.elapsedRealtime()
        _history.value = emptyList()
        EventLog.add(EventLog.Kind.STARTED)
        watchNetwork()
        watchCharging()
        acquireLocks()
        _stopReason.value = StopReason.NONE
        scope.launch {
            appliedSettings = settingsRepository.settings.first()
            ensureReceiver()
            // 🔴 After the receiver, and therefore after startForeground: from target SDK 35 the
            //    system grants focus only to the top app or one with a running foreground
            //    service, so an earlier request is simply refused.
            startWatchingFocus()
            launch { publishLoop() }
            launch { followSettings() }
        }
    }

    /**
     * 🔑 **Whether a receiver should exist at all is a separate question from what it is
     *    configured as.** The session is running either way - the notification stays, the locks
     *    stay - but on mobile data with mobile data turned off there is nothing to listen with.
     *    Called on every pass of the publish loop, so a network that changes under the phone is
     *    picked up without a second observer.
     */
    private fun ensureReceiver() {
        val wanted = blockedBy() == Blocked.NONE
        if (wanted && receiver == null) {
            receiver = newReceiver(appliedSettings).also { it.start() }
        } else if (!wanted && receiver != null) {
            receiver?.stop()
            receiver = null
            EventLog.add(EventLog.Kind.BLOCKED)
        }
    }

    private fun blockedBy(): Blocked =
        if (meteredOnly && !appliedSettings.allowMobileData) Blocked.MOBILE_DATA else Blocked.NONE

    /**
     * 🔑 **Only a network we can positively see as metered blocks anything.** Capabilities are
     *    reported by the system and a VPN or an unusual transport can leave them incomplete; the
     *    test is therefore "cellular *and* neither Wi-Fi nor Ethernet", so anything unclear stays
     *    listening. Refusing to play on a doubt would be the worse failure by far.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            meteredOnly = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !local
        }

        override fun onLost(network: Network) {
            meteredOnly = false
        }
    }

    private fun watchNetwork() {
        if (connectivity != null) return
        connectivity = getSystemService(ConnectivityManager::class.java)?.also {
            runCatching { it.registerDefaultNetworkCallback(networkCallback) }
                .onFailure { e -> Log.w(TAG, "no network callback", e) }
        }
    }

    /**
     * 🔑 **A changed setting is applied, not queued behind a restart.** Which of the three ways it
     *    is applied depends on what the setting *is*:
     *    - volume and the Wi-Fi mode are properties, and take effect where they are;
     *    - the buffer preset is handed to the running receiver, which picks it up on its next turn;
     *    - the port is a **different socket**, so the receiver is replaced. Nothing above it
     *      notices: the service, the notification and the tile stay exactly as they were.
     */
    private suspend fun followSettings() {
        settingsRepository.settings.collect { settings ->
            if (settings == appliedSettings) return@collect
            val previous = appliedSettings
            // 🔑 A different socket means a different receiver; anything else is handed to the
            //    one already running. Nothing above notices either way - the service, the
            //    notification and the tile stay exactly as they were.
            val needsNewSocket = settings.unicastPort != previous.unicastPort ||
                settings.multicastPort != previous.multicastPort ||
                settings.unicastEnabled != previous.unicastEnabled ||
                settings.multicastGroup != previous.multicastGroup
            appliedSettings = settings

            if (needsNewSocket && receiver != null) {
                receiver?.stop()
                receiver = newReceiver(settings).also { it.start() }
                EventLog.add(EventLog.Kind.REBOUND, boundDescription(settings))
                Log.i(TAG, "rebound: ${boundDescription(settings)}")
            } else {
                receiver?.policy = settings.bufferPolicy
            }
            receiver?.gain = currentGain()
            applyKeepAwake()
            // 🔑 The Wi-Fi setting is part of this comparison, so flipping it flips the lock here
            //    without anything else having to notice.
            applyIdleTier(receiver?.idleTier ?: IdlePolicy.Tier.ACTIVE)
            ensureReceiver()
            // 🔑 Both of these change the shape of the focus request itself, so it is made again
            //    rather than reinterpreted - "ignore" means not asking for the speaker at all.
            if (settings.callBehavior != previous.callBehavior ||
                settings.duckOnNotification != previous.duckOnNotification ||
                settings.shareWithOthers != previous.shareWithOthers
            ) {
                startWatchingFocus()
            }
            applyInterruption()
        }
    }

    private fun newReceiver(settings: Settings): ScreamReceiver {
        // 🔴 **Held for every listening session now, not only when a mode was chosen.** The group
        //    is always joined, so the lock that stops the Wi-Fi hardware discarding group traffic
        //    is always needed. It is the standing cost of "wherever I am, it plays".
        applyMulticastLock(true)
        return ScreamReceiver(
            unicastPort = settings.unicastPort,
            multicastPort = settings.multicastPort,
            sink = AudioTrackSink().apply { gain = currentGain() },
            policy = settings.bufferPolicy,
            unicastEnabled = settings.unicastEnabled,
            multicastGroup = settings.multicastGroup,
            stats = stats,
        ).apply { keepAwake = wantsKeepAwake() }
    }

    /** What the log and the diagnostics screen call the current pair of sockets. */
    private fun boundDescription(settings: Settings): String =
        if (settings.unicastEnabled) {
            "group ${settings.multicastGroup}:${settings.multicastPort} + unicast :${settings.unicastPort}"
        } else {
            "group ${settings.multicastGroup}:${settings.multicastPort}"
        }

    /**
     * 🔴 **A charger settles it; the setting only has to answer for battery.** There is nothing
     *    to weigh while plugged in - holding the audio path open costs the listener nothing and
     *    buys every short sound its opening - so that case is not offered as a choice. The setting
     *    says whether to buy the same thing with battery.
     *
     * 🔑 Worked out here rather than in the receiver, so the receiver has nothing to know about
     *    batteries. Flipping either input takes effect on the play thread's next turn - see
     *    ScreamReceiver.keepAwake.
     *
     * ⚠️ The setting is worded the other way round - it is the *saving* that is switched on - so
     *    it is negated here. That is deliberate: see Settings.saveBatteryWhenSilent.
     */
    private fun wantsKeepAwake(): Boolean = charging || !appliedSettings.saveBatteryWhenSilent

    private fun applyKeepAwake() {
        val wanted = wantsKeepAwake()
        val r = receiver ?: return
        if (r.keepAwake == wanted) return
        r.keepAwake = wanted
        Log.i(TAG, "awake mode ${if (wanted) "on" else "off"} (charging=$charging)")
    }

    /**
     * 🔴 **Sticky state and an edge, not one or the other.** The broadcasts only say when the
     *    charger is plugged or pulled, so a service started while already charging would never
     *    hear one; [BatteryManager.isCharging] answers for the moment in between.
     */
    private val powerCallback = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            charging = intent?.action == Intent.ACTION_POWER_CONNECTED
            // 🔑 Through the scope, because [receiver] is only touched from that one thread.
            scope.launch { applyKeepAwake() }
        }
    }

    private fun watchCharging() {
        if (watchingCharging) return
        charging = getSystemService(BatteryManager::class.java)?.isCharging == true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(powerCallback, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(powerCallback, filter)
            }
            watchingCharging = true
        }
    }

    /**
     * 🔴 **Without this lock the phone drops multicast before the app ever sees it.** Wi-Fi
     *    hardware filters out group traffic by default precisely because it is expensive to
     *    receive, so it is held for as long as the receiver is running and released the moment
     *    it stops - which is the whole of what it costs.
     */
    private fun applyMulticastLock(want: Boolean) {
        if (want == (multicastLock?.isHeld == true)) return
        if (!want) {
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            multicastLock = null
            return
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock(MULTICAST_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun setPaused(paused: Boolean, cause: PauseCause) {
        if (paused == (mutedSince != 0L)) return
        val was = mutedCause
        receiver?.paused = paused
        mutedSince = if (paused) SystemClock.elapsedRealtime() else 0L
        mutedCause = cause
        EventLog.add(
            when {
                !paused && (was == PauseCause.CALL || was == PauseCause.FOCUS) ->
                    EventLog.Kind.INTERRUPTION_OVER
                !paused -> EventLog.Kind.UNMUTED
                cause == PauseCause.CALL -> EventLog.Kind.CALL_MUTED
                cause == PauseCause.FOCUS -> EventLog.Kind.FOCUS_MUTED
                else -> EventLog.Kind.MUTED
            },
            if (paused) focusController?.lastSignal else null,
        )
        _snapshot.value = _snapshot.value.copy(paused = paused, pauseCause = cause)
    }

    // ── giving way to other sound ────────────────────────────────────────────

    /**
     * 🔑 **Asking for the speaker is itself a setting.** "Ignore" is not a branch inside the
     *    handler - it is not asking at all, so nothing can take it away from us and the mode check
     *    is skipped too (see InterruptionPolicy).
     */
    private fun startWatchingFocus() {
        val controller = focusController
            ?: AudioFocusController(this) { scope.launch { applyInterruption() } }
                .also { focusController = it }
        // 🔑 **"Share with other apps" is the absence of a request, not a branch inside the
        //    handler.** Asking for focus is what takes the speaker away from YouTube and what
        //    makes YouTube take it away from us; not asking leaves both playing. Calls are
        //    unaffected, because the two signals that actually caught one (the audio mode and
        //    what is being played) have nothing to do with focus.
        if (appliedSettings.callBehavior == CallBehavior.IGNORE || appliedSettings.shareWithOthers) {
            controller.abandon()
        } else {
            controller.request(pauseWhenDucked = !appliedSettings.duckOnNotification)
        }
    }

    /**
     * 🔴 **Yielding means «keep draining the socket and throw it away», never «stop reading».**
     *    Simply stopping would let three minutes of a call pile up in the kernel queue and the
     *    ring, and hanging up would play audio from three minutes ago. [setPaused] is that
     *    machinery, and it is the same one the mute button uses - which is why a call needed no
     *    new playback path at all.
     */
    /**
     * Whether the call episode in progress got past ringing. Reset when the episode ends.
     *
     * 🔑 Held here rather than in the controller because it is a fact about *this interruption*,
     *    not about the device: the controller answers "what is true now", and this is "what has
     *    been true since the phone started ringing".
     */
    private var conversationSeen = false

    private fun applyInterruption() {
        val controller = focusController ?: return
        // 🔴 Any of the three signals is enough, and none of them is reliable alone - see
        //    AudioFocusController. A VoIP call on this phone shows up only in the third.
        val now = SystemClock.elapsedRealtime()
        if (controller.callInProgress) {
            controller.noteCallSignal()
            lastCallAt = now
            // 🔑 Remember that a *conversation* happened, not merely a ring - it is what decides
            //    how long to wait at the end. See InterruptionPolicy.CALL_TAIL_MS.
            if (controller.conversationActive) conversationSeen = true
        }
        // 🔑 A window, not an instant: the ring and the focus loss arrive in either order, and
        //    holding the mute a moment past the ring stops it flapping between ring and call.
        // 🔑 **Two lengths, because the two ends of a call are not the same question.** Before a
        //    conversation there may still be one coming - the answer is a gap in every signal, and
        //    that gap has to be ridden out. After one, nothing else is coming, so waiting the same
        //    three seconds is a pause with nothing behind it.
        val window = if (conversationSeen) {
            InterruptionPolicy.CALL_TAIL_MS
        } else {
            InterruptionPolicy.CALL_MEMORY_MS
        }
        val callRecently = lastCallAt != 0L && now - lastCallAt <= window
        // The episode is over; the next ring starts again from the cautious window.
        if (!callRecently) conversationSeen = false
        val focus = resolveFocus(controller, callRecently, now)
        val action = InterruptionPolicy.decide(
            behavior = appliedSettings.callBehavior,
            focus = focus,
            callRecently = callRecently,
            duckOnNotification = appliedSettings.duckOnNotification,
            // 🔑 Only while sharing: with focus held the system ducks for a notification itself,
            //    and doing it here too would duck twice.
            alertSounding = appliedSettings.shareWithOthers && controller.alertAudioActive,
        )
        setDucked(action == InterruptionPolicy.Action.DUCK)
        when (action) {
            InterruptionPolicy.Action.STOP -> {
                // 🔑 Another media app took the speaker for good. Muting instead would hold a
                //    wake lock and a low-latency Wi-Fi lock for audio nobody will hear.
                EventLog.add(EventLog.Kind.STOPPED_BY_FOCUS)
                _stopReason.value = StopReason.FOCUS_LOST
                stopSelf()
            }
            InterruptionPolicy.Action.MUTE ->
                setPaused(true, if (callRecently) PauseCause.CALL else PauseCause.FOCUS)
            InterruptionPolicy.Action.DUCK, InterruptionPolicy.Action.PLAY -> {
                // 🔴 Only what *this* put on is taken off: a mute the person pressed themselves
                //    must survive the end of a call.
                if (mutedCause == PauseCause.CALL || mutedCause == PauseCause.FOCUS) {
                    setPaused(false, PauseCause.NONE)
                }
                controller.clearSignal()
            }
        }
    }

    /**
     * Work out what a focus loss really was.
     *
     * 🔴 **Measured, not assumed (2026-09-17).** An incoming call on the test phone takes focus
     *    with permanent `AUDIOFOCUS_GAIN` - exactly as a music player does - so acting on that
     *    event directly ended the session every time the phone rang. A permanent loss is
     *    therefore treated as transient until the audio mode has had a moment to say whether a
     *    call is what took the speaker.
     *
     * 🔑 **Coming back needs a new request.** A permanent loss is not followed by a `GAIN`, so
     *    once the call is over the app has to ask for the speaker again; nothing else will give
     *    it back.
     */
    private fun resolveFocus(
        controller: AudioFocusController,
        callRecently: Boolean,
        now: Long,
    ): InterruptionPolicy.Focus {
        if (controller.focus != InterruptionPolicy.Focus.LOST) {
            lostFocusAt = 0L
            lossWasCall = false
            return controller.focus
        }
        if (lostFocusAt == 0L) {
            lostFocusAt = now
            lossWasCall = callRecently
            Log.i(
                TAG,
                "focus lost for good - call recently: $callRecently, " +
                    "mode in call: ${controller.inCallMode}, " +
                    "call audio: ${controller.callAudioActive}, usages: ${controller.activeUsages()}",
            )
        }
        // 🔑 Sticky for the episode: the ring stops long before the call does, and by the time it
        //    is over we still have to know that a call is what took the speaker - otherwise we
        //    would shut down at the very moment we should be coming back.
        lossWasCall = lossWasCall || callRecently
        if (lossWasCall && !callRecently) {
            // 🔑 A permanent loss is never followed by a GAIN, so the speaker has to be asked for
            //    again; nothing hands it back on its own.
            Log.i(TAG, "call over (${controller.lastSignal}) - asking for the speaker again")
            lostFocusAt = 0L
            lossWasCall = false
            startWatchingFocus()
            return controller.focus
        }
        return InterruptionPolicy.resolveLoss(
            callRecently = callRecently,
            settled = now - lostFocusAt >= InterruptionPolicy.LOSS_GRACE_MS,
        )
    }

    /**
     * 🔑 **Ducking is a gain change, and the framework's mixer ramps gain between its own
     *    buffers**, so unlike a gap in the stream this one does not need a fade written here.
     *    ⚠️ It is a listening check all the same - see docs/architecture.md.
     */
    private fun setDucked(on: Boolean) {
        if (on == ducked) return
        ducked = on
        receiver?.gain = currentGain()
        if (on) EventLog.add(EventLog.Kind.DUCKED)
    }

    private fun currentGain(): Float {
        val fader = Volume.gain(appliedSettings.volumePercent)
        return if (ducked) fader * InterruptionPolicy.DUCK_GAIN else fader
    }

    /**
     * 🔑 **Reached from the notification as well as the settings screen**, because it is a thing
     *    people change in the moment - the music starts, and the stream should get out of its way
     *    or not. It is written to storage rather than held here, so the shade and the screen
     *    cannot disagree and the choice survives a restart.
     */
    private fun toggleShare() {
        val wanted = !appliedSettings.shareWithOthers
        scope.launch { settingsRepository.setShareWithOthers(wanted) }
    }

    /** 🔑 What the diagnostics screen's "reset" does: the numbers, not the session. */
    private fun resetCounters() {
        stats.reset()
        sessionStartedAt = SystemClock.elapsedRealtime()
        _history.value = emptyList()
        EventLog.clear()
        EventLog.add(EventLog.Kind.COUNTERS_RESET)
    }

    /**
     * 🔑 The audio threads only add to atomic counters - they may not allocate or lock - so
     *    somebody has to come and read them. This is that reader, and it is also the only place
     *    the notification is rebuilt: it posts when the visible text changes, not ten times a
     *    second, because redrawing the shade costs more than reading the numbers does.
     *
     * 🔑 **It runs at [POLL_MS], which is the level meter's frame rate.** Everything else here is
     *    happy at a quarter of a second; a meter updated that slowly reads as a row of steps
     *    rather than as sound, and the loop itself is a few atomic reads.
     */
    private suspend fun publishLoop() {
        var lastBytes = 0L
        var lastPackets = 0L
        var lastPosted = ""
        var lastDropped = 0L
        var lastTier = IdlePolicy.Tier.ACTIVE
        var lastDeviceBuffer = 0
        var lastRate = 0
        var lastUnderruns = 0L
        var lastUnderrunLoggedAt = 0L
        var nextHistoryAt = 0L
        var interruptionTick = 0
        var lastPassAt = 0L
        while (true) {
            ensureReceiver()
            // 🔴 **Proof, after the fact, that the system stopped us.** This loop runs ten times
            //    a second; a gap of whole seconds means the process was frozen or the phone slept
            //    through it, and during that time the socket was still being filled and emptied
            //    into nothing by the kernel. It is the only trace such a stretch leaves, because
            //    nothing in this process ran to leave another - see Suspension and PowerExemption.
            val passAt = SystemClock.elapsedRealtime()
            // 🔴 **How long this pass actually took, not how long it asked to sleep.** `delay`
            //    is a floor: the work in between, a busy phone and a screen that is off all
            //    stretch it. Dividing by the nominal interval instead reads every rate too high
            //    - measured 1.81 Mbit/s for a stream that is 1.54 (2026-09-19, screen off).
            val sinceMs = if (lastPassAt == 0L) POLL_MS else (passAt - lastPassAt).coerceAtLeast(1L)
            val gap = if (lastPassAt == 0L) 0L else Suspension.gapMs(sinceMs, POLL_MS)
            lastPassAt = passAt
            if (gap > 0L) {
                val exempt = PowerExemption.isExempt(this)
                Log.w(TAG, "was not running for ${gap}ms (battery unrestricted=$exempt)")
                EventLog.add(EventLog.Kind.SUSPENDED, "${gap / 1000}s")
            }
            // 🔑 **The second opinion, polled rather than subscribed.** A mode listener exists
            //    only from Android 12, and one binder call twice a second costs less than two
            //    code paths do. Focus arrives by callback; this is only here for the dialers that
            //    never take focus.
            // 🔑 **Polled, not subscribed, and not on every pass.** These are two binder calls,
            //    and a mode listener exists only from Android 12 - half a second of latency on
            //    noticing a call that focus already told us about is not worth a second code path.
            // 🔑 Half a second while nothing is happening, every pass once something is: the
            //    window that matches a ring to a focus loss is only seconds wide, and missing a
            //    ring that lasted one second is how this went wrong the first time.
            // 🔴 "Busy" has to include *being* muted or ducked, not just the thing that caused
            //    it: coming back is decided in applyInterruption, so a loop that stopped calling
            //    it once the ring ended would leave the stream silent for good.
            val busy = appliedSettings.shareWithOthers ||
                lostFocusAt != 0L || lastInCallMode || ducked ||
                mutedCause == PauseCause.CALL || mutedCause == PauseCause.FOCUS ||
                (focusController?.focus ?: InterruptionPolicy.Focus.HELD) !=
                InterruptionPolicy.Focus.HELD
            if (busy || ++interruptionTick >= INTERRUPTION_POLL_EVERY) {
                interruptionTick = 0
                val inCall = focusController?.callInProgress ?: false
                if (inCall != lastInCallMode) {
                    Log.i(TAG, "call audio present: $inCall")
                    lastInCallMode = inCall
                }
                if (busy || inCall) applyInterruption()
            }
            val receiver = this.receiver
            val now = SystemClock.elapsedRealtime()
            val stats = this.stats
            val bytes = stats.bytes.get()
            val packets = stats.packets.get()
            val snapshot = ReceiverSnapshot(
                running = true,
                paused = mutedSince != 0L,
                pauseCause = mutedCause,
                blocked = blockedBy(),
                ducked = ducked,
                state = receiver?.state ?: ScreamReceiver.State.WAITING,
                idleTier = receiver?.idleTier ?: IdlePolicy.Tier.ACTIVE,
                settings = appliedSettings,
                effectiveTargetMs = receiver?.effectiveTargetMs ?: 0,
                effectiveMaxMs = receiver?.effectiveMaxMs ?: 0,
                sender = receiver?.senderAddress,
                senderPort = receiver?.senderPort ?: 0,
                format = receiver?.currentFormat,
                latencyMs = stats.fillMs.get(),
                deviceMs = stats.deviceMs.get(),
                deviceBufferMs = receiver?.deviceBufferMs ?: 0,
                deviceBufferInitialMs = receiver?.deviceBufferInitialMs ?: 0,
                packets = packets,
                // 🔑 Over the pass that was actually measured; a stretch where nothing ran at
                //    all (gap) would divide a real count by a huge window, so it reads as zero -
                //    which is the truth for a process that was frozen.
                packetsPerSecond = ((packets - lastPackets) * 1000L / sinceMs).toInt(),
                kbitPerSecond = ((bytes - lastBytes) * 8L / sinceMs).toInt(),
                underruns = stats.underruns.get(),
                droppedMs = stats.droppedMs.get(),
                overflowBytes = stats.overflowBytes.get(),
                malformed = stats.malformed.get(),
                discarded = stats.discarded.get(),
                formatChanges = stats.formatChanges.get(),
                uptimeMs = if (sessionStartedAt == 0L) 0L else now - sessionStartedAt,
                mutedForMs = if (mutedSince == 0L) 0L else now - mutedSince,
                // 🔑 Taken, not read: whoever polls clears the high-water mark, which is what
                //    makes the meter fall back to silence on its own.
                peakLeft = Peaks.fraction(stats.peakLeft.getAndSet(0)),
                peakRight = Peaks.fraction(stats.peakRight.getAndSet(0)),
                error = receiver?.lastError,
            )
            lastBytes = bytes
            lastPackets = packets
            _snapshot.value = snapshot
            applyIdleTier(snapshot.idleTier)

            // 🔑 **Diagnostics belong here, not in the audio threads.** Those may not log, so the
            //    only way to know *when* the receiver trimmed latency or changed idle step is to
            //    notice the counters moving from outside.
            if (snapshot.droppedMs < lastDropped) lastDropped = 0L   // counters were reset
            if (snapshot.droppedMs != lastDropped) {
                Log.i(TAG, "trim +${snapshot.droppedMs - lastDropped}ms " +
                    "(latency ${snapshot.latencyMs}ms, device ${snapshot.deviceMs}ms)")
                lastDropped = snapshot.droppedMs
            }
            // 🔑 The audio thread walks the device's buffer down and may not log, so the only
            //    record of where it got to is the figure moving under a poll out here.
            if (snapshot.deviceBufferMs != lastDeviceBuffer) {
                Log.i(TAG, "device buffer ${snapshot.deviceBufferMs}ms " +
                    "(device offered ${snapshot.deviceBufferInitialMs}ms)")
                // 🔑 On the screen as well as in the log. Somebody hearing a click wants to know
                //    whether the buffer just moved, and which way - without that the two cannot be
                //    told apart from a gap on the wire, which looks and sounds the same.
                if (lastDeviceBuffer > 0 && snapshot.deviceBufferMs > 0) {
                    EventLog.add(
                        EventLog.Kind.DEVICE_BUFFER,
                        "$lastDeviceBuffer→${snapshot.deviceBufferMs} ms",
                    )
                }
                lastDeviceBuffer = snapshot.deviceBufferMs
            }
            if (snapshot.idleTier != lastTier) {
                Log.i(TAG, "idle $lastTier -> ${snapshot.idleTier}")
                if (snapshot.idleTier == IdlePolicy.Tier.IDLE) {
                    EventLog.add(EventLog.Kind.DEVICE_RELEASED)
                } else if (snapshot.idleTier == IdlePolicy.Tier.ACTIVE &&
                    lastTier >= IdlePolicy.Tier.IDLE
                ) {
                    EventLog.add(EventLog.Kind.RESUMED)
                }
                lastTier = snapshot.idleTier
            }
            val rate = snapshot.format?.sampleRate ?: 0
            if (rate != 0 && rate != lastRate) {
                if (lastRate != 0) EventLog.add(EventLog.Kind.FORMAT_CHANGED, "$lastRate→$rate")
                lastRate = rate
            }
            // 🔑 Rate-limited on purpose: a bad wireless minute can produce a dropout every second,
            //    and forty identical lines would push everything else out of a list of forty.
            if (snapshot.underruns > lastUnderruns) {
                if (now - lastUnderrunLoggedAt > UNDERRUN_LOG_GAP_MS) {
                    EventLog.add(EventLog.Kind.UNDERRUN)
                    lastUnderrunLoggedAt = now
                }
                lastUnderruns = snapshot.underruns
            } else if (snapshot.underruns < lastUnderruns) {
                lastUnderruns = snapshot.underruns
            }

            // 🔑 The sparkline is a record, so it is kept where the session is - the screen that
            //    draws it may not have existed when the interesting minute happened.
            if (now >= nextHistoryAt) {
                nextHistoryAt = now + HISTORY_STEP_MS
                _history.value = (_history.value + snapshot.latencyMs).takeLast(HISTORY_POINTS)
            }

            val signature = "${snapshot.state}|${snapshot.paused}|${snapshot.blocked}|" +
                "${snapshot.sender}|${snapshot.format}|${snapshot.latencyMs / 10}|${snapshot.error}|" +
                "${snapshot.settings.shareWithOthers}"
            if (signature != lastPosted) {
                lastPosted = signature
                runCatching {
                    startForeground(ReceiverNotification.ID, ReceiverNotification.build(this, snapshot))
                }.onFailure { Log.w(TAG, "could not update the notification", it) }
            }
            delay(POLL_MS)
        }
    }

    /**
     * 🔑 **Low-latency Wi-Fi is rented, not owned.** The mode exists to stop the radio batching
     *    packets, and it spends battery for exactly that reason - Android documents it as
     *    something to hold while it is being used. After ten minutes with nothing arriving it is
     *    no longer being used, so it is traded down; the first packet back trades it up again.
     *    Turning the setting off keeps it traded down for good.
     */
    private fun applyIdleTier(tier: IdlePolicy.Tier) {
        val wantLowLatency = tier != IdlePolicy.Tier.DEEP_IDLE && appliedSettings.lowLatencyWifi
        if (wantLowLatency == lowLatencyWifi) return
        lowLatencyWifi = wantLowLatency
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = createWifiLock(wantLowLatency)?.apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "wifi lock -> ${if (wantLowLatency) "low latency" else "high perf"}")
    }

    private fun createWifiLock(lowLatency: Boolean): WifiManager.WifiLock? {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val mode = if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return wifi.createWifiLock(mode, WIFI_TAG)
    }

    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }

        lowLatencyWifi = true
        wifiLock = createWifiLock(lowLatency = true)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    override fun onDestroy() {
        scope.cancel()
        focusController?.abandon()
        focusController = null
        receiver?.stop()
        receiver = null
        connectivity?.let { cm -> runCatching { cm.unregisterNetworkCallback(networkCallback) } }
        connectivity = null
        if (watchingCharging) {
            runCatching { unregisterReceiver(powerCallback) }
            watchingCharging = false
        }
        charging = false
        releaseLocks()
        applyMulticastLock(false)
        sessionStartedAt = 0L
        mutedSince = 0L
        mutedCause = PauseCause.NONE
        ducked = false
        lastInCallMode = false
        lostFocusAt = 0L
        lastCallAt = 0L
        lossWasCall = false
        EventLog.add(EventLog.Kind.STOPPED)
        _snapshot.value = ReceiverSnapshot()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val WAKE_TAG = "screamdroid:receiver"
        private const val WIFI_TAG = "screamdroid:receiver"
        private const val MULTICAST_TAG = "screamdroid:multicast"

        /** Also the level meter's frame interval - see publishLoop. */
        private const val POLL_MS = 100L

        private const val UNDERRUN_LOG_GAP_MS = 10_000L

        /** Every fifth pass of a 100 ms loop: half a second. */
        private const val INTERRUPTION_POLL_EVERY = 5

        /** 1.5 s a point, 120 points: the "last three minutes" the diagnostics screen promises. */
        private const val HISTORY_STEP_MS = 1_500L
        const val HISTORY_POINTS = 120

        /** 120 × 1.5 s. Written out so the screen and the sampler cannot drift apart silently. */
        const val HISTORY_SPAN_MINUTES = 3

        const val ACTION_START = "io.github.zirize.screamdroid.START"
        const val ACTION_STOP = "io.github.zirize.screamdroid.STOP"
        const val ACTION_PAUSE = "io.github.zirize.screamdroid.PAUSE"
        const val ACTION_RESUME = "io.github.zirize.screamdroid.RESUME"
        const val ACTION_RESET_STATS = "io.github.zirize.screamdroid.RESET_STATS"
        const val ACTION_TOGGLE_SHARE = "io.github.zirize.screamdroid.TOGGLE_SHARE"

        private val _snapshot = MutableStateFlow(ReceiverSnapshot())

        /** What the receiver is doing, for the screen, the notification and the tile alike. */
        val snapshot: StateFlow<ReceiverSnapshot> = _snapshot.asStateFlow()

        private val _stopReason = MutableStateFlow(StopReason.NONE)

        /** Why the last session ended, so a receiver that switched itself off can say why. */
        val stopReason: StateFlow<StopReason> = _stopReason.asStateFlow()

        private val _history = MutableStateFlow<List<Int>>(emptyList())

        /** Latency, one point every 1.5 s, for the diagnostics sparkline. */
        val history: StateFlow<List<Int>> = _history.asStateFlow()

        /** For the screen: the same repository the service reads, so both see one source. */
        fun settingsRepository(context: Context) = SettingsRepository(context)

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun setPaused(context: Context, paused: Boolean) =
            send(context, if (paused) ACTION_PAUSE else ACTION_RESUME)

        /** 🚫 Does nothing while the receiver is off - there would be nothing to reset. */
        fun resetStats(context: Context) {
            if (_snapshot.value.running) send(context, ACTION_RESET_STATS)
        }

        fun toggle(context: Context) {
            if (_snapshot.value.running) stop(context) else start(context)
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, ReceiverService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
