package io.github.zirize.screamdroid.audio

import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBufferTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun writeThenReadGivesBackTheSameBytes() {
        val ring = RingBuffer(16)
        assertEquals(4, ring.write(bytes(1, 2, 3, 4), 0, 4))
        assertEquals(4, ring.available())
        assertEquals(12, ring.free())

        val out = ByteArray(4)
        assertEquals(4, ring.read(out, 0, 4))
        assertArrayEquals(bytes(1, 2, 3, 4), out)
        assertEquals(0, ring.available())
    }

    /** 🔑 The case that hides bugs: a run that straddles the end of the array. */
    @Test
    fun wrapsAroundWithoutLosingOrder() {
        val ring = RingBuffer(8)
        ring.write(ByteArray(6) { it.toByte() }, 0, 6)
        ring.read(ByteArray(6), 0, 6) // read position now at 6, so the next write must wrap

        val src = bytes(10, 11, 12, 13, 14)
        assertEquals(5, ring.write(src, 0, 5))
        val out = ByteArray(5)
        assertEquals(5, ring.read(out, 0, 5))
        assertArrayEquals(src, out)
    }

    /**
     * 🔑 A short write is the overflow signal, and what was already in the buffer must survive it.
     *    Overwriting the oldest instead would corrupt sound the player has already committed to.
     */
    @Test
    fun fullBufferTakesWhatFitsAndKeepsWhatItHad() {
        val ring = RingBuffer(8)
        ring.write(ByteArray(6) { 1 }, 0, 6)
        assertEquals("only 2 bytes of room were left", 2, ring.write(ByteArray(4) { 9 }, 0, 4))
        assertEquals(8, ring.available())
        assertEquals(0, ring.write(ByteArray(1), 0, 1))

        val out = ByteArray(8)
        ring.read(out, 0, 8)
        assertArrayEquals(bytes(1, 1, 1, 1, 1, 1, 9, 9), out)
    }

    @Test
    fun emptyBufferReadsNothing() {
        val ring = RingBuffer(8)
        assertEquals(0, ring.read(ByteArray(4), 0, 4))
        ring.write(bytes(1, 2), 0, 2)
        assertEquals("a short read returns what there is", 2, ring.read(ByteArray(4), 0, 4))
    }

    @Test
    fun skipDropsTheOldestAndClearsEverything() {
        val ring = RingBuffer(8)
        ring.write(bytes(1, 2, 3, 4, 5, 6), 0, 6)
        assertEquals(2, ring.skip(2))
        val out = ByteArray(4)
        ring.read(out, 0, 4)
        assertArrayEquals(bytes(3, 4, 5, 6), out)

        ring.write(bytes(7, 8), 0, 2)
        ring.clear()
        assertEquals(0, ring.available())
        assertEquals(8, ring.free())
        assertEquals("skip cannot go past what is there", 0, ring.skip(5))
    }

    /**
     * One producer, one consumer, no locks - the claim the whole receiver stands on. A million
     * bytes go through a ring far too small to hold them, and every byte must come out in order.
     */
    @Test
    fun oneProducerAndOneConsumerKeepTheStreamIntact() {
        val total = 1_000_000
        val ring = RingBuffer(4096)
        val started = CountDownLatch(2)
        var mismatchAt = -1

        val producer = Thread {
            started.countDown(); started.await()
            val chunk = ByteArray(1157)
            var written = 0L
            val rnd = Random(1)
            while (written < total) {
                val len = minOf(chunk.size, (total - written).toInt())
                for (i in 0 until len) chunk[i] = ((written + i) % 251).toByte()
                var off = 0
                while (off < len) {
                    val n = ring.write(chunk, off, len - off)
                    if (n == 0) { if (rnd.nextBoolean()) Thread.yield(); continue }
                    off += n
                }
                written += len
            }
        }
        val consumer = Thread {
            started.countDown(); started.await()
            val out = ByteArray(997)
            var readCount = 0L
            while (readCount < total) {
                val n = ring.read(out, 0, out.size)
                if (n == 0) { Thread.yield(); continue }
                for (i in 0 until n) {
                    if (out[i] != ((readCount + i) % 251).toByte()) { mismatchAt = (readCount + i).toInt(); return@Thread }
                }
                readCount += n
            }
        }
        producer.start(); consumer.start()
        producer.join(30_000); consumer.join(30_000)

        assertEquals("stream diverged", -1, mismatchAt)
        assertTrue("consumer did not finish", !consumer.isAlive)
        assertEquals("buffer should be drained", 0, ring.available())
    }
}
