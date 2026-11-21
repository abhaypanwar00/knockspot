package io.github.abhaypanwar00.knockspot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.res.painterResource
import io.github.abhaypanwar00.knockspot.R
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.abhaypanwar00.knockspot.Guard
import io.github.abhaypanwar00.knockspot.ShizukuStatus
import io.github.abhaypanwar00.knockspot.core.Access
import io.github.abhaypanwar00.knockspot.core.Decision
import io.github.abhaypanwar00.knockspot.core.Device
import io.github.abhaypanwar00.knockspot.core.KnockspotState
import io.github.abhaypanwar00.knockspot.core.Policy
import io.github.abhaypanwar00.knockspot.service.Notifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val HOUR_MS = 60 * 60 * 1000L
private const val RECENT_KNOCK_MS = 24 * HOUR_MS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(guard: Guard) {
    val state by guard.store.state.collectAsStateWithLifecycle(initialValue = KnockspotState())
    val live by guard.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var renaming by remember { mutableStateOf<Device?>(null) }
    var confirmEmptyEnable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Re-check setup items (notification permission, battery) whenever the screen comes back.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }

    fun act(block: suspend () -> Unit) {
        scope.launch { busy = true; try { block() } finally { busy = false } }
    }

    fun toggleProtection(on: Boolean) {
        if (on && state.devices.none { it.access == Access.TRUSTED } && live.connected.isEmpty()) {
            confirmEmptyEnable = true
        } else act { guard.setProtection(on) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Knockspot", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        val now = System.currentTimeMillis()
        val knocking = state.devices
            .filter { it.access == Access.UNDECIDED && it.lastKnockAt != null && now - it.lastKnockAt < RECENT_KNOCK_MS }
            .sortedByDescending { it.lastKnockAt }
        val trusted = state.devices.filter { it.access == Access.TRUSTED }
        val guests = state.devices.filter { Policy.isActiveGuest(it, now) }
        val blocked = state.devices.filter { it.access == Access.BLOCKED }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "setup") { SetupCard(guard, live.shizuku, resumeTick) }
            item(key = "protection") {
                ProtectionCard(
                    on = state.protectionOn,
                    enabled = !busy && live.shizuku == ShizukuStatus.READY,
                    systemMode = live.systemAllowlistMode,
                    supported = live.allowlistSupported,
                    hotspotOn = live.hotspotOn,
                    error = live.lastError,
                    doorOpenUntil = state.doorOpenUntil,
                    onToggle = ::toggleProtection,
                    onOpenDoor = { act { guard.openDoor() } },
                    onCloseDoor = { act { guard.closeDoor() } },
                )
            }

            if (knocking.isNotEmpty()) {
                section("Knocking")
                items(knocking, key = { "k-" + it.mac }) { d ->
                    KnockCard(
                        device = d,
                        onDecide = { act { guard.decide(d.mac, it) } },
                        onRename = { renaming = d },
                    )
                }
            }

            section("Connected now")
            if (live.connected.isEmpty()) {
                item(key = "connected-empty") {
                    EmptyLine(if (live.hotspotOn == false) "Hotspot is off." else "No devices connected.")
                }
            } else {
                items(live.connected, key = { "c-$it" }) { mac ->
                    val known = state.device(mac) ?: Device(mac = mac)
                    val d = if (known.hostName == null) known.copy(hostName = live.hostnames[mac]) else known
                    DeviceRow(
                        device = d, now = now, connected = true,
                        onDecide = { act { guard.decide(mac, it) } },
                        onRename = { renaming = d },
                    )
                }
            }

            deviceSection("Trusted", trusted, "Your own devices. They can always join.", now,
                onDecide = { mac, dec -> act { guard.decide(mac, dec) } }, onRename = { renaming = it })
            deviceSection("Guests", guests, "Allowed for now. Passes end automatically.", now,
                onDecide = { mac, dec -> act { guard.decide(mac, dec) } }, onRename = { renaming = it })
            deviceSection("Blocked", blocked, "Never allowed. Synced with your phone's hotspot blocklist.", now,
                onDecide = { mac, dec -> act { guard.decide(mac, dec) } }, onRename = { renaming = it })
        }
    }

    renaming?.let { d ->
        RenameDialog(
            device = d,
            onDismiss = { renaming = null },
            onSave = { name -> renaming = null; act { guard.rename(d.mac, name) } },
        )
    }

    if (confirmEmptyEnable) {
        AlertDialog(
            onDismissRequest = { confirmEmptyEnable = false },
            title = { Text("No devices connected") },
            text = {
                Text(
                    "Knockspot trusts the devices connected when you first turn protection on. " +
                        "Nothing is connected right now, so every device — including yours — will have to knock. " +
                        "Connect your laptop first, or continue and allow it when it knocks.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmEmptyEnable = false; act { guard.setProtection(true) } }) {
                    Text("Turn on anyway")
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyEnable = false }) { Text("Cancel") } },
        )
    }

}

