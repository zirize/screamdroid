# Architecture

The phone receives **one** stream. Mixing happens on the PC, in the PipeWire sender, so there are
no slots, no four `AudioTrack`s and no four copies of the drift problem. Everything below follows
from that and from `docs/protocol.md`.

```
┌─ UI (Compose) ───────────────────────────────────────────┐
│  Main · Call · Settings · Diagnostics                    │
│        ▲ StateFlow                     │ Intent          │
└────────┼───────────────────────────────┼─────────────────┘
         │                               ▼
┌─ ReceiverService (foreground, mediaPlayback) ────────────┐
│    RxThread ─push→ [RingBuffer] ─pull→ PlayThread        │
│       │ ScreamHeader.parse()           │ AudioSink       │
│       └ stats (atomic counters)        └ DriftController │
│  AudioFocusController · WakeLock · WifiLock · Notification│
└──────────────────────────────────────────────────────────┘
         ▲
   SettingsRepository (DataStore)
```

## Two threads, not one

```
RxThread   : recv (blocking) → check header → RingBuffer.write(payload)   [never blocks]
PlayThread : RingBuffer.read → DriftController → AudioTrack.write (blocking)
```

🔑 **Why they are split.** With one thread doing `recv → write`, the socket's receive queue fills
while `AudioTrack.write` blocks. That queue *is* latency, and when it overflows the kernel drops
silently. ⇒ **Always drain the socket. Decide what to discard ourselves.** Both prior receivers
(`docs/prior-art.md`) use one thread; this is the main place the design departs from them.

- `RxThread` runs at `THREAD_PRIORITY_URGENT_AUDIO`, `PlayThread` at `THREAD_PRIORITY_AUDIO`.
- 🚫 No allocation, locks or logging inside either. Statistics are atomic counters the UI polls.

## Latency is the ring *plus* the device

🔴 **Measured on a phone, 2026-09-17, after getting it wrong.** `AudioTrack` has a buffer of its
own - on the test device 80 ms, and its minimum is not negotiable. Audio sitting in it is latency
exactly as much as audio sitting in the ring, but it is latency the ring cannot see. A receiver
that watches only its own buffer therefore:

- calls the stream starved while the speaker still has 80 ms to play, and
- "helpfully" writes silence into the middle of it.

That is audible, and it was: five three-second bursts produced **seventeen** reported underruns and
a crackle at the start of every one. Counting the device's queue as well brought the same test to
**five** - one per burst, which is how many times the sender actually stopped.

```
latency = ring fill + (frames written − AudioTrack.getPlaybackHeadPosition())
```

🔴 **The blocking write is the clock, and nothing else may be.** Writing a chunk, sleeping, and
checking again - to keep the device's share of the latency small enough to trim - was tried and
failed: the loop then runs at sleep granularity, a few per cent slower than real time, and the
ring grows without bound. Measured: 4.8 seconds of latency and 1.9 MB dropped in fifteen seconds.
`AudioTrack.write` blocks against the DAC, which is the only clock that is not approximate.

ℹ️ A consequence worth knowing before tuning: with most of the latency inside the device
(measured 77 ms of 83 ms), the part a buffer preset can actually move is the remainder.

### The device's buffer is set small at the open

🔑 **`getMinBufferSize` is a floor on *creating* a track, not on running it.** The size can be set
on a live track, and the device names its own minimum if you ask for zero frames - there is no
public API for the burst size, and a guessed one would be wrong on exactly the devices that matter.
What the device hands out is far more than a blocking writer needs, and it differs by route:
measured on the test phone, **160 ms over the speaker and 81 ms over Bluetooth**.

🔑 **The size is chosen once, at the open, and is one of the player's writes plus a margin.** That
is all the device's own buffer has to be - it exists so a write does not have to wait in the middle
of itself. Every frame beyond that is delay the ring could be holding instead, where
`DriftController` can trim it and the buffer presets mean something.

🔴 **An earlier version searched downwards while the stream played, and the search was the whole
cost.** Halving, proving, halving again put a trim into the sound at every step: measured
2026-09-18, four trims in the first sixteen seconds, each of them heard - while the size it
arrived at played three minutes of music without a single adjustment. So it does not walk. At an
open the device is empty and nothing has been heard yet, which is what makes a small size free
there and expensive anywhere else.

