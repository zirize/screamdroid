# Prior art — what was read, and what was not taken

This receiver is licensed Apache-2.0. That is only honest if none of it was copied, so this file
records what was looked at and what came from where. Everything in `app/` was written from
`docs/protocol.md`, which describes a format, not an implementation.

## Read

| project | licence | what it is | what was taken |
|---|---|---|---|
| [duncanthrax/scream](https://github.com/duncanthrax/scream) | MS-PL | upstream sender and receivers; its README is the public description of the format | **the format description only** — no code. `docs/protocol.md` is written from it |
| [martinellimarco/scream-android](https://github.com/martinellimarco/scream-android) | GPL-3.0 | a 236-line single-Activity receiver | **nothing.** Read to see how it handled the header and the channel mask, then closed |
| [netham45/android-scream-receiver](https://github.com/netham45/android-scream-receiver) | no licence file | a foreground-service receiver with wake and wifi locks | **nothing.** With no licence there is nothing to take even with attribution |

## Why this matters more than usual here

- A file with **no licence** is not permissive by default; it is all rights reserved. Copying from
  a receiver of that kind would make this repository unpublishable, not merely attributed.
- Copying from the GPL-3.0 receiver would make **this whole repository GPL-3.0**.
- The upstream project is MS-PL, and a derivative of it inherits MS-PL. The PipeWire sender is
  such a derivative and carries that licence; this receiver is not one.

## What the two receivers were useful for

Not as code, as **evidence about the platform**: that a foreground service plus wake and wifi
locks is what keeps UDP arriving with the screen off, and that a receiver which does its
receiving and its playback on one thread accumulates latency in the socket queue whenever
playback blocks. Both conclusions are designed *against* in `docs/architecture.md`.

## Dependencies — code that *is* used, and under what

Nothing above is a dependency: those were read, not linked. What the app actually ships beside
AndroidX is one library.

| library | licence | why |
|---|---|---|
| [com.google.zxing:core](https://github.com/zxing/zxing) | Apache-2.0 | encodes the QR code on the main screen. Only the `core` artefact - the camera side is never used |

🔑 **Apache-2.0 on Apache-2.0 carries no obligation beyond attribution**, which this table is.
🔑 It is here rather than hand-written because a QR encoder is Reed-Solomon, mask selection and a
   version table - a week of somebody else's solved bugs for a picture of an IP address.