// -------------------------------------------------------------------- setup

@SuppressLint("BatteryLife")
@Composable
private fun SetupCard(guard: Guard, shizuku: ShizukuStatus, resumeTick: Int) {
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notificationsOk = remember(resumeTick) { Notifications.canNotify(context) }
    val batteryOk = remember(resumeTick) { ignoresBatteryOptimizations(context) }

    if (shizuku == ShizukuStatus.READY && notificationsOk && batteryOk) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)

            SetupItem(
                done = shizuku == ShizukuStatus.READY,
                title = "Shizuku",
                body = when (shizuku) {
                    ShizukuStatus.NOT_RUNNING ->
                        "Knockspot changes hotspot settings through Shizuku (no root). Start Shizuku, then come back."
                    ShizukuStatus.NO_PERMISSION -> "Shizuku is running. Allow Knockspot to use it."
                    ShizukuStatus.READY -> "Connected."
                },
                action = when (shizuku) {
                    ShizukuStatus.NOT_RUNNING -> "Open Shizuku" to { openShizuku(context) }
                    ShizukuStatus.NO_PERMISSION -> "Allow" to { guard.requestShizukuPermission() }
                    ShizukuStatus.READY -> null
                },
            )
            SetupItem(
                done = notificationsOk,
                title = "Notifications",
                body = "Needed to tell you when a device knocks.",
                action = when {
                    notificationsOk -> null
                    Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(context) ->
                        "Allow" to { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    else -> "Settings" to {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                },
            )
            SetupItem(
                done = batteryOk,
                title = "Run in background",
                body = "Stops the phone from closing Knockspot, so knocks still reach you.",
                action = if (batteryOk) null else "Allow" to {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData("package:${context.packageName}".toUri()),
                    )
                },
            )
        }
    }
}

@Composable
private fun SetupItem(done: Boolean, title: String, body: String, action: Pair<String, () -> Unit>?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = action.second) { Text(action.first) }
        }
    }
}

// -------------------------------------------------------------------- protection

@Composable
private fun ProtectionCard(
    on: Boolean,
    enabled: Boolean,
    systemMode: Boolean?,
    supported: Boolean?,
    hotspotOn: Boolean?,
    error: String?,
    doorOpenUntil: Long?,
    onToggle: (Boolean) -> Unit,
    onOpenDoor: () -> Unit,
    onCloseDoor: () -> Unit,
) {
    // Ticks once a second while the door is open, for the countdown.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(doorOpenUntil) {
        now = System.currentTimeMillis()
        while (doorOpenUntil != null && now < doorOpenUntil) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val doorOpen = on && doorOpenUntil != null && doorOpenUntil > now

    val container = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (on) Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(32.dp))
                else Icon(painterResource(R.drawable.ic_stat_knockspot), contentDescription = null,
                    modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (on) "Protection on" else "Protection off", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (on) "Only trusted devices and guests can join. Others knock, and you decide."
                        else "Anyone with the password can join, except blocked devices.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = on, onCheckedChange = onToggle, enabled = enabled)
            }
            if (hotspotOn != null) {
                Text(if (hotspotOn) "Hotspot is on" else "Hotspot is off — settings apply the next time you turn it on",
                    style = MaterialTheme.typography.labelMedium)
            }
            if (on) {
                HorizontalDivider()
                if (doorOpen) {
                    val left = ((doorOpenUntil!! - now) / 1000).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Letting someone in · %d:%02d".format(left / 60, left % 60),
                                style = MaterialTheme.typography.titleSmall)
                            Text("The next new device to connect joins as a guest until the hotspot turns off.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onCloseDoor, enabled = enabled) { Text("Close") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Someone asking for your hotspot? Tap this first, then they connect once.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = onOpenDoor, enabled = enabled && hotspotOn != false) {
                            Text("Let someone in")
                        }
                    }
                }
            }
            if (supported == false) {
                WarningLine("This phone's Wi-Fi hardware does not report allowlist support, so protection may not work.")
            } else if (on && systemMode == false && !doorOpen) {
                WarningLine("The phone has allowlist mode switched off. Knockspot will switch it back on.")
            }
            if (error != null) WarningLine(error)
        }
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// -------------------------------------------------------------------- devices