🔴 **Shrinking was free; growing is not.** Every frame added has to be supplied by the ring, and
the sender only ever sends in real time - so the stream must be quiet for exactly as long as the
buffer grew, and splitting the growth into smaller steps does not change that total. What changes
it is *when*: at a silence there is nothing to interrupt. This sender falls quiet after every alert
sound, so growth waits for the next one and takes a quarter at a time. A stream that breaks up
without ever falling quiet is grown immediately instead - that one has to be paid for.

🔴 **Past a ceiling the idea is abandoned rather than crept past.** If the buffer would have to
grow beyond half of what the device offered, the device's own size goes back once and is never
touched again: the worst case is exactly what the app did before any of this existed, reached in
one step.

🔴 **Three different things are called an underrun, and only one of them is about the buffer.**

| what happened | what it means | how it is told apart |
|---|---|---|
| the device woke up | warm-up; it reports one every time | ignored for the first half second of audio after any start or resize |
| the ring ran dry, or the player is waiting | a gap on the wire; the size is irrelevant | `AudioSink.starved()`, which the player calls on every pad **and every wait** |
| the buffer really is too small | it needs to grow | anything left, and only below the size the device chose itself |

Reading any of the first two as a verdict is not a theoretical risk. Measured: thirty restarts of a
gated signal undid a whole descent; a single underrun in the first seconds pinned a search so that
nothing was ever tried; and covering only the padding - not the waiting - let 42 ms creep back to
158. Each wrong verdict is heard, because each one grows the buffer.

🔑 **What a size is proved by is frames played, not seconds elapsed.** Silence proves nothing about
whether a buffer is deep enough, and this sender stops between alert sounds.

Measured end to end (speaker, unicast, default preset): device buffer **160 ms → 29 ms**, total
latency **62 ms**, one trim at the start and none in the following two minutes of music.

## Nothing starts or stops on a step

A stream does not stop at a zero crossing. When the buffer runs dry the last sample played can be
anywhere in the waveform, and writing silence straight after it is a step - heard as a click. The
same step happens in reverse when audio resumes, and again either side of a trim.

So every transition the receiver *makes itself* is a 5 ms ramp (`Ramp`):

| transition | what happens |
|---|---|
| buffering → playing | fade in |
| buffer runs dry | decay from the last sample played, then silence |
| audio returns | fade in |
| latency trimmed | fade in on the far side of the cut |
| format change | device reopened, fade in |
| stopped | one decay written, and the device drains it rather than flushing |

🚫 **What is deliberately not fixed**: a sender that is killed mid-waveform still clicks at the
cut. Nothing can see that coming - the receiver learns the stream stopped only after it has - so
any fade would be a guess. It does not arise in use either: silence suppression stops a real
sender only after half a second of actual silence, so the cut is at zero anyway.

## Buffering and drift

| knob | default | meaning |
|---|---|---|
| `targetLatencyMs` | 60 | fill level playback aims to hold |
| `maxLatencyMs` | 150 | above this, drop the oldest packet |
| `startThresholdMs` | = target | fill this much before starting or after an underrun |

Presets: low latency 30/80 · default 60/150 · stable 120/300.

The sender's clock and the phone's DAC clock are never the same, so over minutes the buffer
drifts full or empty. In order: drop a packet when over `maxLatency`, pad with silence when
empty, and optionally trim playback rate by ±0.2 % with `AudioTrack.setPlaybackParams` — the
first two are audible, the third is not but costs a resampler.

🔴 **A wireless power-saving stall must not be mistaken for a disconnect.** On a laptop over
Wi-Fi the same sender has produced minutes of choppiness that cleared on its own; the receiver
was never dead, only late. A phone's power saving is more aggressive than a laptop's. Ride it out
with the buffer; do not restart the socket.

## Idle

Silence suppression means the packets simply stop (`docs/protocol.md`), and **"silent" cannot be
told from "not arriving"**. A gap is therefore the normal case, not a fault - which means the
receiver may neither treat one as a disconnection nor hold an open audio device, a wake lock and
a low-latency Wi-Fi lock forever on the chance that audio returns.

