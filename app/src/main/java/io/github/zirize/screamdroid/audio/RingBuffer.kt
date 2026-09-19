package io.github.zirize.screamdroid.audio

import java.util.concurrent.atomic.AtomicLong

/**
 * A byte ring for one producer and one consumer, and nothing more.
 *
 * 🔑 **This buffer is the whole jitter strategy.** The protocol has no sequence numbers and no
 *    retransmission, so a receiver cannot recover anything it did not get in time; all it can do
 *    is hold enough to ride out the network. Everything else - drift, underrun, a wireless stall -
 *    is read off this one number, [available].
 *
 * 🔑 **Lock-free by construction, not by cleverness.** Positions are monotonic longs, so "full"
 *    and "empty" can never look alike, and each side writes only its own position. That is what
 *    lets the receiving thread never block: it is the one rule the design rests on.
 * 🚫 Two producers or two consumers break it. There is exactly one of each.
 */
class RingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val buf = ByteArray(capacity)
    private val readPos = AtomicLong(0)
    private val writePos = AtomicLong(0)

    /** Bytes waiting to be played. */
    fun available(): Int = (writePos.get() - readPos.get()).toInt()

    /** Room left for more. */
    fun free(): Int = capacity - available()

    /**
     * Copy in what fits and report how much that was.
     *
     * 🔑 A short return is **the overflow signal** and the caller counts it. It never overwrites
     *    what has not been played: dropping the newest is a loss we can name, while overwriting
     *    the oldest is a discontinuity in the middle of sound already committed to.
     */
    fun write(src: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, free())
        if (n <= 0) return 0
        val w = (writePos.get() % capacity).toInt()
        val firstPart = minOf(n, capacity - w)
        System.arraycopy(src, offset, buf, w, firstPart)
        if (n > firstPart) System.arraycopy(src, offset + firstPart, buf, 0, n - firstPart)
        writePos.addAndGet(n.toLong())
        return n
    }

    /** Copy out up to [length] bytes and report how many there were. */
    fun read(dst: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, available())
        if (n <= 0) return 0
        val r = (readPos.get() % capacity).toInt()
        val firstPart = minOf(n, capacity - r)
        System.arraycopy(buf, r, dst, offset, firstPart)
        if (n > firstPart) System.arraycopy(buf, 0, dst, offset + firstPart, n - firstPart)
        readPos.addAndGet(n.toLong())
        return n
    }

    /** Throw away the [n] oldest bytes - how latency that has built up is given back. */
    fun skip(n: Int): Int {
        val k = minOf(n, available())
        if (k <= 0) return 0
        readPos.addAndGet(k.toLong())
        return k
    }

    /** 🚫 Consumer side only: it moves the read position past everything written so far. */
    fun clear() {
        readPos.set(writePos.get())
    }
}
