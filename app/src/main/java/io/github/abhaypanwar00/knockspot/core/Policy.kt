package io.github.abhaypanwar00.knockspot.core

/**
 * All of Knockspot's access rules, as pure functions over [KnockspotState].
 *
 * Nothing here touches Android, so every rule is covered by plain JVM unit tests.
 * The rest of the app only does I/O: read the system state, run it through these
 * functions, write the result back.
 */
object Policy {

    /** A device retrying to connect knocks many times a minute; notify at most once per window. */
    const val KNOCK_NOTIFY_COOLDOWN_MS = 60_000L

    fun normalize(mac: String): String = mac.trim().lowercase()

    fun isActiveGuest(d: Device, now: Long): Boolean =
        d.access == Access.GUEST && (d.guestUntil == null || d.guestUntil > now)

    /** What the system hotspot configuration should look like for this state. */
    fun desiredLists(state: KnockspotState, now: Long): SystemLists = SystemLists(
        allowlistMode = state.protectionOn,
        allowed = state.devices
            .filter { it.access == Access.TRUSTED || isActiveGuest(it, now) }
            .mapTo(sortedSetOf()) { it.mac },
        blocked = state.devices
            .filter { it.access == Access.BLOCKED }
            .mapTo(sortedSetOf()) { it.mac },
    )

    data class KnockOutcome(val state: KnockspotState, val notify: Boolean)

    /** A device that is not on the allowlist tried to join. */
    fun onKnock(state: KnockspotState, rawMac: String, now: Long): KnockOutcome {
        val mac = normalize(rawMac)
        val existing = state.device(mac)
        if (existing != null) {
            // Blocked devices stay silent; allowed ones only knock if the system is out of
            // sync, which the next sync fixes — no need to bother the user.
            if (existing.access == Access.BLOCKED || existing.access == Access.TRUSTED ||
                isActiveGuest(existing, now)
            ) return KnockOutcome(state, notify = false)
        }
        val base = existing ?: Device(mac = mac)
        val notify = base.lastNotifiedAt == null || now - base.lastNotifiedAt >= KNOCK_NOTIFY_COOLDOWN_MS
        val updated = base.copy(
            access = Access.UNDECIDED,
            guestUntil = null,
            lastKnockAt = now,
            lastNotifiedAt = if (notify) now else base.lastNotifiedAt,
        )
        return KnockOutcome(state.upsert(updated), notify)
    }

    fun decide(state: KnockspotState, rawMac: String, decision: Decision, now: Long): KnockspotState {
        val mac = normalize(rawMac)
        if (decision == Decision.Forget) return state.copy(devices = state.devices.filterNot { it.mac == mac })
        val base = state.device(mac) ?: Device(mac = mac)
        val updated = when (decision) {
            Decision.AllowUntilHotspotOff -> base.copy(access = Access.GUEST, guestUntil = null)
            is Decision.AllowFor -> base.copy(access = Access.GUEST, guestUntil = now + decision.millis)
            Decision.AlwaysAllow -> base.copy(access = Access.TRUSTED, guestUntil = null)
            Decision.Block -> base.copy(access = Access.BLOCKED, guestUntil = null)
            Decision.Forget -> error("handled above")
        }
        return state.upsert(updated)
    }

    fun rename(state: KnockspotState, rawMac: String, name: String?): KnockspotState {
        val mac = normalize(rawMac)
        val base = state.device(mac) ?: Device(mac = mac)
        return state.upsert(base.copy(name = name?.trim()?.takeIf { it.isNotEmpty() }))
    }

    /** "Until the hotspot turns off" passes end when the hotspot stops. */
    fun onHotspotStopped(state: KnockspotState): KnockspotState = state.mapDevices {
        if (it.access == Access.GUEST && it.guestUntil == null) it.copy(access = Access.UNDECIDED) else it
    }

    /** Timed passes end at their deadline. */
    fun expireGuests(state: KnockspotState, now: Long): KnockspotState = state.mapDevices {
        if (it.access == Access.GUEST && it.guestUntil != null && it.guestUntil <= now) {
            it.copy(access = Access.UNDECIDED, guestUntil = null)
        } else it
    }

    /** The next moment a timed guest pass ends, so the app can wake up for it. */
    fun nextExpiry(state: KnockspotState, now: Long): Long? = state.devices
        .filter { it.access == Access.GUEST && it.guestUntil != null && it.guestUntil > now }
        .minOfOrNull { it.guestUntil!! }

    /**
     * Turning protection on. The first time (no trusted devices yet), whatever is connected
     * right now — normally your own laptop or phone — becomes trusted, so turning protection
     * on never locks you out. Blocked devices are never auto-trusted.
     */
    fun enableProtection(state: KnockspotState, connected: Collection<String>, now: Long): KnockspotState {
        var s = state.copy(protectionOn = true)
        if (s.devices.none { it.access == Access.TRUSTED }) {
            for (mac in connected.map(::normalize)) {
                val d = s.device(mac)
                if (d?.access == Access.BLOCKED) continue
                s = s.upsert((d ?: Device(mac = mac)).copy(access = Access.TRUSTED, guestUntil = null, lastSeenAt = now))
            }
        }
        return s
    }

    fun disableProtection(state: KnockspotState): KnockspotState = state.copy(protectionOn = false)

    /**
     * Two-way sync with the phone's own blocklist (e.g. OxygenOS Settings → Connection management).
     * Entries the system has gained since our last write were blocked by the user in Settings;
     * entries it has lost were unblocked there. Both are adopted before we write again.
     */
    fun syncFromSystem(state: KnockspotState, systemBlocked: Collection<String>): KnockspotState {
        val system = systemBlocked.mapTo(mutableSetOf(), ::normalize)
        val lastWritten = state.lastAppliedBlocked.orEmpty().toSet()
        var s = state
        for (mac in system - lastWritten) {
            val d = s.device(mac) ?: Device(mac = mac)
            if (d.access != Access.BLOCKED) s = s.upsert(d.copy(access = Access.BLOCKED, guestUntil = null))
        }
        for (mac in lastWritten - system) {
            val d = s.device(mac) ?: continue
            if (d.access == Access.BLOCKED) s = s.upsert(d.copy(access = Access.UNDECIDED))
        }
        return s
    }

    fun recordApplied(state: KnockspotState, blocked: Set<String>): KnockspotState =
        state.copy(lastAppliedBlocked = blocked.sorted())

    /** Remember when known devices were last connected. Unknown devices are not added. */
    fun markSeen(state: KnockspotState, connected: Collection<String>, now: Long): KnockspotState {
        val macs = connected.mapTo(mutableSetOf(), ::normalize)
        if (state.devices.none { it.mac in macs }) return state
        return state.mapDevices { if (it.mac in macs) it.copy(lastSeenAt = now) else it }
    }

    // ------------------------------------------------------------------ helpers

    private fun KnockspotState.upsert(d: Device): KnockspotState {
        val i = devices.indexOfFirst { it.mac == d.mac }
        return copy(devices = if (i < 0) devices + d else devices.toMutableList().also { it[i] = d })
    }

    private inline fun KnockspotState.mapDevices(f: (Device) -> Device): KnockspotState =
        copy(devices = devices.map(f))
}