| quiet for | what is given up | what it costs to undo |
|---|---|---|
| 1.5 s | `AudioTrack.pause()` | nothing — the next write resumes |
| 60 s | `AudioTrack.release()`, notification says *waiting* | one reopen |
| 10 min | wifi lock drops from `LOW_LATENCY` to `HIGH_PERF` | a lock swap |

🔑 **The last step gives up tuning, not power saving.** `HIGH_PERF` already keeps Wi-Fi power
save off, so the radio is not back to batching packets; what ten quiet minutes hand back is the
firmware's low-latency mode, which Android documents as something to hold for bounded periods.
By then the device has been closed for nine of those ten minutes anyway, so the first packet back
pays one wake-up with the lock swap folded into it - which is why a charger does not exempt this
step the way it exempts the pause (see **Awake mode**).

🔴 **One arriving packet undoes all of it at once.** Unwinding a step at a time would take three
gaps to come back from a long quiet spell, and the sender gives no warning that audio is about to
resume. `IdlePolicy` is a pure function of "how long since the last packet" for exactly that
reason - the ladder can be played through in a unit test rather than waited for on a phone.

Because the two causes are indistinguishable, anything shown to the user names both: the PC is
quiet, **or** the sender is not pointed at this device.

## What a setting changes, and when

Nothing waits for a restart, but not everything can be applied the same way:

| changed | how it takes effect |
|---|---|
| buffer preset | handed to the running receiver; its playback thread rebuilds the controller on its next turn |
| port, addressing mode | a **different socket**, so the receiver is replaced underneath. The service, the notification and the tile never notice |

🔴 **The device sets a floor the presets have to respect.** A blocking writer keeps the device's
buffer near full, so latency cannot fall under it - measured on the test phone before trimming:
**81 ms**, which is above the low-latency preset's entire ceiling of 80 ms. Left alone, that
preset sits over its own ceiling and trims on every pass, and every trim is a cut in the sound.
So the floor raises the preset instead, keeping the band it asked for, and the screen says so
rather than leaving it to be discovered.

🔑 **And the floor can move while the stream plays**, because the device's buffer may grow. A new
band under a running stream must not rebuffer - nothing was lost, so there is nothing to refill -
which is what `DriftController.resume()` is for.

🔴 **"Is the ring dry" and "how much delay is there" are different questions, and one number
answered both wrongly.** Trimming is about the total, device queue included. A drought is about the
ring alone: during one, the device queue holds nothing but the controller's own padding, so
measuring the total let the padding prove to itself that there was still audio to play. The budget
never ran out and the receiver padded through the entire silence - measured 2026-09-18, latency
cycling between 2 and 10 ms for a minute after the sender had stopped, with the audio device
written to the whole time, while every counter on the screen read clean.

🔴 **Nothing is trimmed before the device has made a sound.** Waking the audio path takes about
a tenth of a second and audio keeps arriving throughout, which puts a freshly started stream over
the ceiling before a single frame has been heard. That excess is the wake-up, not a stream that
got ahead - and a trim takes it off the *front* of the ring, which is the beginning of the sound
that did the waking. So while `AudioSink.hasStarted` is false, the receiver leaves the backlog
alone; once frames are coming out, the ordinary trim takes the delay back in one cut, with the
fade every other cut gets.

This used to discard the whole backlog instead, on the reasoning that throwing away the start is
silent because there is nothing yet to be silent about. That holds for music starting up. It is
exactly wrong for a short sound, which is *all* beginning: measured 2026-09-18 with 6 ms clicks
three seconds apart, 82-114 ms was discarded at every click - the entire click, every time. They
were inaudible on the phone, and audible again once the rule changed. 🔑 A sound that is shorter
than the wake-up cannot survive a policy that spends the wake-up on it.

### Awake mode

🔴 **Pausing the audio device is not free to undo, and short sounds pay the whole bill.** The
step above keeps the sound, but it still arrives a tenth of a second late, because the device has
to be started again first. `IdlePolicy.AWAKE_STALE_AFTER_MS` removes the pause instead: the
`STALE` tier is pushed out to where `IDLE` hands the device back anyway, and `DriftController`'s
pad budget is stretched to match, so silence keeps being fed and the device keeps running between
sounds. A click then plays the moment it arrives.

