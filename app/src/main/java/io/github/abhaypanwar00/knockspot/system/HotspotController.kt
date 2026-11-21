package io.github.abhaypanwar00.knockspot.system

import android.annotation.SuppressLint
import android.content.Context
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.abhaypanwar00.knockspot.core.Policy
import io.github.abhaypanwar00.knockspot.core.SystemLists
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Reads and writes the system hotspot configuration with shell (ADB) privileges via Shizuku,
 * and listens to hotspot events.
 *
 * The classes involved are @SystemApi / @hide, so everything goes through reflection:
 *  - IWifiManager (binder interface of the "wifi" system service)
 *  - SoftApConfiguration + Builder (allowlist mode, allowed list, blocked list)
 *  - WifiManager.SoftApCallback (state, connected clients, and onBlockedClientConnecting)
 *
 * The shell user holds NETWORK_SETTINGS, which all of these require.
 */
@SuppressLint("PrivateApi")
class HotspotController(private val context: Context) {

    enum class KnockReason { NOT_ALLOWED, LIMIT_REACHED, OTHER }

    interface Listener {
        fun onHotspotState(enabled: Boolean)
        fun onConnectedClients(macs: List<String>)
        fun onKnock(mac: String, reason: KnockReason)
        fun onCapability(allowlistSupported: Boolean?, maxClients: Int?)
    }

