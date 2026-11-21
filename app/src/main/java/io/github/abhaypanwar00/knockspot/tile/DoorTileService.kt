package io.github.abhaypanwar00.knockspot.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.abhaypanwar00.knockspot.ShizukuStatus
import io.github.abhaypanwar00.knockspot.core.KnockspotState
import io.github.abhaypanwar00.knockspot.core.Policy
import io.github.abhaypanwar00.knockspot.guard
import io.github.abhaypanwar00.knockspot.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile for the moment someone asks for your hotspot: tap it, they connect,
 * and they're in as a guest on the first try.
 */
class DoorTileService : TileService() {

    private var job: Job? = null

    override fun onStartListening() {
        job?.cancel()
        job = guard.scope.launch {
            guard.store.state.collect { s -> withContext(Dispatchers.Main) { render(s) } }
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
            val s = g.currentState()
            when {
                !s.protectionOn -> withContext(Dispatchers.Main) { openApp() }
                Policy.isDoorOpen(s, System.currentTimeMillis()) -> g.closeDoor()
                !g.openDoor() -> withContext(Dispatchers.Main) { openApp() }
            }
        }
    }

    private fun render(s: KnockspotState) {
        val tile = qsTile ?: return
        val open = Policy.isDoorOpen(s, System.currentTimeMillis())
        tile.label = "Let someone in"
        tile.state = when {
            !s.protectionOn -> Tile.STATE_UNAVAILABLE
            open -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !s.protectionOn -> "Protection off"
            open -> "Open for 2 min"
            else -> "Tap, then connect"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
