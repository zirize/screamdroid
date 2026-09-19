package io.github.zirize.screamdroid.net

/**
 * The five header bytes, parsed. See docs/protocol.md.
 *
 * 🔑 Plain Kotlin on purpose - no Android types - so the parsing rules can be tested on the JVM.
 *    Turning this into an `AudioTrack` format is [io.github.zirize.screamdroid.audio.AudioSink]'s
 *    job, not this class's.
 * 🔑 It is a data class because **equality is the format-change test**: the sender may change
 *    format between one packet and the next, and the receiver notices by comparing.
 */
data class ScreamHeader(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channels: Int,
    /** `dwChannelMask` exactly as it came off the wire, i.e. in Windows `SPEAKER_*` values. */
    val channelMask: Int,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)

    val bytesPerSecond: Int get() = bytesPerFrame * sampleRate

    /**
     * 🔑 Windows `SPEAKER_*` and Android `CHANNEL_OUT_*` name the same speakers in the same order,
     *    two bits apart: `SPEAKER_FRONT_LEFT|RIGHT` = 0x3 becomes `CHANNEL_OUT_STEREO` = 0xC.
     */
    val androidChannelMask: Int get() = channelMask shl 2

    /**
     * What this app can actually play. 16-bit only - a deliberate limit, not an oversight: the one
     * sender in use is 16-bit, and supporting the other two widths would buy an encoding branch
     * and a conversion path for nothing.
     */
    val isSupported: Boolean get() = bitsPerSample == 16 && channels in 1..8 && sampleRate in 8000..192000

    /** How many bytes of this format hold [ms] milliseconds of audio, rounded down to a frame. */
    fun bytesForMs(ms: Int): Int = (bytesPerSecond.toLong() * ms / 1000L).toInt() / bytesPerFrame * bytesPerFrame

    /** How many milliseconds [bytes] of this format last. */
    fun msForBytes(bytes: Int): Int =
        if (bytesPerSecond == 0) 0 else (bytes.toLong() * 1000L / bytesPerSecond).toInt()

    companion object {
        /**
         * Parse the header at [offset], or return null if those bytes are not a Scream header.
         *
         * 🔑 **Rejecting is the point.** Anything at all can arrive on a UDP port, and the format
         *    has no magic number to check - so the defined value ranges are the only guard there
         *    is. A packet that fails here is counted and dropped; one that passes is trusted all
         *    the way to the audio device.
         */
        fun parse(buf: ByteArray, offset: Int = 0): ScreamHeader? {
            if (offset + ScreamProtocol.HEADER_BYTES > buf.size) return null

            val b0 = buf[offset].toInt() and 0xFF
            val multiplier = b0 and 0x7F
            if (multiplier == 0) return null
            val base = if (b0 >= 0x80) ScreamProtocol.RATE_BASE_44100 else ScreamProtocol.RATE_BASE_48000

            val bits = buf[offset + 1].toInt() and 0xFF
            if (bits != 16 && bits != 24 && bits != 32) return null

            val channels = buf[offset + 2].toInt() and 0xFF
            if (channels < 1 || channels > 8) return null

            val mask = (buf[offset + 3].toInt() and 0xFF) or ((buf[offset + 4].toInt() and 0xFF) shl 8)

            return ScreamHeader(base * multiplier, bits, channels, mask)
        }
    }
}
