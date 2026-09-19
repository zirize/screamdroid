package io.github.zirize.screamdroid.audio

/**
 * How much latency to hold, and when to start giving it back.
 *
 * 🔑 **Three presets rather than two numbers to type.** The pair only makes sense together - a
 *    ceiling below the target is nonsense, and a target near the ceiling leaves nothing to absorb
 *    jitter with - so the choice offered is the trade-off itself, not its parameters.
 *
 * ℹ️ **What these can actually move is narrower than it looks.** Most of the latency lives inside
 *    `AudioTrack`, whose buffer has a device minimum that no setting here can go below (measured
 *    on the test phone: 77 ms of 83 ms). These numbers govern the part above that floor, and the
 *    ceiling at which the receiver starts trimming.
 */
enum class BufferPolicy(val targetMs: Int, val maxMs: Int) {
    /** Least delay, least tolerance for a stall. */
    LOW_LATENCY(30, 80),

    /** What the receiver uses unless told otherwise. */
    BALANCED(60, 150),

    /** Rides out a bad wireless patch; you hear it later. */
    STABLE(120, 300),
}