🔴 **A charger turns it on by itself, and the setting only answers for battery.** There is
nothing to weigh while plugged in: holding the audio path open costs the listener nothing and buys
every short sound its opening, so that case is not offered as a choice. What it does cost is
battery - the audio path and its wake-ups are held for as long as a minute after each sound - so
that is the one thing a person is asked about.

🔑 **The setting is worded as the saving, not as the keeping awake, and is on by default.** A
switch reads as "this happens while it is on", so the one that is on by default should describe
what the phone actually does with nothing plugged in: let the engine stop, and be a tenth of a
second late with the next short sound. Stated the other way round it would be a negative that has
to be switched on, which is one turn of the head too many for a thing this small.

```
keepAwake = charging || !saveBatteryWhenSilent
```

`ReceiverService` watches `ACTION_POWER_CONNECTED` / `_DISCONNECTED` for the edges and
`BatteryManager.isCharging` for the state in between - 🔑 a service started while already plugged
in would otherwise never hear a broadcast - and hands the result to `ScreamReceiver.keepAwake`,
which the play thread picks up on its next turn.

## Unicast and multicast

Unicast is the default, and that is a decision. Multicast over Wi-Fi is forwarded at the slowest
rate the access point supports and is dropped outright by plenty of consumer routers, so a
receiver that defaulted to it would look broken on exactly the networks phones are on. It is
offered because one sender can feed several listeners that way.

🔴 Two things are easy to get wrong and silent when wrong:
- **`MulticastLock`**, without which Wi-Fi hardware filters group traffic out before the app ever
  sees it. It is held only while multicast is actually selected.
- **The interface for `joinGroup`**, which is named explicitly rather than left to the system: a
  join on the wrong interface leaves everything looking healthy while nothing arrives.

## Staying alive with nobody watching

The receiver lives in a foreground service, not in the screen. That is not tidiness: without one
the system stops the process soon after the screen goes off, and the screen going off is exactly
when someone wants this running.

```
ReceiverService  ── owns ──▶ ScreamReceiver
      │
      ├─ publishes a snapshot ──▶ the screen, the notification, the quick settings tile
      ├─ PARTIAL_WAKE_LOCK          (the CPU keeps running)
      └─ WifiLock LOW_LATENCY       (Wi-Fi stops batching and dropping UDP; traded
                                     down to HIGH_PERF after ten quiet minutes)
```

🔑 **State flows one way.** The activity comes and goes - rotation, back button, the launcher - and
audio must not, so nothing but the service owns the receiver. All three surfaces read the same
immutable snapshot, which is what stops the notification showing one state while the screen shows
another.

🔑 **The notification is a control surface, not a status line.** Something that runs for hours is
operated from the shade far more often than from the app, so *pause* and *turn off* are buttons
there, and the line underneath answers "is it working" without opening anything.

🔑 **It is called "mute", not "pause", and that is not wording.** Pause promises to carry on from
where it stopped. This deliberately does not: it keeps reading the socket and throws the audio
away, so unmuting gives the live stream rather than a backlog. Stopping the reads instead would
let the kernel queue and the ring fill up, and unmuting would play minutes-old audio. The
notification explains this, because "why does it not replay the gap" is otherwise a reasonable
thing to wonder — and it is the same machinery a phone call will use.

🔴 **There is no "start on boot."** From target SDK 35 a `mediaPlayback` service cannot be started
from `BOOT_COMPLETED`. A quick settings tile takes its place: one press, from anywhere, without
hunting for the app.
⚠️ The tile has to be dragged into the panel by hand the first time — there is no API to add it
before Android 13, and even there it is a request the person answers.

### The system can still freeze it

🔴 **A foreground service and a wake lock are not the last word.** Measured on a Galaxy phone,
2026-09-19: with the screen off, unplugged and nothing arriving, the phone goes idle, the
service's `PARTIAL_WAKE_LOCK` is listed as `DISABLED`, and the process is put in the kernel's
frozen cgroup. The socket keeps being delivered to - the kernel counts every packet straight into
`drops` with the queue empty - because the thread that would read it cannot run. Sound does not
come back when the PC starts sending again; it comes back when the phone is unlocked.

