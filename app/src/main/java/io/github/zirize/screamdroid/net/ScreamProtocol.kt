package io.github.zirize.screamdroid.net

/**
 * The wire format, and nothing else.
 *
 * A Scream packet is a 5-byte header followed by little-endian PCM. There is no handshake, no
 * sequence number and no timestamp - see docs/protocol.md. These constants are written from that
 * public description; no code was taken from any existing receiver (docs/prior-art.md).
 *
 * 🔴 Do not change these to suit the app. They describe what the sender puts on the wire; the
 *    sender in use here is a PipeWire module whose format is fixed independently of this app.
 */
object ScreamProtocol {

    /** rate, sample width, channel count, channel mask low, channel mask high. */
    const val HEADER_BYTES = 1 + 1 + 1 + 2

    /**
     * Largest PCM payload in one packet.
     *
     * 🔑 1152 divides by the frame size of the layouts that actually occur - 1, 2, 3, 4, 6 and 8
     *    channels, at any of the three sample widths - so a frame is never split across a packet
     *    boundary and a receiver need not carry a remainder between packets.
     * 🔴 It is **not** every channel count: 1152 = 2^7 x 9, so 5 and 7 channels leave a partial
     *    frame. Those are not layouts a Scream sender produces in practice, but code that assumes
     *    whole frames must not be written as though the rule were universal.
     *    ScreamProtocolTest pins both halves of this.
     */
    const val MAX_PAYLOAD_BYTES = 1152

    const val MAX_PACKET_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES

    /** Header byte 0: bit 7 picks the base rate, the low 7 bits are the multiplier. */
    const val RATE_BASE_48000 = 48000
    const val RATE_BASE_44100 = 44100

    /** The port both prior receivers hard-coded, kept here as the default a user can change. */
    const val DEFAULT_PORT = 4010

    /** The group upstream Scream senders use when they are not aimed at one address. */
    const val DEFAULT_MULTICAST_GROUP = "239.255.77.77"
}
