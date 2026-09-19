package io.github.zirize.screamdroid.net

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.zirize.screamdroid.audio.AudioSink
import io.github.zirize.screamdroid.audio.BufferPolicy
import io.github.zirize.screamdroid.audio.DriftController
import io.github.zirize.screamdroid.audio.IdlePolicy
import io.github.zirize.screamdroid.audio.Peaks
import io.github.zirize.screamdroid.audio.Ramp
import io.github.zirize.screamdroid.audio.RingBuffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * One UDP stream: a socket, a ring, and two threads.
 *
 * ```
 * McastThread: recv (blocking) ┐
 * UcastThread: recv (blocking) ┴ SourceGate -> check header -> RingBuffer.write  [never blocks]
 * PlayThread : RingBuffer.read -> DriftController -> AudioSink.write             [blocks on device]
 * ```
 *
 * \U0001f511 **Two sockets, because a datagram does not say where it was sent.** It carries the address
 *    it came *from*; there is no asking a received packet whether it was addressed to the group.
 *    So the group is listened for on a socket bound to the group address, and this phone's own
 *    address on a second one, and which socket delivered it is the answer. See SourceGate for what
 *    happens when both are talking at once.
 *
 * 🔑 **Why they are separate.** With one thread doing both, the socket's receive queue fills up
 *    while the device is being written to. That queue is latency nobody chose and nobody can see,
 *    and when it overflows the kernel drops packets silently. Draining the socket always, and
 *    deciding here what to discard, is the difference between a loss we count and a loss we never
 *    hear about.
 *
 * 🚫 Neither thread allocates once running. The receive buffer, the packet and the playback chunk
 *    are made once; a header is only parsed when the five bytes differ from the previous packet's,
 *    which for a steady stream is never.
 */