🔑 **Silence suppression is what uncovered this**, which is to say a feature working rather than
breaking. A sender that goes quiet through the quiet leaves the phone nothing to do, and nothing
to do is the condition for going idle; a stream that never stops kept the phone busy and hid it.
It is also why a charger hides it - a phone that is plugged in does not go idle at all - and why
an hours-long test with audio playing throughout will never show it.

🚫 **It cannot be fixed from inside.** Everything the platform offers is already held. The cure is
the battery exemption, which is the user's to grant:

- the app asks `PowerManager.isIgnoringBatteryOptimizations` on every resume, and says so on the
  main screen when the answer is no, with a button to the page that grants it;
- asking for it directly would need `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, a restricted
  permission that has to be justified to a store, so the app opens its own details page instead;
- the diagnostics screen and the copied report both carry the state, because "it went quiet
  overnight" has no other symptom: no dropout, no error, no counter moving.

🔑 **Being frozen can only be reported afterwards.** Nothing in the process runs while it is held,
so there is no moment to log at. The publish loop ticks ten times a second and compares
`elapsedRealtime` between passes; whole seconds missing means the process was not running, and
that is written to the event log. It cannot tell a freeze from the phone sleeping, and does not
try - to somebody who heard nothing they are the same thing, with the same cure.

## The five screens

```
   Guide  ─ first launch, then Settings ─┐
                                         ▼
                                 ┌──────────┐
                                 │   Main   │  state · level meter · fader · stream · address
                                 └────┬─────┘
                            ┌─────────┼──────────┐
                            ▼         ▼          ▼
                          Call    Settings   Diagnostics