@Composable
private fun KnockCard(device: Device, onDecide: (Decision) -> Unit, onRename: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${device.mac} · knocked ${relative(device.lastKnockAt)}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = onRename) { Text("Rename") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecide(Decision.AllowUntilHotspotOff) }) { Text("Allow once") }
                OutlinedButton(onClick = { onDecide(Decision.AlwaysAllow) }) { Text("Always") }
                OutlinedButton(onClick = { onDecide(Decision.Block) }) { Text("Block") }
            }
        }
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "h-$title") {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun LazyListScope.deviceSection(
    title: String,
    devices: List<Device>,
    emptyText: String,
    now: Long,
    onDecide: (String, Decision) -> Unit,
    onRename: (Device) -> Unit,
) {
    section(title)
    if (devices.isEmpty()) {
        item(key = "e-$title") { EmptyLine(emptyText) }
    } else {
        items(devices, key = { "$title-${it.mac}" }) { d ->
            DeviceRow(d, now, connected = false, onDecide = { onDecide(d.mac, it) }, onRename = { onRename(d) })
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeviceRow(
    device: Device,
    now: Long,
    connected: Boolean,
    onDecide: (Decision) -> Unit,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (icon, status) = describe(device, now, connected)
    Column {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(device.displayName) },
            supportingContent = {
                Text("${device.mac} · $status", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val options = buildList<Pair<String, () -> Unit>> {
                            add("Rename" to onRename)
                            if (device.access != Access.TRUSTED) add("Always allow" to { onDecide(Decision.AlwaysAllow) })
                            add("Allow until hotspot turns off" to { onDecide(Decision.AllowUntilHotspotOff) })
                            add("Allow for 1 hour" to { onDecide(Decision.AllowFor(HOUR_MS)) })
                            if (device.access != Access.BLOCKED) add("Block" to { onDecide(Decision.Block) })
                            add("Forget" to { onDecide(Decision.Forget) })
                        }
                        options.forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                        }
                    }
                }
            },
        )
        HorizontalDivider()
    }
}

private fun describe(d: Device, now: Long, connected: Boolean): Pair<ImageVector, String> {
    val access = when {
        d.access == Access.TRUSTED -> Icons.Filled.CheckCircle to "trusted"
        Policy.isActiveGuest(d, now) -> Icons.Filled.DateRange to
            (d.guestUntil?.let { "guest until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" }
                ?: "guest until hotspot turns off")
        d.access == Access.BLOCKED -> Icons.Filled.Close to "blocked"
        else -> Icons.Outlined.Info to "not allowed"
    }
    val seen = when {
        connected -> "connected"
        d.lastSeenAt != null -> "seen ${relative(d.lastSeenAt)}"
        else -> null
    }
    return access.first to listOfNotNull(access.second, seen).joinToString(" · ")
}

private fun relative(time: Long?): String =
    if (time == null) "never"
    else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
private fun RenameDialog(device: Device, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(device.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column {
                Text(device.mac, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    placeholder = { Text("e.g. Rahul's phone") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// -------------------------------------------------------------------- helpers

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun ignoresBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        ?: Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri())
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
