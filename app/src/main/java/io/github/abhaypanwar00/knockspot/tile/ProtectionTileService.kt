package io.github.abhaypanwar00.knockspot.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.abhaypanwar00.knockspot.ShizukuStatus
import io.github.abhaypanwar00.knockspot.guard
import io.github.abhaypanwar00.knockspot.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick Settings tile: one tap switches hotspot protection on or off. */
class ProtectionTileService : TileService() {

    private var job: Job? = null

    override fun onStartListening() {
        job?.cancel()
        job = guard.scope.launch {
            guard.store.state.collect { s -> withContext(Dispatchers.Main) { render(s.protectionOn, busy = false) } }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    override fun onClick() {
        val g = guard
        if (g.refreshShizuku() != ShizukuStatus.READY) {
            openApp()
            return
        }
        g.scope.launch {
            val target = !g.currentState().protectionOn
            withContext(Dispatchers.Main) { render(target, busy = true) }
            val ok = g.setProtection(target)
            if (!ok) withContext(Dispatchers.Main) { openApp() }
        }
    }

    private fun render(on: Boolean, busy: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Knockspot"
        tile.subtitle = when {
            busy -> "Applying…"
            on -> "Protected"
            else -> "Open"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
