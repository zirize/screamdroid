package io.github.zirize.screamdroid.settings

import io.github.zirize.screamdroid.audio.BufferPolicy
import io.github.zirize.screamdroid.net.ScreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun defaultsAreTheOnesTheSenderUses() {
        val s = Settings()
        assertEquals(ScreamProtocol.DEFAULT_PORT, s.unicastPort)
        assertEquals(ScreamProtocol.DEFAULT_PORT, s.multicastPort)
        assertEquals(BufferPolicy.BALANCED, s.bufferPolicy)
    }

    /**
     * 🔴 **The group has no switch, and unicast defaults on.** A phone that was receiving
     *    unicast before the two could be had at once has to go on receiving it without anybody
     *    going into settings - see SettingsRepository for the stored answer being carried over.
     */
    @Test
    fun unicastIsOnByDefaultAndTheGroupIsNotASetting() {
        val s = Settings()
        assertTrue(s.unicastEnabled)
    }

    /**
     * 🔑 The two ports are independent, and equal by default so that a sender already aimed at
     *    4010 keeps working whichever way it addresses the packets.
     */
    @Test
    fun theTwoPortsMoveIndependently() {
        val s = Settings().copy(multicastPort = 4011)
        assertEquals(ScreamProtocol.DEFAULT_PORT, s.unicastPort)
        assertEquals(4011, s.multicastPort)
    }

    /**
     * 🔑 Corrected rather than rejected: this path also reads stored values, and a stored port
     *    that has become impossible must still leave the receiver with one it can bind.
     */
    @Test
    fun anImpossibleStoredPortFallsBackToTheDefault() {
        assertEquals(ScreamProtocol.DEFAULT_PORT, Settings.sanitizePort(0))
        assertEquals(ScreamProtocol.DEFAULT_PORT, Settings.sanitizePort(80))
        assertEquals(ScreamProtocol.DEFAULT_PORT, Settings.sanitizePort(70_000))
        assertEquals(ScreamProtocol.DEFAULT_PORT, Settings.sanitizePort(-1))
        assertEquals(4011, Settings.sanitizePort(4011))
        assertEquals(Settings.MIN_PORT, Settings.sanitizePort(Settings.MIN_PORT))
        assertEquals(Settings.MAX_PORT, Settings.sanitizePort(Settings.MAX_PORT))
    }

    /** What somebody typed, on the other hand, can simply be wrong - and the field should say so. */
    @Test
    fun typedTextIsParsedOrRefused() {
        assertEquals(4010, Settings.parsePort("4010"))
        assertEquals(4010, Settings.parsePort("  4010 "))
        assertNull(Settings.parsePort(""))
        assertNull(Settings.parsePort("abc"))
        assertNull(Settings.parsePort("80"))
        assertNull(Settings.parsePort("70000"))
        assertNull(Settings.parsePort("4010x"))
    }

    /**
     * 🔴 `joinGroup` throws on an address outside 224.0.0.0/4, and it would throw on the receive
     *    thread. A typo must be caught while it is still text.
     */
    @Test
    fun onlyAMulticastAddressIsAGroup() {
        assertEquals("239.255.77.77", Settings.parseGroup("239.255.77.77"))
        assertEquals("224.0.0.1", Settings.parseGroup(" 224.0.0.1 "))
        assertEquals("239.255.255.255", Settings.parseGroup("239.255.255.255"))
        assertNull("223 is below the range", Settings.parseGroup("223.255.77.77"))
        assertNull("240 is above it", Settings.parseGroup("240.0.0.1"))
        assertNull("an ordinary LAN address is not a group", Settings.parseGroup("192.0.2.7"))
        assertNull(Settings.parseGroup("239.255.77"))
        assertNull(Settings.parseGroup("239.255.77.256"))
        assertNull(Settings.parseGroup("239.255.77.a"))
        assertNull(Settings.parseGroup(""))
    }

    @Test
    fun anImpossibleStoredGroupFallsBackToTheDefault() {
        assertEquals(ScreamProtocol.DEFAULT_MULTICAST_GROUP, Settings.sanitizeGroup(null))
        assertEquals(ScreamProtocol.DEFAULT_MULTICAST_GROUP, Settings.sanitizeGroup("192.0.2.7"))
        assertEquals("225.1.2.3", Settings.sanitizeGroup("225.1.2.3"))
    }

    @Test
    fun aStoredVolumeIsBroughtBackIntoRange() {
        assertEquals(0, Settings.sanitizeVolume(-10))
        assertEquals(100, Settings.sanitizeVolume(1_000))
        assertEquals(37, Settings.sanitizeVolume(37))
    }

    @Test
    fun everyPresetHasRoomBetweenTargetAndCeiling() {
        for (policy in BufferPolicy.entries) {
            assert(policy.targetMs > 0) { "$policy has no target" }
            assert(policy.maxMs > policy.targetMs) { "$policy has no room to absorb a burst" }
        }
    }
}
