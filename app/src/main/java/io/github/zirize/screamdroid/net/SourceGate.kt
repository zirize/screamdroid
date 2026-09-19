package io.github.zirize.screamdroid.net

/**
 * Which of the two streams reaches the ring while both are arriving.
 *
 * 🔴 **They cannot be mixed here, and that is a property of the design rather than a gap.** The
 *    PC is what mixes (docs/architecture.md): the phone keeps one ring and one device, so two
 *    senders writing into it would interleave and be played as noise, not as a blend. Real mixing
 *    needs a ring and a drift controller per sender - the four of them this app was shaped to
 *    avoid. So when both are live, one has to give way.
 *
 * 🔑 **Multicast is the one that is kept.** It is addressed to a group rather than to this phone,
 *    so it goes on working when the phone moves to another network and gets a new address, which
 *    is the case this exists for.
 *
 * 🔑 **"Live" means "was heard from recently", not "is turned on".** With silence suppression at
 *    the sender a quiet PC sends nothing at all, so the hold lapses and unicast is heard. That is
 *    the behaviour worth having: whoever is making sound is heard, and if both are, the group wins.
 *
 * 🔑 Arithmetic on a clock passed in, so the whole rule is a unit test instead of two senders, a
 *    phone and a pair of ears.
 */
class SourceGate(private val holdMs: Long = HOLD_MS) {

    // 🔴 Not 0. The clock this is fed is elapsed-realtime, which starts near zero after a boot,
    //    and "never heard from" has to sit further back than any hold. Halved so that
    //    `now - this` cannot overflow a Long.
    @Volatile private var lastMulticastAt: Long = NEVER

    /** Called for every multicast packet that arrives, before anything else looks at it. */
    fun onMulticast(nowMs: Long) {
        lastMulticastAt = nowMs
    }

    /** Whether a multicast packet has arrived recently enough to still own the ring. */
    fun multicastLive(nowMs: Long): Boolean = nowMs - lastMulticastAt < holdMs

    /** Whether a unicast packet arriving now should be written rather than dropped. */
    fun allowUnicast(nowMs: Long): Boolean = !multicastLive(nowMs)

    /** Forget the group, so a new listening session does not start out deaf to unicast. */
    fun reset() {
        lastMulticastAt = NEVER
    }

    companion object {
        /**
         * 🔑 **Long enough to ride out a gap, short enough not to sit on the stream.** At the
         *    format this carries a packet is about 6 ms, so this is some thirty of them: a lost
         *    burst does not hand the ring back and forth, and a sender that goes quiet is not
         *    still holding it a moment later.
         */
        const val HOLD_MS = 200L

        private const val NEVER = Long.MIN_VALUE / 2
    }
}
