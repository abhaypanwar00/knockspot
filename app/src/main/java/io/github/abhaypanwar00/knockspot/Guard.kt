package io.github.abhaypanwar00.knockspot

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.abhaypanwar00.knockspot.core.Decision
import io.github.abhaypanwar00.knockspot.core.KnockspotState
import io.github.abhaypanwar00.knockspot.core.Policy
import io.github.abhaypanwar00.knockspot.data.DeviceStore
import io.github.abhaypanwar00.knockspot.service.GuardService
import io.github.abhaypanwar00.knockspot.service.Notifications
import io.github.abhaypanwar00.knockspot.system.HotspotController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

enum class ShizukuStatus { NOT_RUNNING, NO_PERMISSION, READY }

/** Things that are only known while the app runs (not persisted). */
data class LiveStatus(
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val hotspotOn: Boolean? = null,
    val connected: List<String> = emptyList(),
    val allowlistSupported: Boolean? = null,
    /** Allowlist mode as last read back from the system. */
    val systemAllowlistMode: Boolean? = null,
    val lastError: String? = null,
)

/**
 * App-wide coordinator. Every change follows the same loop:
 * read the system → run [Policy] → persist → write the system → record what was written.
 */
class Guard(context: Context) {

    private val app = context.applicationContext
    val store = DeviceStore(app)
    private val controller = HotspotController(app)
    private val mutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    private val permissionRequestCode = 4242

    init {
        Shizuku.addBinderReceivedListenerSticky {
            refreshShizuku()
            scope.launch { sync() }
        }
        Shizuku.addBinderDeadListener {
            controller.reset()
            refreshShizuku()
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == permissionRequestCode) {
                refreshShizuku()
                if (result == PackageManager.PERMISSION_GRANTED) scope.launch { sync() }
            }
        }
        refreshShizuku()
    }

    // ---------------------------------------------------------------- Shizuku

    fun refreshShizuku(): ShizukuStatus {
        val status = when {
            !Shizuku.pingBinder() -> ShizukuStatus.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuStatus.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
            else -> ShizukuStatus.READY
        }
        _live.update { it.copy(shizuku = status) }
        return status
    }

    fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(permissionRequestCode)
    }

    private fun ensureConnected(): Boolean {
        if (refreshShizuku() != ShizukuStatus.READY) return false
        if (!controller.isConnected) {
            controller.connect()
            controller.listen(listener)
        }
        return true
    }

    // ---------------------------------------------------------------- the sync loop

    /** Brings the system hotspot config in line with Knockspot's state. Returns false on failure. */
    suspend fun sync(): Boolean = mutex.withLock {
        try {
            if (!ensureConnected()) return@withLock false
            val now = System.currentTimeMillis()
            val system = controller.read()
            val state = store.update {
                Policy.expireGuests(Policy.syncFromSystem(Policy.adoptExisting(it, system), system.blocked), now)
            }
            val want = Policy.desiredLists(state, now)
            val result = controller.apply(want)
            store.update { Policy.recordApplied(it, result.blocked) }
            _live.update { it.copy(systemAllowlistMode = result.allowlistMode, lastError = null) }
            if (want.allowlistMode && !result.allowlistMode) {
                _live.update {
                    it.copy(lastError = "Your phone switched allowlist mode back off. It may not support it.")
                }
            }
            true
        } catch (t: Throwable) {
            val root = HotspotController.unwrap(t)
            Log.w(TAG, "Sync failed", root)
            _live.update { it.copy(lastError = root.message ?: root.javaClass.simpleName) }
            if (root is SecurityException || root is IllegalStateException) controller.reset()
            false
        }
    }

    // ---------------------------------------------------------------- user actions

    /** Turns protection on or off. Reverts and returns false if the system could not be updated. */
    suspend fun setProtection(on: Boolean): Boolean {
        val before = store.current()
        if (on) {
            // The first enable trusts whatever is connected, so make sure we have heard the
            // connected-clients event (sent right after the callback is registered).
            val fresh = mutex.withLock {
                val was = controller.isConnected
                runCatching { ensureConnected() }.getOrDefault(false) && !was
            }
            if (fresh) delay(1_500)
        }
        val connected = _live.value.connected
        val now = System.currentTimeMillis()
        store.update { if (on) Policy.enableProtection(it, connected, now) else Policy.disableProtection(it) }
        val ok = sync()
        if (!ok) {
            store.update { it.copy(protectionOn = before.protectionOn) }
            return false
        }
        if (on) GuardService.start(app) else GuardService.stop(app)
        return true
    }

    suspend fun decide(mac: String, decision: Decision) {
        store.update { Policy.decide(it, mac, decision, System.currentTimeMillis()) }
        Notifications.cancelKnock(app, mac)
        sync()
    }

    suspend fun rename(mac: String, name: String?) {
        store.update { Policy.rename(it, mac, name) }
    }

    suspend fun currentState(): KnockspotState = store.current()

    // ---------------------------------------------------------------- hotspot events

    private val listener = object : HotspotController.Listener {
        override fun onHotspotState(enabled: Boolean) {
            _live.update { it.copy(hotspotOn = enabled) }
            scope.launch {
                if (!enabled) store.update(Policy::onHotspotStopped)
                sync()
            }
        }

        override fun onConnectedClients(macs: List<String>) {
            _live.update { it.copy(connected = macs) }
            if (macs.isNotEmpty()) scope.launch {
                store.update { Policy.markSeen(it, macs, System.currentTimeMillis()) }
            }
        }

        override fun onKnock(mac: String, reason: HotspotController.KnockReason) {
            if (reason != HotspotController.KnockReason.NOT_ALLOWED) {
                Log.i(TAG, "Device $mac turned away: $reason")
                return
            }
            scope.launch {
                var notify = false
                val state = store.update {
                    val out = Policy.onKnock(it, mac, System.currentTimeMillis())
                    notify = out.notify
                    out.state
                }
                if (notify) state.device(mac)?.let { Notifications.showKnock(app, it) }
            }
        }

        override fun onCapability(allowlistSupported: Boolean?, maxClients: Int?) {
            _live.update { it.copy(allowlistSupported = allowlistSupported) }
        }
    }

    companion object {
        const val TAG = "Knockspot"
    }
}
