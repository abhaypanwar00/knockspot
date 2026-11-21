package io.github.abhaypanwar00.knockspot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.abhaypanwar00.knockspot.R
import io.github.abhaypanwar00.knockspot.core.Device
import io.github.abhaypanwar00.knockspot.ui.MainActivity

object Notifications {

    private const val CHANNEL_GUARD = "guard"
    private const val CHANNEL_KNOCKS = "knocks"
    const val GUARD_ID = 1
    private const val LIMIT_ID = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, "Protection status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Knockspot is listening for knocks"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KNOCKS, "Knocks", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A device that is not on your allowlist is trying to join your hotspot"
            },
        )
    }

    /** The ongoing notification. [doorOpenUntil] non-null while "Let someone in" is open. */
    fun guard(context: Context, doorOpenUntil: Long? = null): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_stat_knockspot)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (doorOpenUntil != null) {
            b.setContentTitle("Letting someone in")
                .setContentText("The next new device to connect joins as a guest")
                .setWhen(doorOpenUntil).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
                .addAction(0, "Close", KnockActionReceiver.intent(context, null, KnockActionReceiver.CLOSE_DOOR))
        } else {
            b.setContentTitle("Hotspot protection is on")
                .setContentText("Only trusted devices can join. Others knock.")
                .setShowWhen(false)
                .addAction(0, "Let someone in", KnockActionReceiver.intent(context, null, KnockActionReceiver.OPEN_DOOR))
        }
        return b.build()
    }

    fun updateGuard(context: Context, doorOpenUntil: Long?) {
        if (!canNotify(context)) return
        context.getSystemService(NotificationManager::class.java).notify(GUARD_ID, guard(context, doorOpenUntil))
    }

    fun showKnock(context: Context, device: Device) {
        if (!canNotify(context)) return
        val id = knockId(device.mac)
        val n = NotificationCompat.Builder(context, CHANNEL_KNOCKS)
            .setSmallIcon(R.drawable.ic_stat_knockspot)
            .setContentTitle("Knock knock")
            .setContentText("${device.displayName} tried to join your hotspot")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${device.displayName} tried to join your hotspot. If you allow it, it has to connect once " +
                        "more. Tip: tap “Let someone in” before they connect, and they get in on the first try.",
                ),
            )
            .setSubText(device.mac)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Allow once", KnockActionReceiver.intent(context, device.mac, KnockActionReceiver.ALLOW_ONCE))
            .addAction(0, "Always allow", KnockActionReceiver.intent(context, device.mac, KnockActionReceiver.ALWAYS))
            .addAction(0, "Block", KnockActionReceiver.intent(context, device.mac, KnockActionReceiver.BLOCK))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** A device came in through "Let someone in". */
    fun showJoined(context: Context, device: Device) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_KNOCKS)
            .setSmallIcon(R.drawable.ic_stat_knockspot)
            .setContentTitle("${device.displayName} joined")
            .setContentText("Guest until the hotspot turns off")
            .setSubText(device.mac)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Always allow", KnockActionReceiver.intent(context, device.mac, KnockActionReceiver.ALWAYS))
            .addAction(0, "Block", KnockActionReceiver.intent(context, device.mac, KnockActionReceiver.BLOCK))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(knockId(device.mac), n)
    }

    /** The hotspot's own device limit turned someone away. */
    fun showLimitReached(context: Context) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_KNOCKS)
            .setSmallIcon(R.drawable.ic_stat_knockspot)
            .setContentTitle("Hotspot is full")
            .setContentText("A device couldn't join: your hotspot's device limit is reached")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A device couldn't join because your hotspot's maximum number of connections is reached. " +
                        "With Knockspot deciding who gets in, you can raise the limit in your phone's hotspot " +
                        "settings (Connection management → Maximum connections).",
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(LIMIT_ID, n)
    }

    fun cancelKnock(context: Context, mac: String) {
        context.getSystemService(NotificationManager::class.java).cancel(knockId(mac))
    }

    /** Covers both the Android 13+ runtime permission and notifications switched off in Settings. */
    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun knockId(mac: String) = 1000 + (mac.hashCode() and 0x7fff)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