class ScreamReceiver(
    private val unicastPort: Int,
    private val multicastPort: Int,
    private val sink: AudioSink,
    policy: BufferPolicy = BufferPolicy.BALANCED,
    private val unicastEnabled: Boolean = true,
    private val multicastGroup: String = ScreamProtocol.DEFAULT_MULTICAST_GROUP,
    val stats: ReceiverStats = ReceiverStats(),
) {

    /**
     * 🔑 **Changeable while running.** The playback thread notices on its next turn and rebuilds
     *    its controller, so choosing a different buffer preset does not mean stopping the stream.
     *    A port change is different - that is a new socket, and the service makes a new receiver.
     */
    @Volatile var policy: BufferPolicy = policy

    /**
     * Keep the audio path running through a quiet patch instead of letting it be paused.
     *
     * 🔴 **What this buys is the first moment of a short sound.** A paused device takes about a
     *    tenth of a second to start again, and whatever woke it waits unheard through that -
     *    which for a UI click is the whole click. Left running, the click plays the moment it
     *    arrives. See docs/architecture.md.
     *
     * 🔑 **The service turns it on whenever the phone is charging**, and on battery only if
     *    asked to: the cost is that silence goes on being fed to the device, and its wake-ups go
     *    on happening, for as long as a minute after the last sound. Changeable while running,
     *    like [policy].
     */
    @Volatile var keepAwake: Boolean = false

    /**
     * Playback gain, 0..1. Handed straight to the sink, which keeps it across a reopen.
     *
     * 🔑 Changing it is a property write, not a restart: the device applies gain itself, so the
     *    stream never stops to be turned down.
     */
    var gain: Float
        get() = sink.gain
        set(value) { sink.gain = value }

    enum class State { STOPPED, WAITING, PLAYING, PAUSED, ERROR }

    /** Format currently on the wire, with a counter so the player can tell it has changed. */
    private data class Wire(val header: ScreamHeader, val epoch: Int)

    @Volatile private var running = false
    @Volatile var state: State = State.STOPPED
        private set
    @Volatile var lastError: String? = null
        private set

    private val wire = AtomicReference<Wire?>(null)
    @Volatile private var playedEpoch = -1

    /** The buffer figures actually in force, once the device's floor has been applied. */
    @Volatile var effectiveTargetMs: Int = policy.targetMs
        private set
    @Volatile var effectiveMaxMs: Int = policy.maxMs
        private set

    /** How long the stream has been quiet, in the steps of IdlePolicy. */
    @Volatile var idleTier: IdlePolicy.Tier = IdlePolicy.Tier.ACTIVE
        private set

    /**
     * What the audio device's own buffer is set to now, and what it was handed at open.
     *
     * 🔑 Passed through rather than snapshotted here: the sink publishes both as volatile fields
     *    precisely so the service can poll them without touching the device from another thread.
     */
    val deviceBufferMs: Int get() = sink.deviceBufferMs
    val deviceBufferInitialMs: Int get() = sink.deviceBufferInitialMs

    /** The format last seen on the wire, or null before the first packet. For display only. */
    val currentFormat: ScreamHeader? get() = wire.get()?.header

    /** Who the packets are coming from, for the notification and for diagnosis. */
    @Volatile var senderAddress: String? = null
        private set

    /**
     * The port they are coming *from*, which is not the one we listen on.
     *
     * 🔑 Worth showing: two senders aimed at the same phone are told apart by it, and it is the
     *    quickest way to see that packets are arriving from a different process than expected.
     */
    @Volatile var senderPort: Int = 0
        private set

    /**
     * 🔑 **Shown as "mute", not "pause", and the difference is not wording.** Pause promises to
     *    carry on from where it stopped; this deliberately does not - it keeps reading and throws
     *    the audio away, so unmuting gives the live stream rather than a backlog. Calling that
     *    "pause" would be a promise the receiver does not keep.
     *
     * 🔴 **It means "keep draining the socket and throw it away", never "stop reading".**
     *    Stopping would let the kernel queue and the ring fill up while paused, and resuming would
     *    then play minutes-old audio. This is the same machinery a phone call will use.
     */
    @Volatile var paused: Boolean = false

    @Volatile private var multicastSocket: MulticastSocket? = null
    @Volatile private var unicastSocket: DatagramSocket? = null
    private var joinedGroup: InetSocketAddress? = null
    private var multicastThread: Thread? = null
    private var unicastThread: Thread? = null
    private var playThread: Thread? = null

    /** Decides which source owns the ring while both are arriving. */
    private val gate = SourceGate()

    /** Which sockets are up, so that one failing does not read as the receiver having failed. */
    @Volatile private var multicastUp = false
    @Volatile private var unicastUp = false

    /** Where each stream is bound, for the diagnostics screen. */
    @Volatile var unicastBoundTo: String? = null
        private set

    private enum class Source { MULTICAST, UNICAST }

    /**
     * 🔑 Sized from the ceiling, not from the target: it has to *hold* more than the ceiling before
     *    the controller decides to trim, or it would overflow at exactly the moment the policy
     *    says to allow a burst. It is sized for the widest format the app will open rather than
     *    for the one that turns up, so a format change never needs a new allocation - about 900 kB
     *    at twice a 300 ms ceiling, which for the format actually in use is seconds of headroom
     *    that DriftController keeps empty anyway.
     */
    private val ring = RingBuffer(bytesFor(policy.maxMs * 2, MAX_BYTES_PER_SECOND))

    fun start() {
        if (running) return
        running = true
        lastError = null
        state = State.WAITING
        // 🔑 The counters are **not** reset here. They belong to the listening session, not to
        //    this receiver: changing the port replaces the receiver, and an uptime and a fault
        //    count that restarted every time a setting was touched would be worth nothing. The
        //    service resets them when a session begins, and when somebody asks it to.

        // 🔑 The gate is cleared here rather than where it is made: a session that begins just
        //    after a group packet must not spend its first moments deaf to unicast.
        gate.reset()
        multicastThread = Thread({ multicastLoop() }, "screamdroid-rx-mcast").apply { start() }
        if (unicastEnabled) {
            unicastThread = Thread({ unicastLoop() }, "screamdroid-rx-ucast").apply { start() }
        }
        playThread = Thread(::playLoop, "screamdroid-play").apply { start() }
    }

    fun stop() {
        running = false
        paused = false
        // the only way out of a blocking receive
        multicastSocket?.close()
        unicastSocket?.close()
        multicastThread?.join(1000)
        unicastThread?.join(1000)
        playThread?.join(1000)
        multicastThread = null
        unicastThread = null
        playThread = null
        sink.release()
        wire.set(null)
        playedEpoch = -1
        senderAddress = null
        senderPort = 0
        unicastBoundTo = null
        gate.reset()
        idleTier = IdlePolicy.Tier.ACTIVE
        ring.clear()
        state = State.STOPPED
    }

    // ── receive ──────────────────────────────────────────────────────────────

    /**
     * The group. Always on while the receiver is running - there is no setting for it.
     *
     * 🔴 **Bound to the group address rather than to the wildcard, and that is what makes the
     *    pair work.** A wildcard-bound socket is handed unicast traffic as well, so with both
     *    halves on the same port the two sockets would each get every unicast packet and the ring
     *    would be written twice. Bound to the group, this one is handed group traffic only.
     */
    private fun multicastLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            // 🔴 **The join is rebuilt when the phone changes network, and that is the whole
            //    promise of this half.** A membership belongs to the interface it was made on, so
            //    one made at the office does not follow the phone home. Nothing throws when it
            //    goes stale - the socket stays open and silent, which is the failure this app is
            //    most often accused of - so the address is watched and the join redone.
            val address = LocalAddress.ipv4()
            try {
                val group = InetAddress.getByName(multicastGroup)
                val s = MulticastSocket(null).apply {
                    reuseAddress = true
                    // 🔑 Room for a burst while this thread is between receives. The ring is
                    //    still the buffer that matters; this only stops the kernel dropping
                    //    during a hiccup.
                    receiveBufferSize = SOCKET_BUFFER_BYTES
                    bind(InetSocketAddress(group, multicastPort))
                    soTimeout = SOCKET_TIMEOUT_MS
                }
                multicastSocket = s
                joinedGroup = InetSocketAddress(group, multicastPort)
                // 🔴 The interface is named rather than left to the system: a join on the wrong
                //    one leaves everything looking healthy while nothing arrives.
                s.joinGroup(joinedGroup, LocalAddress.ipv4Interface())
                multicastUp = true
                Log.i(TAG, "joined $multicastGroup:$multicastPort on ${address ?: "?"}")
                drain(s, Source.MULTICAST) { LocalAddress.ipv4() != address }
            } catch (e: Exception) {
                if (!running) break
                noteSourceFailure(Source.MULTICAST, e)
            } finally {
                multicastUp = false
                multicastSocket?.let { m ->
                    joinedGroup?.let { runCatching { m.leaveGroup(it, LocalAddress.ipv4Interface()) } }
                    runCatching { m.close() }
                }
                multicastSocket = null
                joinedGroup = null
                settleState()
            }
            if (running) Thread.sleep(REBIND_DELAY_MS)
        }
    }

    /**
     * Packets addressed to this phone itself. Only when it is asked for.
     *
     * 🔴 **Bound to this phone's own address, not to the wildcard**, for the reason above: on
     *    one port the wildcard would take the group's traffic too. The cost is that this socket is
     *    tied to an address that changes with the network - so it is reopened when it does, which
     *    is also how a phone that had no address at all when it started ever gets one.
     *
     * 🔑 **Losing this half to a changed address costs nothing that was not already lost.** The
     *    sender has this phone's old address written into its config; unicast is broken at the far
     *    end until somebody fixes that, whatever this socket is bound to. Multicast is what keeps
     *    playing in the meantime, which is the whole reason it is the half that is always on.
     */
    private fun unicastLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (running) {
            val address = LocalAddress.ipv4()
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    receiveBufferSize = SOCKET_BUFFER_BYTES
                    bind(
                        if (address != null) {
                            InetSocketAddress(InetAddress.getByName(address), unicastPort)
                        } else {
                            // 🔑 No address yet. The wildcard still hears unicast, and the group
                            //    socket is bound to the group, so nothing is heard twice - this is
                            //    a worse bind only in that it will be redone once an address turns up.
                            InetSocketAddress(unicastPort)
                        },
                    )
                    soTimeout = SOCKET_TIMEOUT_MS
                }
                unicastSocket = s
                unicastBoundTo = address ?: "*"
                unicastUp = true
                Log.i(TAG, "unicast on ${address ?: "*"}:$unicastPort")
                drain(s, Source.UNICAST) { LocalAddress.ipv4() != address }
            } catch (e: Exception) {
                if (!running) break
                noteSourceFailure(Source.UNICAST, e)
            } finally {
                unicastUp = false
                unicastSocket?.let { runCatching { it.close() } }
                unicastSocket = null
                unicastBoundTo = null
                settleState()
            }
            // 🔑 Either the address changed or the bind failed. Neither is worth a busy loop, and
            //    the group is carrying the sound in the meantime.
            if (running) Thread.sleep(REBIND_DELAY_MS)
        }
    }

    /**
     * The part that is the same for both: drain the socket, keep the format, fill the ring.
     *
     * 🔑 **Nothing here knows which socket it is reading** beyond handing [source] to the gate,
     *    which is the only place the difference means anything.
     *
     * @param reopen checked on every idle turn; true ends the drain so the caller can rebind.
     */
    private fun drain(s: DatagramSocket, source: Source, reopen: () -> Boolean = { false }) {
        val buf = ByteArray(ScreamProtocol.MAX_PACKET_BYTES)
        val packet = DatagramPacket(buf, buf.size)
        val lastHeaderBytes = ByteArray(ScreamProtocol.HEADER_BYTES)
        var lastSender: java.net.InetAddress? = null

        while (running) {
            packet.setData(buf, 0, buf.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                if (reopen()) return
                continue
            }
            val length = packet.length
            if (length <= ScreamProtocol.HEADER_BYTES) {
                stats.malformed.incrementAndGet()
                continue
            }

            val now = SystemClock.elapsedRealtime()
            // 🔴 **Before anything else looks at the packet.** A dropped packet must not move the
            //    format, the sender shown in the notification, or the clock the idle policy reads -
            //    otherwise the half that is being ignored still steers the half that is playing.
            if (source == Source.MULTICAST) {
                gate.onMulticast(now)
            } else if (!gate.allowUnicast(now)) {
                stats.discarded.incrementAndGet()
                continue
            }

            // 🔑 The fast path: compare the five bytes, do not parse them. A steady stream
            //    takes this branch for every packet and allocates nothing.
            var sameFormat = true
            for (i in 0 until ScreamProtocol.HEADER_BYTES) {
                if (buf[i] != lastHeaderBytes[i]) { sameFormat = false; break }
            }
            if (!sameFormat) {
                val header = ScreamHeader.parse(buf)
                if (header == null || !header.isSupported) {
                    stats.malformed.incrementAndGet()
                    continue
                }
                System.arraycopy(buf, 0, lastHeaderBytes, 0, ScreamProtocol.HEADER_BYTES)
                val previous = wire.get()
                wire.set(Wire(header, (previous?.epoch ?: -1) + 1))
                if (previous != null) stats.formatChanges.incrementAndGet()
                Log.i(TAG, "format ${header.sampleRate}Hz ${header.bitsPerSample}bit ${header.channels}ch")
            }

            stats.packets.incrementAndGet()
            stats.bytes.addAndGet(length.toLong())
            stats.lastPacketAt.set(now)

            // 🔑 Only when it changes: hostAddress allocates a String, and this runs for every
            //    packet. Comparing the InetAddress does not.
            val from = packet.address
            if (from != null && from != lastSender) {
                lastSender = from
                senderAddress = from.hostAddress
            }
            if (packet.port != senderPort) senderPort = packet.port

            if (paused) {
                stats.discarded.incrementAndGet()
                continue
            }

            // 🔑 Between a format change and the player reopening the device, the ring still
            //    holds bytes in the old format. Mixing the two would be played as noise, so these
            //    few milliseconds of packets are dropped on purpose and counted as such.
            if (wire.get()?.epoch != playedEpoch) {
                stats.discarded.incrementAndGet()
                continue
            }

            val payload = length - ScreamProtocol.HEADER_BYTES
            val written = ring.write(buf, ScreamProtocol.HEADER_BYTES, payload)
            if (written < payload) stats.overflowBytes.addAndGet((payload - written).toLong())
        }
    }

    /**
     * 🔴 **One socket failing is not the receiver failing.** The group can be joined on a network
     *    that then goes away while unicast keeps arriving, and a unicast bind can fail on a phone
     *    that has no address yet while the group plays perfectly. Only being left with nothing at
     *    all is an error worth showing.
     */
    private fun noteSourceFailure(source: Source, e: Exception) {
        if (!running) return
        Log.e(TAG, "$source receive failed", e)
        lastError = e.message ?: e.javaClass.simpleName
    }

    /**
     * 🔴 **A socket being down is not the receiver being down.** Both halves reopen themselves
     *    when the network moves, so there is a moment on every change where neither is up; taking
     *    that for a failure would stop a receiver that is about to be fine. Only the last thread
     *    leaving says the session is over, and even then only if it is not going to come back.
     */
    private fun settleState() {
        if (!running) return
        val multicastGone = multicastThread?.isAlive != true
        val unicastGone = !unicastEnabled || unicastThread?.isAlive != true
        if (multicastGone && unicastGone) {
            state = State.ERROR
            running = false
        }
    }

    // ── play ─────────────────────────────────────────────────────────────────

    /**
     * 🔑 **Awake mode changes one number: how long a dry buffer is fed before it is written
     *    off.** Padding is what keeps the device running through a quiet patch, so giving up after
     *    a buffer's worth - the right answer on battery - is what lets it be paused, and what the
     *    next sound then pays for.
     */
    private fun newDrift(targetMs: Int, maxMs: Int, awake: Boolean) = DriftController(
        targetMs = targetMs,
        maxMs = maxMs,
        padBudgetMs = if (awake) AWAKE_PAD_BUDGET_MS else maxMs,
    )

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var activePolicy = policy
        var activeFloorMs = 0
        var activeKeepAwake = keepAwake
        var drift = newDrift(activePolicy.targetMs, activePolicy.maxMs, activeKeepAwake)
        val chunk = ByteArray(ScreamProtocol.MAX_PAYLOAD_BYTES * 4)
        val pad = ByteArray(chunk.size)

        // 🔑 Made once, like everything else on this thread: the meter is read out of atomics by
        //    whoever is watching, so measuring it here costs one pass over a chunk and nothing else.
        val peaks = IntArray(2)

        // 🔴 A stream does not stop at a zero crossing. These three carry the last sample played
        //    across the gap so a drought is entered with a decay and left with a fade, instead of
        //    with a step in each direction - a step is heard as a click. See Ramp.
        val lastFrame = ShortArray(MAX_CHANNELS)
        var rampFrames = 0
        var fadeInNext = true
        var padding = false
        var pausedHere = false
        var stalled = false

        var format: ScreamHeader? = null

        while (running) {
            // 🔑 A preset change takes effect here. Rebuilding means refilling from the new
            //    threshold, which is a short gap - the honest cost of changing the cushion while
            //    audio is running, and cheaper than making the person stop and start the stream.
            // 🔴 **The device sets a floor the presets have to respect.** A blocking writer keeps
            //    the device's buffer near full, so latency cannot fall under it. Measured on the
            //    test phone before trimming: 81 ms, which is above the low-latency preset's whole
            //    ceiling - left alone, that preset trims on every pass, and each trim is a cut in
            //    the sound. So the floor raises the preset instead, keeping the band the preset
            //    asked for.
            // 🔑 **And the floor moves while the stream plays.** AudioTrackSink walks the device's
            //    buffer down towards what it will really take, so this is not a once-per-open
            //    reading. Each step narrows the band, the delay it gave up reappears in the ring,
            //    and the ordinary over-the-ceiling trim below hands it back - once, with the fade
            //    every other cut gets, instead of a little at a time.
            val floorMs = sink.deviceBufferMs
            val awake = keepAwake
            if (policy != activePolicy || floorMs != activeFloorMs || awake != activeKeepAwake) {
                // 🔴 A floor change under a running stream must not rebuffer. Nothing has been
                //    lost - the frames the device gave back are in the ring - so a gap of silence
                //    and a fade here would be a cost with nothing bought.
                val carryOn = policy == activePolicy && drift.isPlaying
                activePolicy = policy
                activeFloorMs = floorMs
                activeKeepAwake = awake
                val band = activePolicy.maxMs - activePolicy.targetMs
                val target = maxOf(activePolicy.targetMs, floorMs)
                drift = newDrift(target, target + band, awake)
                effectiveTargetMs = target
                effectiveMaxMs = target + band
                if (carryOn) drift.resume() else fadeInNext = true
            }

            // Going quiet is the normal case, not a fault: the sender simply stops. These steps
            // hand back what is expensive to hold, and one packet undoes all of them at once.
            val since = stats.lastPacketAt.get().let {
                if (it == 0L) 0L else SystemClock.elapsedRealtime() - it
            }
            val tier = IdlePolicy.tierFor(
                since,
                if (activeKeepAwake) IdlePolicy.AWAKE_STALE_AFTER_MS else IdlePolicy.STALE_AFTER_MS,
            )
            idleTier = tier
            if (tier >= IdlePolicy.Tier.IDLE) {
                if (sink.isOpen) {
                    sink.release()
                    format = null
                    playedEpoch = -1
                    lastFrame.fill(0)
                    drift.reset()
                    padding = false
                    fadeInNext = true
                }
                // 🔴 **And then wait here, rather than falling through to open it again.**
                //    Releasing clears the epoch, and the format on the wire has not changed - so
                //    the next pass reopened the device, the pass after that released it, and so on
                //    for as long as the stream stayed quiet. Measured 2026-09-18: twenty opens a
                //    second for minutes on end, 600 kB of log, and a wake-up for each one. One
                //    packet drops the tier back to ACTIVE and playback resumes from the top.
                state = State.WAITING
                Thread.sleep(IDLE_POLL_MS)
                continue
            } else if (tier == IdlePolicy.Tier.STALE && !stalled && sink.isOpen) {
                sink.pause()
                stalled = true
            }

            val current = wire.get()
            if (current == null) {
                state = State.WAITING
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            if (current.epoch != playedEpoch) {
                // 🔑 Order matters: open the device, empty the ring, *then* publish the epoch. The
                //    receiving thread starts writing the new format the moment it sees the epoch,
                //    so anything else would let old and new bytes meet in the ring.
                sink.open(current.header)
                ring.clear()
                drift.reset()
                format = current.header
                rampFrames = Ramp.frames(current.header.sampleRate)
                lastFrame.fill(0)
                fadeInNext = true
                padding = false
                stalled = false
                playedEpoch = current.epoch
            }
            val header = format ?: continue

            if (paused) {
                if (!pausedHere) {
                    // Fade out rather than cutting, then let the ring empty: the receiving thread
                    // is dropping everything anyway, so nothing accumulates while we are away.
                    val padBytes = header.bytesForMs(PAD_STEP_MS).coerceAtLeast(header.bytesPerFrame)
                    Ramp.fillDecay(pad, padBytes, header.channels, lastFrame, rampFrames)
                    sink.write(pad, 0, padBytes)
                    pausedHere = true
                    state = State.PAUSED
                }
                sink.starved()
                ring.clear()
                drift.reset()
                fadeInNext = true
                padding = false
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            if (pausedHere) {
                pausedHere = false
                lastFrame.fill(0)
            }

            // 🔴 **Latency is the ring plus what the device still holds.** Measuring only the
            //    ring was wrong in a way that was audible: the first writes after buffering are
            //    accepted instantly into the device's own 80 ms buffer, the ring empties, and the
            //    player - seeing an empty ring - writes silence into a stream the speaker has not
            //    even reached yet. Confirmed on the device 2026-09-17: five bursts produced
            //    seventeen "underruns" and a crackle at every start.
            val queuedMs = header.msForBytes(sink.queuedFrames() * header.bytesPerFrame)
            val ringMs = header.msForBytes(ring.available())
            val totalMs = ringMs + queuedMs
            stats.fillMs.set(totalMs)
            stats.deviceMs.set(queuedMs)
            val stepMs = header.msForBytes(chunk.size)
            val decision = drift.decide(totalMs, ringMs, stepMs)

            when (decision.action) {
                DriftController.Action.WAIT -> {
                    state = State.WAITING
                    // 🔴 **This is where writing actually stops**, and a device that is not being
                    //    written to underruns whatever size its buffer is. Padding covers only the
                    //    first 80 ms of a drought; the remaining second and a half is spent here.
                    //    Leaving it out was enough to send the buffer search back to the top over
                    //    and over - measured on the phone's speaker 2026-09-18, 42 ms crept back
                    //    to 158, and every step up is a refill the listener hears.
                    sink.starved()
                    padding = false
                    fadeInNext = true
                    Thread.sleep(IDLE_POLL_MS)
                }

                DriftController.Action.PAD -> {
                    state = State.PLAYING
                    // 🔑 Every pad, not just the first: the buffer search has to be told to keep
                    //    its hands off for as long as the drought lasts, not once at the start of
                    //    one. See AudioSink.starved.
                    sink.starved()
                    val padBytes = header.bytesForMs(PAD_STEP_MS).coerceAtLeast(header.bytesPerFrame)
                    if (!padding) {
                        // First pad of this drought: slide down from where playback stopped.
                        Ramp.fillDecay(pad, padBytes, header.channels, lastFrame, rampFrames)
                        padding = true
                        fadeInNext = true
                        stats.underruns.incrementAndGet()
                    } else if (pad[0] != ZERO || pad[1] != ZERO) {
                        // The decay is spent; from here the gap really is silence.
                        java.util.Arrays.fill(pad, 0, padBytes, ZERO)
                    }
                    // 🔑 Writing through the gap keeps the device's clock running. Stopping the
                    //    writes instead makes the track underrun, and recovering from that costs
                    //    more than the gap did.
                    sink.write(pad, 0, padBytes)
                }

                DriftController.Action.PLAY -> {
                    state = State.PLAYING
                    // 🔴 **Nothing is trimmed until the device has actually made a sound.**
                    //    Waking the audio path takes about a tenth of a second on this phone and
                    //    audio keeps arriving throughout, so the stream goes over the ceiling
                    //    before a single frame has been heard. That excess is the wake-up, not a
                    //    stream that got ahead - and a trim takes it off the *front* of the ring,
                    //    which is the beginning of the sound that did the waking.
                    //
                    // 🔴 **This used to discard the ring instead, and it cost whole sounds.**
                    //    The reasoning was that throwing away the start is silent because there is
                    //    nothing yet to be silent about. That holds for music starting up; it is
                    //    exactly wrong for a short one - a UI click is *all* beginning. Measured
                    //    2026-09-18 with 6 ms clicks three seconds apart: 82-114 ms discarded at
                    //    every click, which was the entire click. They were inaudible on the phone
                    //    and audible again with this rule in place.
                    //
                    // 🔑 What it costs instead: the delay the wake-up added stays until the
                    //    device is running, and the ordinary trim below then takes it back in one
                    //    cut - with the fade every other cut gets - a tenth of a second into the
                    //    sound. One covered cut is a better trade than losing the opening.
                    if (decision.dropMs > 0 && sink.hasStarted) {
                        val dropped = ring.skip(header.bytesForMs(decision.dropMs))
                        stats.droppedMs.addAndGet(header.msForBytes(dropped).toLong())
                        // 🔑 A trim is a cut in the middle of the waveform too, so it fades in on
                        //    the far side just like a drought does.
                        if (dropped > 0) fadeInNext = true
                    }
                    val n = ring.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        stalled = false
                        if (fadeInNext) {
                            Ramp.fadeIn(chunk, 0, n, header.channels, rampFrames)
                            fadeInNext = false
                        }
                        padding = false
                        Ramp.readLastFrame(chunk, 0, n, header.channels, lastFrame)
                        // 🔑 Measured after the ramp and scaled by the fader, so the meter shows
                        //    what the speaker is about to make - fades and volume included -
                        //    rather than what arrived on the wire. A meter that went on bouncing
                        //    with the volume at zero would be answering the wrong question.
                        Peaks.scanStereo(chunk, 0, n, header.channels, peaks)
                        val g = sink.gain
                        stats.reportPeaks((peaks[0] * g).toInt(), (peaks[1] * g).toInt())
                        // 🔴 **This write is the clock, and nothing else may be.** It blocks once
                        //    the device's buffer is full, which paces this thread against the DAC
                        //    exactly. An attempt to cap how much sits in the device instead - write
                        //    a chunk, sleep, check again - was tried on 2026-09-17 and failed: the
                        //    loop then runs at sleep granularity, which is a few per cent slower
                        //    than real time, so the ring grew without bound until it overflowed
                        //    (measured: 4.8 s of latency and 1.9 MB dropped in fifteen seconds).
                        sink.write(chunk, 0, n)
                    } else {
                        // The ring is empty but the device still has audio to play. Nothing to do.
                        Thread.sleep(IDLE_POLL_MS)
                    }
                }
            }
        }

        // 🔑 Stopping is a cut in the waveform like any other. One decay on the way out means the
        //    stop button does not click either - and it costs the five milliseconds that
        //    AudioTrackSink then waits for before closing the device.
        val closing = format
        if (closing != null && !padding) {
            val padBytes = closing.bytesForMs(PAD_STEP_MS).coerceAtLeast(closing.bytesPerFrame)
            Ramp.fillDecay(pad, padBytes, closing.channels, lastFrame, rampFrames)
            sink.write(pad, 0, padBytes)
        }
        state = if (lastError != null) State.ERROR else State.STOPPED
    }

    private companion object {
        const val TAG = "ScreamReceiver"
        const val SOCKET_BUFFER_BYTES = 256 * 1024
        const val SOCKET_TIMEOUT_MS = 500

        /**
         * 🔑 Long enough that a phone with no address is not rebinding in a tight loop, short
         *    enough that walking into Wi-Fi does not leave unicast down for noticeably longer than
         *    the network itself took to settle.
         */
        const val REBIND_DELAY_MS = 2_000L
        const val IDLE_POLL_MS = 5L

        /**
         * 🔑 In awake mode, pad right up to where IdlePolicy hands the device back anyway.
         *    Stopping earlier would pause the device for the remainder, which is the one thing
         *    awake mode exists to avoid.
         */
        val AWAKE_PAD_BUDGET_MS = IdlePolicy.IDLE_AFTER_MS.toInt()
        const val PAD_STEP_MS = 10
        const val ZERO: Byte = 0

        /** The widest channel count the format carries - the last-frame scratch is sized for it. */
        const val MAX_CHANNELS = 8

        /** 8 channels of 16-bit at 192kHz - the widest format this app will open. */
        const val MAX_BYTES_PER_SECOND = 192_000 * 2 * 8

        fun bytesFor(ms: Int, bytesPerSecond: Int) = (bytesPerSecond.toLong() * ms / 1000L).toInt()
    }
}
