package io.github.abhaypanwar00.knockspot.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.abhaypanwar00.knockspot.core.Decision
import io.github.abhaypanwar00.knockspot.guard
import kotlinx.coroutines.launch

/** Handles the Allow once / Always allow / Block buttons on a knock notification. */
class KnockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mac = intent.getStringExtra(EXTRA_MAC) ?: return
        val decision = when (intent.action) {
            ALLOW_ONCE -> Decision.AllowUntilHotspotOff
            ALWAYS -> Decision.AlwaysAllow
            BLOCK -> Decision.Block
            else -> return
        }
        val pending = goAsync()
        val guard = context.guard
        guard.scope.launch {
            try {
                guard.decide(mac, decision)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ALLOW_ONCE = "io.github.abhaypanwar00.knockspot.ALLOW_ONCE"
        const val ALWAYS = "io.github.abhaypanwar00.knockspot.ALWAYS"
        const val BLOCK = "io.github.abhaypanwar00.knockspot.BLOCK"
        private const val EXTRA_MAC = "mac"

        fun intent(context: Context, mac: String, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            (mac + action).hashCode(),
            Intent(context, KnockActionReceiver::class.java).setAction(action).putExtra(EXTRA_MAC, mac),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
