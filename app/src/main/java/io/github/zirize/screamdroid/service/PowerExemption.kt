package io.github.zirize.screamdroid.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Whether the system has been told to leave this app alone while the phone is idle.
 *
 * 🔴 **Without it the process is not slowed down, it is frozen.** Measured on the device,
 *    2026-09-19: with the screen off, unplugged, and no audio arriving, the phone enters idle,
 *    the service's `PARTIAL_WAKE_LOCK` is listed as `DISABLED`, and the process lands in the
 *    kernel's frozen cgroup. The socket still receives - the kernel counts every packet straight
 *    into `drops`, with the queue empty, because the thread that would read it cannot run. Sound
 *    never comes back on its own, and no log line is written either, because the thread that
 *    would write one is frozen too.
 *
 * 🔑 **Silence suppression is what exposed this**, so it arrived with a feature working rather
 *    than breaking: a sender that stops transmitting through the quiet leaves the phone nothing
 *    to do, which is exactly the condition for going idle. A stream that never stops kept the
 *    phone busy and hid it. It is also why a charger hides it - a plugged-in phone does not idle.
 *
 * 🚫 **This cannot be fixed in code.** A foreground service, a wake lock and a Wi-Fi lock are all
 *    held already; the exemption is the user's to grant, so all the app can do is notice, say so
 *    plainly, and open the page that grants it. See docs/architecture.md.
 */
object PowerExemption {

    /**
     * 🔑 **Unknown counts as exempt.** The only use of this is whether to warn, and a warning that
     *    cannot be acted on - or that is wrong - is worse than the silence it describes.
     */
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /**
     * Opens where the exemption is granted, and says whether anything opened.
     *
     * 🔑 **This app's own page first, the global list second.** Asking for the exemption directly
     *    needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, a restricted permission that has to be
     *    justified to the store; the app's details page reaches the same switch in one more tap
     *    and costs nothing. On the phones that hide it there, the battery optimisation list is
     *    the fallback.
     */
    fun open(context: Context): Boolean {
        val own = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(own, list)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "could not open ${intent.action}", it) }
                .isSuccess
            if (opened) return true
        }
        return false
    }

    private const val TAG = "PowerExemption"
}
