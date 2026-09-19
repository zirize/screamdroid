package io.github.zirize.screamdroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.zirize.screamdroid.MainActivity
import io.github.zirize.screamdroid.R
import io.github.zirize.screamdroid.net.ScreamReceiver

/**
 * The one thing the app shows while nobody is looking at it.
 *
 * 🔑 **It is the control surface, not a status line.** A receiver that runs for hours is operated
 *    from here far more often than from the app, so the two things a person actually wants -
 *    silence it, stop it - are buttons, and the line underneath answers "is it working" without
 *    opening anything.
 * 🔑 **"Waiting" names both causes.** Silence suppression means a quiet PC and a misaimed sender
 *    look identical from here (docs/protocol.md), so the text says both rather than guessing.
 */
object ReceiverNotification {

    const val CHANNEL_ID = "receiver"

    /**
     * A channel that was tried and did not work, deleted on sight so it does not sit in the app's
     * notification settings as a dead entry. See [ensureChannel].
     */
    private const val ABANDONED_CHANNEL_ID = "receiver-quiet"

    const val ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_receiver),
                // 🔑 LOW: it must be visible for as long as the service runs, and it must never
                //    make a sound or push itself in front of anything. It is furniture, not an
                //    alert.
                // 🚫 **MIN does not work, and the platform will not let it.** Asking for MIN to
                //    keep the status bar clean was tried on 2026-09-18 and the system silently
                //    raised it: `mOriginalImp=1, mImportance=2, mFgServiceShown=true` in
                //    `dumpsys notification`. A channel carrying a foreground-service notification
                //    is held at LOW or above so that "this app is running" is always visible -
                //    and a new channel id does not get around it, because the raise happens when
                //    the notification is posted, not when the channel is made.
                //    ⇒ **The icon is the system's to show, not ours to hide.** Somebody who does
                //    not want it turns the channel off in the phone's own notification settings.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_receiver_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
        runCatching { manager.deleteNotificationChannel(ABANDONED_CHANNEL_ID) }
    }

    fun build(context: Context, snapshot: ReceiverSnapshot): Notification {
        val playing = snapshot.state == ScreamReceiver.State.PLAYING
        val title = when {
            snapshot.error != null -> context.getString(R.string.notification_error)
            snapshot.blocked != Blocked.NONE -> context.getString(R.string.notification_blocked)
            // 🔑 A mute that happened by itself says so, because the useful sentence is "you do
            //    not have to do anything" - and that is only true for this one.
            snapshot.pauseCause == PauseCause.CALL -> context.getString(R.string.notification_call)
            snapshot.pauseCause == PauseCause.FOCUS -> context.getString(R.string.notification_focus)
            snapshot.paused -> context.getString(R.string.notification_paused)
            playing -> snapshot.sender
                ?.let { context.getString(R.string.notification_receiving_from, it) }
                ?: context.getString(R.string.state_playing)
            else -> context.getString(R.string.notification_waiting)
        }
        val text = when {
            snapshot.error != null -> snapshot.error
            // 🔑 Being refused is the one state here the person can actually do something about,
            //    so it says which switch to go and find rather than "no audio".
            snapshot.blocked == Blocked.MOBILE_DATA ->
                context.getString(R.string.blocked_mobile_data)
            // 🔑 Paused is a thing the person did, so it is explained as one. Telling them the PC
            //    might be quiet would be answering a question they did not ask - and it would hide
            //    the part that matters: packets are still being taken off the socket and thrown
            //    away, which is why resuming is instant instead of replaying the gap.
            snapshot.interrupted -> context.getString(R.string.interrupted_reason)
            snapshot.paused -> context.getString(R.string.paused_reason)
            playing -> snapshot.format?.let {
                context.getString(
                    R.string.notification_format,
                    it.sampleRate / 1000,
                    it.bitsPerSample,
                    it.channels,
                    snapshot.latencyMs,
                )
            }
            else -> context.getString(R.string.waiting_reason)
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                if (snapshot.paused) {
                    action(context, R.string.action_resume, ReceiverService.ACTION_RESUME)
                } else {
                    action(context, R.string.action_pause, ReceiverService.ACTION_PAUSE)
                }
            )
            // 🔑 Three buttons is the most a shade will show, and these are the three things
            //    somebody reaches for without opening the app: silence it, let other apps share
            //    the speaker with it, stop it.
            .addAction(
                if (snapshot.settings.shareWithOthers) {
                    action(context, R.string.action_unshare, ReceiverService.ACTION_TOGGLE_SHARE)
                } else {
                    action(context, R.string.action_share, ReceiverService.ACTION_TOGGLE_SHARE)
                }
            )
            .addAction(action(context, R.string.action_turn_off, ReceiverService.ACTION_STOP))
            .build()
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(context: Context, label: Int, action: String): Notification.Action {
        val intent = Intent(context, ReceiverService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 🔑 The icon is required by the API and ignored by every launcher since Nougat, which
        //    draws these as text buttons. Reusing the notification icon keeps it from inventing a
        //    second glyph nobody will see.
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification),
            context.getString(label),
            pending,
        ).build()
    }
}