```

🔑 **A star, not a graph**, so there is no navigation library and no back stack: four leaves,
each reached from the main screen and each returning to it.

🔑 **The guide is the one screen shown without being asked for**, and it exists for the one step
that has no symptom. A wrong address is silent immediately and the main screen says so; battery
optimisation works, and then hours later the phone freezes the app with the screen off and the
sound never returns - which nothing can explain at the moment it happens, because the app is not
running to explain it. So it is said first, before it happens. Three numbered steps in the order
they bite (the exemption, the address for the PC, the tile that replaces start-on-boot) and one
unnumbered tip; each step carries the button that performs it, and the one with a state to check
shows that state instead of asking. It is remembered in the settings - one store, so it arrives
with everything else rather than flashing past on a launch where the other was late.

🔑 **The main screen answers one question in a second: is sound coming out.** Hence a coloured
word, a switch and a meter that moves; the numbers sit underneath for when the answer is no.

🔑 **Every bar of the level meter is a measured peak**, taken on the playback thread after the
fade and after the fader, immediately before the write (`audio/Peaks.kt`). A decoration that
moved whether or not audio was arriving would answer the screen's own question wrongly. The
strip is a mirrored history - newest in the middle, flowing out, left channel on the left - so a
stutter half a second ago is still on screen when you look up. Levels are drawn in decibels: a
linear meter spends its whole length on the top few dB and never leaves the floor for music.

🔑 **The fader is the app's own, not the system's**, and its travel is decibels (`audio/Volume.kt`).
Wired straight to `AudioTrack.setVolume` a slider would do nothing for its top half and drop to
inaudible in the last few per cent.

🔑 **The address on the main screen is what the *PC* needs.** Getting audio here means writing
that exact string into a file on the sending machine, and a mistyped octet is indistinguishable
from a quiet PC at this end - so it is one tap to copy and one to scan.

🔑 **The diagnostics screen exists because network audio is mostly "why can I not hear it".** Four
tiles that each distinguish a different failure, a latency sparkline kept by the *service* (the
screen that draws it may not have existed during the interesting minute), and a short event log
written from outside the audio threads - they may not allocate, so everything in it is noticed
from the counters moving.

🔑 **Receiving on mobile data is off by default and means something.** A default-network callback
watches the transports; while the only one is cellular the receiver is not merely quiet, it is
not created, and the screen and the notification say which switch to go and find. Only a network
positively seen as cellular-and-not-Wi-Fi blocks anything: refusing to play on a doubt would be
the worse failure.

## When a call arrives

Audio focus is the primary signal — no `READ_PHONE_STATE`, and it covers alarms and navigation
too. It is not, on its own, enough.

🔴 **Three signals, and the third is the one that works. Measured on a real call, 2026-09-17.**

| signal | what it said about a VoIP call |
|---|---|
| `AUDIOFOCUS_*` | **`LOSS` — permanent.** Indistinguishable from a music player taking over |
| `AudioManager.getMode()` | **`MODE_NORMAL` throughout.** A VoIP app need not set the telephony mode |
| `getActivePlaybackConfigurations()` | **`USAGE_NOTIFICATION_RINGTONE`.** The only one that said "call" |

🔑 **Playback configurations need no permission**, and although the system anonymises *who* is
playing, it keeps the **usage** — which is the entire question. Ringtone and voice-communication
usages are a call; `USAGE_MEDIA` is another app.

🔴 **An incoming ring asks for permanent `AUDIOFOCUS_GAIN`.** So "stop on a permanent loss" — the
obvious reading of the focus contract — switches the session off every time the phone rings,
which is the exact opposite of what this feature is for.

🔴 **The two events arrive in either order.** The focus loss was seen two seconds *after* the ring
began on one call, and one second *after the ring had already ended* on another. The window that
matches them therefore looks both ways: a ring seen shortly before a loss counts as much as one
seen after it.

⇒ A permanent loss means **go quiet now and decide in a moment**: call → stay muted and ask for
the speaker again when it ends (a permanent loss is never followed by a `GAIN`); no call within
five seconds → it really was another app, so end the session and say so on screen.

🔴 **While silenced the socket keeps being drained and the data thrown away.** Simply stopping
would let three minutes of a call become three minutes queued in the kernel and the ring buffer,
and playback would resume three minutes in the past. This is the same machinery the mute button
uses — which is why a call needed no new playback path at all. On coming back, the ring is
cleared and refilled from the start threshold.

ℹ️ Notification sounds are handled by the system, not here: unless the request declares it would
rather pause, the system ducks the app itself. That is what the "quieten for notification sounds"
setting turns on and off.

## Sharing the speaker

🔑 **"Share with other apps" is the absence of a focus request, not a branch inside the handler.**
Asking for focus is both what takes the speaker away from a music app and what lets that app take
it away from us; not asking leaves the two streams mixing. It exists because this stream is not
always media in the ordinary sense - somebody listening to music on the phone may want the PC's
alert sounds audible over it, and for that the receiver has to behave like an alert channel.

🔴 **Calls still silence it, and that is not luck.** The two signals that actually caught a call -
the audio mode and what is being played - have nothing to do with focus, so they keep working
with no claim on the speaker at all. Only the focus-shaped outcomes disappear: nothing can take
the speaker permanently, so the session can no longer be ended by another app.

ℹ️ Ducking changes hands in this mode. With focus held the **system** ducks the app for a
notification; with no focus nobody will, so the same playback list that finds a ringtone finds
notification and alarm usages and the receiver ducks itself. An alarm therefore quietens the
stream here where it would silence it in the ordinary mode - which is the reading that matches
what somebody asking to hear several things at once meant.

## Platform constraints that shaped this

- Foreground service type `mediaPlayback` is mandatory from Android 14.
- From target SDK 35 a `mediaPlayback` service **cannot be started from `BOOT_COMPLETED`** ⇒ there
  is no "start on boot"; a quick settings tile takes its place.
- From target SDK 35 audio focus is granted only to the top app or one with a running foreground
  service ⇒ start the service **first**, then request focus.
- The system stops a foreground service that has been idle for ten minutes ⇒ tidy up first.
- With the screen off, Wi-Fi power saving drops UDP ⇒ `PARTIAL_WAKE_LOCK` plus a wifi lock;
  `WIFI_MODE_FULL_LOW_LATENCY` only while actually playing, since it is designed for bounded use.
- 🔴 **That lock is inactive exactly when this app needs it.** The platform documents it as active
  *"only when the screen is on"* and *"only when the acquiring app is running in the foreground"*,
  and `WIFI_MODE_FULL_HIGH_PERF` is deprecated and replaced by the same mode - so there is nothing
  to hold instead. Measured on the device, 2026-09-19: with the screen off neither lock's active
  timer advances, under either mode. **The buffer is what carries a screen-off session**, which is
  why the presets exist and why the low-latency one is offered rather than assumed.
