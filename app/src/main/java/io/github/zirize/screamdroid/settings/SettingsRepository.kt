package io.github.zirize.screamdroid.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zirize.screamdroid.audio.BufferPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Where the settings live between runs.
 *
 * 🔑 **Nothing here trusts what it reads.** Stored values outlive the code that wrote them - a
 *    preset can be renamed, a port can be typed into a backup - so everything is put through
 *    [Settings]'s own rules on the way out. The receiver should never be handed a port it cannot
 *    bind, or a group address `joinGroup` will throw on, because of something a previous version
 *    stored.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { prefs ->
        val defaults = Settings()
        Settings(
            unicastPort = Settings.sanitizePort(prefs[PORT] ?: defaults.unicastPort),
            multicastPort = Settings.sanitizePort(prefs[MPORT] ?: prefs[PORT] ?: defaults.multicastPort),
            bufferPolicy = prefs[BUFFER]?.let { enumOr(BufferPolicy.entries, it) } ?: defaults.bufferPolicy,
            unicastEnabled = prefs[UNICAST] ?: migratedUnicast(prefs[MODE]) ?: defaults.unicastEnabled,
            multicastGroup = Settings.sanitizeGroup(prefs[GROUP]),
            volumePercent = Settings.sanitizeVolume(prefs[VOLUME] ?: defaults.volumePercent),
            callBehavior = prefs[CALL]?.let { enumOr(CallBehavior.entries, it) } ?: defaults.callBehavior,
            duckOnNotification = prefs[DUCK] ?: defaults.duckOnNotification,
            shareWithOthers = prefs[SHARE] ?: defaults.shareWithOthers,
            lowLatencyWifi = prefs[LOW_LATENCY] ?: defaults.lowLatencyWifi,
            allowMobileData = prefs[MOBILE] ?: defaults.allowMobileData,
            saveBatteryWhenSilent = prefs[SAVE_BATTERY] ?: defaults.saveBatteryWhenSilent,
            guideSeen = prefs[GUIDE_SEEN] ?: defaults.guideSeen,
        )
    }

    suspend fun setUnicastPort(port: Int) {
        store.edit { it[PORT] = Settings.sanitizePort(port) }
    }

    suspend fun setMulticastPort(port: Int) {
        store.edit { it[MPORT] = Settings.sanitizePort(port) }
    }

    suspend fun setBufferPolicy(policy: BufferPolicy) {
        store.edit { it[BUFFER] = policy.name }
    }

    suspend fun setUnicastEnabled(enabled: Boolean) {
        store.edit { it[UNICAST] = enabled }
    }

    suspend fun setMulticastGroup(group: String) {
        store.edit { it[GROUP] = Settings.sanitizeGroup(group) }
    }

    suspend fun setVolume(percent: Int) {
        store.edit { it[VOLUME] = Settings.sanitizeVolume(percent) }
    }

    suspend fun setCallBehavior(behavior: CallBehavior) {
        store.edit { it[CALL] = behavior.name }
    }

    suspend fun setDuckOnNotification(on: Boolean) {
        store.edit { it[DUCK] = on }
    }

    suspend fun setShareWithOthers(on: Boolean) {
        store.edit { it[SHARE] = on }
    }

    suspend fun setLowLatencyWifi(on: Boolean) {
        store.edit { it[LOW_LATENCY] = on }
    }

    suspend fun setAllowMobileData(on: Boolean) {
        store.edit { it[MOBILE] = on }
    }

    suspend fun setSaveBatteryWhenSilent(on: Boolean) {
        store.edit { it[SAVE_BATTERY] = on }
    }

    suspend fun setGuideSeen(seen: Boolean) {
        store.edit { it[GUIDE_SEEN] = seen }
    }

    private companion object {
        val PORT = intPreferencesKey("port")
        val BUFFER = stringPreferencesKey("buffer_policy")
        val MPORT = intPreferencesKey("multicast_port")
        val UNICAST = booleanPreferencesKey("unicast_enabled")

        /**
         * 🔑 **Kept so a phone that has been running since before the two could be had at once
         *    carries its answer over.** Until 2026-09-19 this was one choice of two, stored by
         *    name; multicast is now always on and this only decides the other half. Somebody who
         *    had chosen unicast keeps hearing it, and somebody who had chosen multicast is not
         *    handed a second stream they never asked for.
         *
         * 🔴 Read by the string rather than through an enum, because the enum it named is gone.
         *    A value that is neither - a store written by some later version - is no answer, so it
         *    falls through to the default rather than guessing.
         */
        val MODE = stringPreferencesKey("receive_mode")

        fun migratedUnicast(storedMode: String?): Boolean? = when (storedMode) {
            "UNICAST" -> true
            "MULTICAST" -> false
            else -> null
        }
        val GROUP = stringPreferencesKey("multicast_group")
        val VOLUME = intPreferencesKey("volume_percent")
        val CALL = stringPreferencesKey("call_behavior")
        val DUCK = booleanPreferencesKey("duck_on_notification")
        val SHARE = booleanPreferencesKey("share_with_others")
        val LOW_LATENCY = booleanPreferencesKey("low_latency_wifi")
        val MOBILE = booleanPreferencesKey("allow_mobile_data")
        val SAVE_BATTERY = booleanPreferencesKey("save_battery_when_silent")
        val GUIDE_SEEN = booleanPreferencesKey("guide_seen")

        /** A stored name that no longer exists - a renamed preset, say - falls back to null. */
        fun <T : Enum<T>> enumOr(entries: List<T>, name: String): T? =
            entries.firstOrNull { it.name == name }
    }
}
