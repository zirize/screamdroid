# Screamdroid

📄 Project page: **[Screamdroid — play your PC's audio on an Android phone, and let a call silence it](https://zirize.github.io/screamdroid/)** · More projects: **[zirize.github.io](https://zirize.github.io/)**

**Turn a phone into the Bluetooth adapter a PC doesn't have - one that knows when you get a call.**

An Android receiver for [Scream](https://github.com/duncanthrax/scream) audio over UDP. The PC
sends its audio over Wi-Fi and the phone plays it through whatever headphones are already paired
with it, so a desktop with no Bluetooth of its own borrows the phone's.

🔑 **Unlike a USB dongle, the receiver is on the phone** - which is the only place that can tell a
call is coming. An incoming call silences the PC audio by itself and hands it back when the call
ends; a dongle cannot, because nothing in it ever hears the phone ring.

⚠️ **The trade is latency, not stability.** This path is slower than a dongle - tens of
milliseconds over the network, plus whatever Bluetooth adds after it. It is built for music,
alerts and video, and the target is "never breaks up", not "tight enough for games".

```
  PC (PipeWire)                       phone
  ┌──────────────┐   udp :4010   ┌──────────────┐
  │ sources      │──────────────▶│ Screamdroid  │──▶ speaker / headphones
  │ mixed to one │   5-byte hdr  │  (one stream)│
  └──────────────┘   + PCM       └──────────────┘
```

Several sources are mixed **on the PC**, so the phone receives a single stream: one output track,
one buffer, one clock to follow. The sender that does this is
[pipewire-scream](https://github.com/zirize/pipewire-scream); any Scream sender works.

## Install

It is going to Google Play, on the internal testing track — the track is being set up now.

**Want in? Send the Google account address you would install it with to `zirize@gmail.com`** — by
email, **not in an issue**, so your address does not end up on a public page. You go on the tester
list, and the link that installs it from Play comes back to you as soon as the track opens.

- 🔑 It has to be the address of the **Google account on the phone**. Play matches the tester
  list against the account that opens the link, so any other address will simply say the app is
  not available — and that symptom tells you nothing about why.
- The address is used to add you as a tester and to send you that link. Nothing else. The app
  itself collects nothing and has no account: see the
  [privacy policy](https://zirize.github.io/screamdroid/privacy.html).
- Android **8.0 or newer** (API 26).
- You will need a PC that sends Scream audio to point it at — on Linux that is
  [pipewire-scream](https://github.com/zirize/pipewire-scream). This is the receiving half.

Rather not hand over an address? Build it yourself — the repository builds with one command and needs nothing but the Android
SDK and a JDK 17:

```bash
bash scripts/build.sh            # release APK, signed with the debug key
bash scripts/build.sh install    # …and push it to a connected phone
```

Open it, and the guide on first launch has the three things to do once — the battery setting
below, the address to write on the PC, and the quick settings tile.

🚫 **No APK is published here, and that is deliberate.** An APK carries the certificate
it was signed with, and a certificate carries the name and address of whoever made it — readable
by anyone who has the file, with one command and no password. What a store hands you is signed
with the store's own key instead, so none of that travels with it — which is the way this goes out.

## Status

It works, calls included. The receiver runs in a foreground service, survives the screen going
off, settings persist and apply live, the five screens are built, and an incoming call silences
it and lets it come back by itself.

🔑 That last part needed **three** signals, not the one the audio-focus contract suggests: an
incoming ring asks for *permanent* focus, and a VoIP call may never touch the telephony mode. See
"When a call arrives" in `docs/architecture.md` — it is the least obvious thing in this codebase.

## One phone setting

🔴 **Set this app's battery use to *unrestricted*** (App info → Battery → Unrestricted). Without
it the phone is free to freeze the app once the screen has been off for a few minutes, and audio
then stops arriving and does not return until the phone is unlocked - a foreground service, a wake
lock and a Wi-Fi lock do not prevent it. See "The system can still freeze it" in
`docs/architecture.md`.

The app asks for none of this silently: the guide on first launch has it as step one with a button
to the right page, the main screen says so while it is not granted, and the diagnostics screen
carries the state. The guide is in Settings whenever it is wanted again.

## Build

```bash
bash scripts/doctor.sh     # is this host able to build it
bash scripts/build.sh      # release APK
bash scripts/build.sh test # unit tests, no device needed
bash scripts/build.sh install
```

🔑 **Go through `scripts/build.sh`, not `./gradlew`.** The wrapper needs `JAVA_HOME`, and on a host
whose only JDK lives inside the Android toolchain there is none to inherit; the script resolves it
(`scripts/_hostenv.sh`, the single place any host path is written down).

Requirements: JDK 17, Android SDK with platform 36. No NDK — playback is `AudioTrack`, so there is
no native code.

## Documentation

📄 The [project page](https://zirize.github.io/screamdroid/) is the same story written for reading
rather than for building; the [privacy policy](https://zirize.github.io/screamdroid/privacy.html)
is one sentence long in substance (nothing is collected).

| file | what |
|---|---|
| `docs/protocol.md` | the wire format, and what follows from it |
| `docs/architecture.md` | threads, buffering, idle, what happens on a call |
| `docs/prior-art.md` | what was read, and why none of it was copied |

## Licence

Apache-2.0. The protocol was implemented from its public description, not from existing
receivers — `docs/prior-art.md` records exactly that.
