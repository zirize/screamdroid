package io.github.zirize.screamdroid.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreamHeaderTest {

    private fun header(vararg bytes: Int) = ScreamHeader.parse(ByteArray(bytes.size) { bytes[it].toByte() })

    /** The header this project's sender actually puts on the wire, measured on the LAN. */
    @Test
    fun parsesTheSenderInUse() {
        val h = header(0x01, 0x10, 0x02, 0x03, 0x00)!!
        assertEquals(48000, h.sampleRate)
        assertEquals(16, h.bitsPerSample)
        assertEquals(2, h.channels)
        assertEquals(0x3, h.channelMask)
        assertTrue(h.isSupported)
    }

    @Test
    fun bitSevenSelectsTheFortyFourOneBase() {
        assertEquals(44100, header(0x81, 0x10, 0x02, 0x03, 0x00)!!.sampleRate)
        assertEquals(88200, header(0x82, 0x10, 0x02, 0x03, 0x00)!!.sampleRate)
        assertEquals(96000, header(0x02, 0x10, 0x02, 0x03, 0x00)!!.sampleRate)
        assertEquals(192000, header(0x04, 0x10, 0x02, 0x03, 0x00)!!.sampleRate)
    }

    /**
     * 🔴 A UDP port takes anything that is sent to it. These are the only checks standing between
     *    a stray packet and the audio device, so each one is pinned.
     */
    @Test
    fun rejectsWhatIsNotAHeader() {
        assertNull("multiplier 0 would mean a rate of 0", header(0x00, 0x10, 0x02, 0x03, 0x00))
        assertNull("sample width 8 is not defined", header(0x01, 0x08, 0x02, 0x03, 0x00))
        assertNull("sample width 0 is not defined", header(0x01, 0x00, 0x02, 0x03, 0x00))
        assertNull("0 channels", header(0x01, 0x10, 0x00, 0x03, 0x00))
        assertNull("9 channels is past what the format carries", header(0x01, 0x10, 0x09, 0x03, 0x00))
        assertNull("too short to be a header", header(0x01, 0x10, 0x02))
    }

    /** 🔑 SPEAKER_* and CHANNEL_OUT_* are the same list two bits apart - stereo proves the shift. */
    @Test
    fun channelMaskShiftsTwoBitsToAndroid() {
        val stereo = header(0x01, 0x10, 0x02, 0x03, 0x00)!!
        assertEquals(0xC, stereo.androidChannelMask) // AudioFormat.CHANNEL_OUT_STEREO
        val fiveOne = header(0x01, 0x10, 0x06, 0x3F, 0x00)!!
        assertEquals(0xFC, fiveOne.androidChannelMask) // CHANNEL_OUT_5POINT1
    }

    @Test
    fun equalityIsTheFormatChangeTest() {
        val a = header(0x01, 0x10, 0x02, 0x03, 0x00)
        val b = header(0x01, 0x10, 0x02, 0x03, 0x00)
        val c = header(0x81, 0x10, 0x02, 0x03, 0x00)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    /** 24- and 32-bit parse - they are real Scream formats - but this app does not play them. */
    @Test
    fun widerSamplesParseButAreNotSupported() {
        assertFalse(header(0x01, 0x18, 0x02, 0x03, 0x00)!!.isSupported)
        assertFalse(header(0x01, 0x20, 0x02, 0x03, 0x00)!!.isSupported)
    }

    @Test
    fun byteAndMillisecondConversionsAgree() {
        val h = header(0x01, 0x10, 0x02, 0x03, 0x00)!!
        assertEquals(4, h.bytesPerFrame)
        assertEquals(192000, h.bytesPerSecond)
        assertEquals(11520, h.bytesForMs(60))
        assertEquals(60, h.msForBytes(11520))
        // 🔑 bytesForMs always lands on a frame boundary; half a frame would put the channels out
        //    of order for everything after it.
        assertEquals(0, h.bytesForMs(1) % h.bytesPerFrame)
        assertEquals(0, h.bytesForMs(7) % h.bytesPerFrame)
    }
}
