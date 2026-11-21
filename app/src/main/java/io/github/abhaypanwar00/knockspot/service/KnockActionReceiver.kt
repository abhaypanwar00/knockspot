package io.github.abhaypanwar00.knockspot.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.abhaypanwar00.knockspot.core.Decision
import io.github.abhaypanwar00.knockspot.guard
import kotlinx.coroutines.launch

/** Handles notification buttons: decisions about a device, and opening/closing the door. */
class KnockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val guard = context.guard
        val action: suspend () -> Unit = when (intent.action) {
            OPEN_DOOR -> { { guard.openDoor() } }
            CLOSE_DOOR -> { { guard.closeDoor() } }
            else -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: return
                val decision = when (intent.action) {
                    ALLOW_ONCE -> Decision.AllowUntilHotspotOff
                    ALWAYS -> Decision.AlwaysAllow
                    BLOCK -> Decision.Block
                    else -> return
                }
                { guard.decide(mac, decision) }
            }
        }
        val pending = goAsync()
        guard.scope.launch {
            try {
                action()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ALLOW_ONCE = "io.github.abhaypanwar00.knockspot.ALLOW_ONCE"
        const val ALWAYS = "io.github.abhaypanwar00.knockspot.ALWAYS"
        const val BLOCK = "io.github.abhaypanwar00.knockspot.BLOCK"
        const val OPEN_DOOR = "io.github.abhaypanwar00.knockspot.OPEN_DOOR"
        const val CLOSE_DOOR = "io.github.abhaypanwar00.knockspot.CLOSE_DOOR"
        private const val EXTRA_MAC = "mac"

        fun intent(context: Context, mac: String?, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            (mac.orEmpty() + action).hashCode(),
            Intent(context, KnockActionReceiver::class.java).setAction(action).putExtra(EXTRA_MAC, mac),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
