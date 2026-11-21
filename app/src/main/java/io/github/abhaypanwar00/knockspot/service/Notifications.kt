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

    fun guard(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_stat_knockspot)
            .setContentTitle("Hotspot protection is on")
            .setContentText("Only trusted devices can join. Others knock.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun showKnock(context: Context, device: Device) {
        if (!canNotify(context)) return
        val id = knockId(device.mac)
        val n = NotificationCompat.Builder(context, CHANNEL_KNOCKS)
            .setSmallIcon(R.drawable.ic_stat_knockspot)
            .setContentTitle("Knock knock")
            .setContentText("${device.displayName} wants to join your hotspot")
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
