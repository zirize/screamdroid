package io.github.zirize.screamdroid.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGateTest {

    @Test
    fun `unicast is heard when the group is silent`() {
        val gate = SourceGate(holdMs = 200)
        assertTrue(gate.allowUnicast(0))
        assertTrue(gate.allowUnicast(10_000))
    }

    @Test
    fun `a multicast packet takes the ring from unicast`() {
        val gate = SourceGate(holdMs = 200)
        gate.onMulticast(1_000)
        assertFalse(gate.allowUnicast(1_000))
        assertFalse(gate.allowUnicast(1_199))
    }

    @Test
    fun `unicast comes back once the hold lapses`() {
        val gate = SourceGate(holdMs = 200)
        gate.onMulticast(1_000)
        assertTrue(gate.allowUnicast(1_200))
    }

    /**
     * 🔑 A stream holds the ring for as long as it keeps arriving - the hold is measured from the
     *    last packet, not from the first.
     */
    @Test
    fun `a continuing stream keeps the ring`() {
        val gate = SourceGate(holdMs = 200)
        var now = 1_000L
        repeat(100) {
            gate.onMulticast(now)
            now += 6
            assertFalse(gate.allowUnicast(now))
        }
        assertTrue(gate.allowUnicast(now + 200))
    }

    /**
     * 🔴 The clock starts near zero after a boot. A gate that had never heard the group must not
     *    read that as "heard it a moment ago" and swallow unicast for the first fifth of a second
     *    of every session.
     */
    @Test
    fun `a fresh gate is not holding the ring at boot`() {
        val gate = SourceGate(holdMs = 200)
        assertTrue(gate.allowUnicast(0))
        assertFalse(gate.multicastLive(0))
    }

    @Test
    fun `reset forgets the group`() {
        val gate = SourceGate(holdMs = 200)
        gate.onMulticast(1_000)
        assertFalse(gate.allowUnicast(1_000))
        gate.reset()
        assertTrue(gate.allowUnicast(1_000))
    }
}
