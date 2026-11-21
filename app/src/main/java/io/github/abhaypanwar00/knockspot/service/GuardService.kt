package io.github.abhaypanwar00.knockspot.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.abhaypanwar00.knockspot.Guard
import io.github.abhaypanwar00.knockspot.core.Policy
import io.github.abhaypanwar00.knockspot.guard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps Knockspot's process alive while protection is on, so knocks arrive with the app closed.
 *
 * Protection itself does not depend on this service: the allowlist lives in the system hotspot
 * config and is enforced by Android even if Knockspot is killed. This service adds the knock
 * notifications, ends timed guest passes on time, and re-applies the allowlist if the phone's
 * Settings app switches it off.
 */
class GuardService : Service() {

    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, Notifications.GUARD_ID, Notifications.guard(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val guard = guard
        loop = guard.scope.launch {
            while (isActive) {
                if (!guard.currentState().protectionOn) {
                    stopSelf()
                    break
                }
                guard.sync()
                val now = System.currentTimeMillis()
                val next = Policy.nextExpiry(guard.currentState(), now)
                val wait = if (next == null) CHECK_INTERVAL_MS else (next - now + 500).coerceIn(1_000, CHECK_INTERVAL_MS)
                delay(wait)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        loop?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** How often to check that the system still has allowlist mode on. */
        private const val CHECK_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            } catch (e: IllegalStateException) {
                // Android may refuse to start a foreground service from the background. Protection
                // is still enforced by the system; knocks resume next time the app is opened.
                Log.w(Guard.TAG, "Could not start GuardService", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