    companion object {
        private const val TAG = "Knockspot"

        /** Calls that ask "who is calling" must name the package of the shell UID. */
        private const val SHELL_PACKAGE = "com.android.shell"

        // WifiManager.WIFI_AP_STATE_*
        private const val AP_ENABLED = 13
        private const val AP_DISABLED = 11
        private const val AP_FAILED = 14

        fun exemptHiddenApis() {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }

        fun unwrap(t: Throwable): Throwable =
            if (t is InvocationTargetException && t.targetException != null) unwrap(t.targetException) else t
    }

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }

    private val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
    private val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
    private val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")

    private var service: Any? = null
    private var wifiManager: WifiManager? = null
    private var callback: Any? = null
    private var listener: Listener? = null
    private var connected: List<String> = emptyList()

    val isConnected: Boolean get() = service != null

    // ---------------------------------------------------------------- connection

    /** Binds to the wifi service through Shizuku. Safe to call repeatedly. */
    @Synchronized
    fun connect() {
        if (service != null) return
        val raw: IBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
            ?: error("Wi-Fi system service not found")
        val stub = Class.forName("android.net.wifi.IWifiManager\$Stub")
        val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ShizukuBinderWrapper(raw))
            ?: error("Could not open the Wi-Fi service")

        // A WifiManager whose internal binder is the Shizuku-wrapped one. WifiManager's own
        // callback plumbing then works while every call runs as the shell user.
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val iwm = Class.forName("android.net.wifi.IWifiManager")
        val field = generateSequence<Class<*>>(wm.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { iwm.isAssignableFrom(it.type) }
        field.isAccessible = true
        field.set(wm, svc)

        service = svc
        wifiManager = wm
    }

    /**
     * Call when Shizuku goes away; the next [connect] starts fresh. The event callback is kept:
     * the system holds it directly, so it keeps working (and must not be registered twice).
     */
    @Synchronized
    fun reset() {
        service = null
        wifiManager = null
    }

    // ---------------------------------------------------------------- configuration

    fun read(): SystemLists {
        val cfg = readConfig()
        return SystemLists(
            allowlistMode = configClass.getMethod("isClientControlByUserEnabled").invoke(cfg) as Boolean,
            allowed = macList(cfg, "getAllowedClientList"),
            blocked = macList(cfg, "getBlockedClientList"),
        )
    }

    /**
     * Writes [want] to the system hotspot configuration if it differs from what is there.
     * Returns the configuration as read back afterwards; the system may silently refuse
     * allowlist mode on hardware that does not support it.
     */
    fun apply(want: SystemLists): SystemLists {
        val current = readConfig()
        val have = SystemLists(
            allowlistMode = configClass.getMethod("isClientControlByUserEnabled").invoke(current) as Boolean,
            allowed = macList(current, "getAllowedClientList"),
            blocked = macList(current, "getBlockedClientList"),
        )
        if (have == want) return have

        val builder = builderClass.getConstructor(configClass).newInstance(current)
        builderClass.getMethod("setAllowedClientList", List::class.java)
            .invoke(builder, want.allowed.map(MacAddress::fromString))
        builderClass.getMethod("setBlockedClientList", List::class.java)
            .invoke(builder, want.blocked.map(MacAddress::fromString))
        builderClass.getMethod("setClientControlByUserEnabled", Boolean::class.javaPrimitiveType)
            .invoke(builder, want.allowlistMode)
        val updated = builderClass.getMethod("build").invoke(builder)!!

        val svc = requireService()
        val set = svc.javaClass.methods.first { it.name == "setSoftApConfiguration" }
        val args = set.parameterTypes.map { type ->
            when {
                type == String::class.java -> SHELL_PACKAGE
                configClass.isAssignableFrom(type) -> updated
                else -> error("Unexpected parameter ${type.name} in setSoftApConfiguration")
            }
        }.toTypedArray()
        val accepted = set.invoke(svc, *args)
        if (accepted == false) error("The system rejected the hotspot configuration")
        Log.i(TAG, "Applied hotspot config: $want")
        return read()
    }

    private fun readConfig(): Any {
        val svc = requireService()
        val get = svc.javaClass.methods.first { it.name == "getSoftApConfiguration" && it.parameterCount == 0 }
        return get.invoke(svc) ?: error("Hotspot configuration unavailable")
    }

    private fun macList(cfg: Any, getter: String): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val list = configClass.getMethod(getter).invoke(cfg) as List<MacAddress>
        return list.mapTo(sortedSetOf()) { Policy.normalize(it.toString()) }
    }

    private fun requireService(): Any = service ?: error("Not connected to Shizuku")

    // ---------------------------------------------------------------- events

    @Synchronized
    fun listen(l: Listener) {
        listener = l
        if (callback != null) return
        val wm = wifiManager ?: error("Not connected to Shizuku")
        val proxy = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.getOrNull(0)
                "toString" -> "KnockspotSoftApCallback"
                else -> {
                    try {
                        onEvent(method.name, args ?: emptyArray())
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error handling ${method.name}", unwrap(t))
                    }
                    null
                }
            }
        }
        WifiManager::class.java.getMethod("registerSoftApCallback", Executor::class.java, callbackClass)
            .invoke(wm, mainExecutor, proxy)
        callback = proxy
    }

    private fun onEvent(name: String, args: Array<out Any?>) {
        val l = listener ?: return
        when (name) {
            "onStateChanged" -> {
                val state = when (val a = args.getOrNull(0)) {
                    is Int -> a
                    null -> return
                    else -> runCatching { a.javaClass.getMethod("getState").invoke(a) as Int }
                        .getOrElse { Regex("mState=(\\d+)").find(a.toString())?.groupValues?.get(1)?.toInt() ?: return }
                }
                when (state) {
                    AP_ENABLED -> l.onHotspotState(true)
                    AP_DISABLED, AP_FAILED -> {
                        connected = emptyList()
                        l.onConnectedClients(connected)
                        l.onHotspotState(false)
                    }
                }
            }
            "onConnectedClientsChanged" -> {
                val list = args.filterIsInstance<List<*>>().firstOrNull().orEmpty()
                val macs = list.mapNotNull { macOf(it) }
                // (clients) is the full list across bands; (info, clients) covers one band.
                connected = if (args.size == 1) macs else (connected + macs).distinct()
                l.onConnectedClients(connected)
            }
            "onBlockedClientConnecting" -> {
                val mac = macOf(args.getOrNull(0)) ?: return
                val reason = when (args.getOrNull(1) as? Int) {
                    0 -> KnockReason.NOT_ALLOWED   // SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER
                    1 -> KnockReason.LIMIT_REACHED // SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS
                    else -> KnockReason.OTHER
                }
                l.onKnock(mac, reason)
            }
            "onCapabilityChanged" -> {
                val cap = args.getOrNull(0) ?: return
                val c = cap.javaClass
                val feature = runCatching { c.getField("SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT").getLong(null) }
                    .getOrDefault(2L)
                val supported = runCatching {
                    c.getMethod("areFeaturesSupported", Long::class.javaPrimitiveType).invoke(cap, feature) as Boolean
                }.getOrNull()
                val max = runCatching { c.getMethod("getMaxSupportedClients").invoke(cap) as Int }.getOrNull()
                l.onCapability(supported, max)
            }
        }
    }

    private fun macOf(client: Any?): String? = client?.let {
        runCatching { Policy.normalize((it.javaClass.getMethod("getMacAddress").invoke(it) as MacAddress).toString()) }
            .getOrNull()
    }
}
