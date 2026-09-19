package io.github.zirize.screamdroid.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreamProtocolTest {

    @Test
    fun headerIsFiveBytes() {
        assertEquals(5, ScreamProtocol.HEADER_BYTES)
    }

    /**
     * 🔑 The property the receiver leans on: a maximum payload holds a whole number of frames, so
     *    nothing has to be carried across packets.
     */
    @Test
    fun maxPayloadHoldsWholeFramesForTheUsualLayouts() {
        val bytesPerSample = 2 // 16-bit is the only width this app handles
        for (channels in intArrayOf(1, 2, 3, 4, 6, 8)) {
            val frameBytes = channels * bytesPerSample
            assertTrue(
                "payload of ${ScreamProtocol.MAX_PAYLOAD_BYTES} splits a frame at $channels ch",
                ScreamProtocol.MAX_PAYLOAD_BYTES % frameBytes == 0,
            )
        }
    }

    /**
     * 🔴 And the two counts where it does **not** hold. 1152 = 2^7 × 9, so 5 and 7 channels leave a
     *    partial frame at any sample width. They are pinned here so the whole-frames property above
     *    is never read as "for every channel count": a receiver that met a 5.0 or 7.0 sender and
     *    assumed whole frames would drift by a fraction of a frame per packet and end up with the
     *    channels rotated.
     */
    @Test
    fun fiveAndSevenChannelsDoNotDivideThePayload() {
        for (channels in intArrayOf(5, 7)) {
            assertFalse(
                "$channels ch unexpectedly divides ${ScreamProtocol.MAX_PAYLOAD_BYTES}",
                ScreamProtocol.MAX_PAYLOAD_BYTES % (channels * 2) == 0,
            )
        }
    }

    @Test
    fun packetFitsInATypicalDatagramBuffer() {
        assertEquals(1157, ScreamProtocol.MAX_PACKET_BYTES)
    }
}
