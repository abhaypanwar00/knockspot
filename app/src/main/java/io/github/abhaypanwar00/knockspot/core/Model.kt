package io.github.abhaypanwar00.knockspot.core

import kotlinx.serialization.Serializable

/** What a device is allowed to do on the hotspot. */
@Serializable
enum class Access {
    /** Always allowed (your own devices). */
    TRUSTED,

    /** Temporarily allowed — until [Device.guestUntil], or until the hotspot turns off if that is null. */
    GUEST,

    /** Never allowed. Mirrors the system hotspot blocklist. */
    BLOCKED,

    /** Known to Knockspot (it knocked, or its guest pass ended) but has no access. */
    UNDECIDED,
}

@Serializable
data class Device(
    /** Lower-case MAC address as the hotspot sees it (usually a per-network randomized address). */
    val mac: String,
    val name: String? = null,
    val access: Access = Access.UNDECIDED,
    /** Epoch millis when a [Access.GUEST] pass ends; null means "until the hotspot turns off". */
    val guestUntil: Long? = null,
    val lastKnockAt: Long? = null,
    val lastNotifiedAt: Long? = null,
    val lastSeenAt: Long? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: defaultName(mac)

    companion object {
        fun defaultName(mac: String): String = "Device " + mac.takeLast(5).uppercase()
    }
}

@Serializable
data class KnockspotState(
    /** When on, only trusted devices and active guests may join; everyone else knocks. */
    val protectionOn: Boolean = false,
    val devices: List<Device> = emptyList(),
    /**
     * The blocklist Knockspot last wrote to the system. Comparing it with what the system
     * reports tells us which entries the user changed in the phone's own Settings app.
     * Null until the first successful sync.
     */
    val lastAppliedBlocked: List<String>? = null,
) {
    fun device(mac: String): Device? = devices.firstOrNull { it.mac == mac }
}

/** The three hotspot settings Knockspot controls. */
data class SystemLists(
    val allowlistMode: Boolean,
    val allowed: Set<String>,
    val blocked: Set<String>,
)

/** A choice the user makes about a device. */
sealed interface Decision {
    data object AllowUntilHotspotOff : Decision
    data class AllowFor(val millis: Long) : Decision
    data object AlwaysAllow : Decision
    data object Block : Decision
    data object Forget : Decision
}
