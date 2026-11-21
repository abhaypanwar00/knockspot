package io.github.abhaypanwar00.knockspot.system

import android.annotation.SuppressLint
import android.content.Context
import android.net.MacAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.abhaypanwar00.knockspot.core.Policy
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.function.Supplier

/**
 * Reports the name each hotspot client advertised when it got its IP address (DHCP hostname) —
 * the same names the phone's own hotspot settings show, like "Macbook".
 *
 * Uses TetheringManager.TetheringEventCallback#onClientsChanged. The tethering service only
 * includes client details for callers holding NETWORK_SETTINGS, so the TetheringManager is built
 * on a Shizuku-wrapped binder and every call runs as the shell user.
 */
@SuppressLint("PrivateApi")
class TetheringWatcher(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var callback: Any? = null

    /** Registers once; [onClients] receives MAC → hostname for clients that reported one. */
    @Synchronized
    fun start(onClients: (Map<String, String>) -> Unit) {
        if (callback != null) return
        val raw = rawTetheringBinder() ?: error("Tethering service not found")

        val managerClass = Class.forName("android.net.TetheringManager")
        val manager = managerClass.getConstructor(Context::class.java, Supplier::class.java)
            .newInstance(context.applicationContext, Supplier<IBinder> { ShizukuBinderWrapper(raw) })

        val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        val proxy = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.getOrNull(0)
                "toString" -> "KnockspotTetheringCallback"
                "onClientsChanged" -> {
                    runCatching { onClients(hostnames(args?.getOrNull(0) as? Collection<*>)) }
                        .onFailure { Log.w(TAG, "Could not read tethered clients", it) }
                    null
                }
                else -> null
            }
        }
        managerClass.getMethod("registerTetheringEventCallback", Executor::class.java, callbackClass)
            .invoke(manager, Executor { main.post(it) }, proxy)
        callback = proxy
    }

    private fun hostnames(clients: Collection<*>?): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (client in clients.orEmpty()) {
            if (client == null) continue
            val c = client.javaClass
            val mac = (c.getMethod("getMacAddress").invoke(client) as? MacAddress)?.toString() ?: continue
            val addresses = c.getMethod("getAddresses").invoke(client) as? List<*> ?: continue
            val name = addresses.firstNotNullOfOrNull { a ->
                a?.let { it.javaClass.getMethod("getHostname").invoke(it) as? String }?.takeIf { it.isNotBlank() }
            } ?: continue
            out[Policy.normalize(mac)] = name
        }
        return out
    }

    /** The tethering service's binder: from ServiceManager, else from this app's own TetheringManager. */
    private fun rawTetheringBinder(): IBinder? {
        runCatching { SystemServiceHelper.getSystemService(TETHERING) }.getOrNull()?.let { return it }
        return runCatching {
            val local = context.getSystemService(TETHERING) ?: return null
            val field = local.javaClass.getDeclaredField("mConnector").apply { isAccessible = true }
            val connector = field.get(local) ?: return null
            connector.javaClass.getMethod("asBinder").invoke(connector) as IBinder
        }.getOrNull()
    }

    private companion object {
        const val TAG = "Knockspot"
        const val TETHERING = "tethering"
    }
}
