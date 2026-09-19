package io.github.zirize.screamdroid.settings

import io.github.zirize.screamdroid.audio.BufferPolicy
import io.github.zirize.screamdroid.audio.Volume
import io.github.zirize.screamdroid.net.ScreamProtocol

/**
 * Everything a person can change, and the rules about what is allowed.
 *
 * 🔑 Kept as plain data with its validation beside it, so "is 70000 a port" can be answered in a
 *    unit test rather than by typing it into a phone.
 */
data class Settings(
    /**
     * The port the sender aims at this phone's own address on.
     *
     * 🔑 Separate from [multicastPort] so the two can be told apart by the port alone. They may
     *    be left equal - the receiver then tells them apart by the address each socket is bound
     *    to - but giving them a port each is the arrangement with nothing to go wrong.
     */
    val unicastPort: Int = ScreamProtocol.DEFAULT_PORT,
    /** The port the group is carried on. Independent of [unicastPort], and equal by default. */
    val multicastPort: Int = ScreamProtocol.DEFAULT_PORT,
    val bufferPolicy: BufferPolicy = BufferPolicy.BALANCED,
    /**
     * Whether packets aimed at this phone's own address are listened for as well.
     *
     * 🔴 **There is no switch for multicast, and that is the point of this pair.** The group
     *    is always joined while listening, because it is the half that survives a change of
     *    network: it is addressed to the group rather than to this phone, so moving between home
     *    and the office does not make it wrong. Unicast is the half that has this phone's address
     *    written into the sender's config, so it is the half worth turning off when that address
     *    no longer means anything.
     *
     * 🔑 Default on, so a phone that was receiving unicast before this existed goes on
     *    receiving it (SettingsRepository migrates the old single-choice setting into this one).
     */
    val unicastEnabled: Boolean = true,
    val multicastGroup: String = ScreamProtocol.DEFAULT_MULTICAST_GROUP,
    val volumePercent: Int = Volume.DEFAULT_PERCENT,
    val callBehavior: CallBehavior = CallBehavior.MUTE,
    val duckOnNotification: Boolean = true,
    /** Play alongside other apps instead of taking the speaker from them. */
    val shareWithOthers: Boolean = false,
    val lowLatencyWifi: Boolean = true,
    val allowMobileData: Boolean = false,
    /**
     * On battery, stop the audio engine while everything is silent.
     *
     * 🔴 **On a charger this is not a choice and there is no setting for it.** Keeping the
     *    engine running is what makes a short sound - a UI click, a notification chime - arrive on
     *    time instead of a tenth of a second late, and plugged in it costs nothing anybody pays.
     *    The only real decision is whether to spend battery on the same thing. See IdlePolicy and
     *    ReceiverService.wantsKeepAwake.
     *
     * 🔑 **Worded as the saving rather than as the keeping awake**, and default on. A switch
     *    reads as "this happens while it is on", so the one that is on by default should be the
     *    one describing what the phone actually does with nothing plugged in - which is to let the
     *    engine stop, and to be a tenth of a second late with the next short sound.
     */
    val saveBatteryWhenSilent: Boolean = true,
    /**
     * Whether the first-run guide has been through once.
     *
     * 🔑 **A setting, not a flag in some other store**, because it is read on the way in and has
     *    to arrive with everything else - two sources would mean two moments, and the guide would
     *    flicker past on a launch where one of them was late.
     */
    val guideSeen: Boolean = false,
) {
    companion object {
        /**
         * Ports below 1024 need privileges no app has, and the range ends at 65535.
         *
         * 🔑 Out-of-range values are **corrected, not rejected**: this is read back from storage
         *    as well as from a text field, and a stored value that has become impossible must
         *    still leave the receiver with a port it can bind.
         */
        fun sanitizePort(port: Int): Int = when {
            port < MIN_PORT -> ScreamProtocol.DEFAULT_PORT
            port > MAX_PORT -> ScreamProtocol.DEFAULT_PORT
            else -> port
        }

        /** Parse what somebody typed. Null means "not a usable port", so the field can say so. */
        fun parsePort(text: String): Int? =
            text.trim().toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }

        /**
         * Parse a multicast group address, or null if it is not one.
         *
         * 🔴 **Only 224.0.0.0/4 is a group.** `joinGroup` throws on anything else, and it would
         *    throw on the receive thread - a typo in a text field must not be able to stop the
         *    audio, so it is rejected while it is still text.
         */
        fun parseGroup(text: String): String? {
            val parts = text.trim().split('.')
            if (parts.size != 4) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            if (octets.any { it !in 0..255 }) return null
            if (octets[0] !in MIN_GROUP_OCTET..MAX_GROUP_OCTET) return null
            return octets.joinToString(".")
        }

        /** As [parseGroup], but for a value read back from storage: anything odd becomes default. */
        fun sanitizeGroup(text: String?): String =
            text?.let { parseGroup(it) } ?: ScreamProtocol.DEFAULT_MULTICAST_GROUP

        fun sanitizeVolume(percent: Int): Int = percent.coerceIn(0, 100)

        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** 224.0.0.0 – 239.255.255.255, the whole of the IPv4 multicast range. */
        const val MIN_GROUP_OCTET = 224
        const val MAX_GROUP_OCTET = 239
    }
}
