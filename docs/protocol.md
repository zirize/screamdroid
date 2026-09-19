# The Scream wire format

One UDP packet is a **5-byte header followed by up to 1152 bytes of little-endian PCM**. That is
the whole protocol. There is no handshake, no sequence number, no timestamp and no retransmission.

| byte | meaning |
|---|---|
| `[0]` | sample rate. **bit 7 selects the base** (0 → 48000, 1 → 44100); the low 7 bits are the multiplier ⇒ `rate = (b0 >= 128 ? 44100 : 48000) * (b0 % 128)` |
| `[1]` | sample width in bits: 16, 24 or 32 |
| `[2]` | channel count |
| `[3..4]` | `dwChannelMask` from `WAVEFORMATEXTENSIBLE`, low byte first |

## What follows from it

- **1152 divides by the frame size of the layouts that occur** — 1, 2, 3, 4, 6 and 8 channels, at
  any of the three sample widths — so a payload never ends mid-frame and a receiver can treat each
  payload as whole frames with no remainder carried between packets.
  🔴 Not *every* count: 1152 = 2⁷ × 9, so 5 and 7 channels would leave a partial frame. No sender
  produces those layouts in practice, but do not write the assumption down as universal.
- **The format can change from one packet to the next.** If the five header bytes differ from the
  previous packet, reopen the output device.
- **Loss and reordering cannot be detected**, because nothing in the packet is numbered. Absorbing
  jitter is entirely the receiver's buffer, and a gap stays a gap.
- **Silence suppression** on the sending side stops the packets altogether while nothing is
  playing. A receiver therefore **cannot tell "silence" from "not arriving"**, and everything it
  says to the user about an idle stream has to be true of both.

## Channel mask

Windows `SPEAKER_*` and Android `CHANNEL_OUT_*` list the same speakers in the same order; the
values differ by a two-bit shift. So `androidMask = winMask shl 2`.

## The sender used here

A PipeWire module (<https://github.com/zirize/pipewire-scream>) mixes several local sources into
one virtual sink and sends that single stream. On the wire it is `48000 Hz · 16-bit · 2 ch ·
mask 0x3`, header bytes `01 10 02 03 00`, with silence suppression on.
