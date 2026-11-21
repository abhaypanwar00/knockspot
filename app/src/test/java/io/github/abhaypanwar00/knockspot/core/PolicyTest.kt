package io.github.abhaypanwar00.knockspot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {

    private val laptop = "aa:bb:cc:00:00:01"
    private val roommate = "aa:bb:cc:00:00:02"
    private val stranger = "aa:bb:cc:00:00:03"
    private val t0 = 1_000_000L

    private fun state(vararg devices: Device, protection: Boolean = true) =
        KnockspotState(protectionOn = protection, devices = devices.toList())

    // ---------------------------------------------------------------- desired lists

    @Test fun `trusted devices and active guests are allowed, blocked devices are blocked`() {
        val s = state(
            Device(laptop, access = Access.TRUSTED),
            Device(roommate, access = Access.GUEST, guestUntil = t0 + 10),
            Device(stranger, access = Access.BLOCKED),
        )
        val lists = Policy.desiredLists(s, now = t0)
        assertTrue(lists.allowlistMode)
        assertEquals(setOf(laptop, roommate), lists.allowed)
        assertEquals(setOf(stranger), lists.blocked)
    }

    @Test fun `expired guests are not allowed`() {
        val s = state(Device(roommate, access = Access.GUEST, guestUntil = t0))
        assertTrue(Policy.desiredLists(s, now = t0).allowed.isEmpty())
    }

    @Test fun `allowlist mode follows the protection switch`() {
        assertFalse(Policy.desiredLists(state(protection = false), t0).allowlistMode)
    }

    // ---------------------------------------------------------------- knocks

    @Test fun `unknown device knocking is remembered and notified`() {
        val out = Policy.onKnock(state(), "AA:BB:CC:00:00:03", now = t0)
        assertTrue(out.notify)
        val d = out.state.device(stranger)!!
        assertEquals(Access.UNDECIDED, d.access)
        assertEquals(t0, d.lastKnockAt)
    }

    @Test fun `repeated knocks notify only once per cooldown`() {
        val first = Policy.onKnock(state(), stranger, now = t0)
        val second = Policy.onKnock(first.state, stranger, now = t0 + 5_000)
        val later = Policy.onKnock(second.state, stranger, now = t0 + Policy.KNOCK_NOTIFY_COOLDOWN_MS)
        assertFalse(second.notify)
        assertEquals(t0 + 5_000, second.state.device(stranger)!!.lastKnockAt)
        assertTrue(later.notify)
    }

    @Test fun `blocked devices knock silently`() {
        val s = state(Device(stranger, access = Access.BLOCKED))
        val out = Policy.onKnock(s, stranger, now = t0)
        assertFalse(out.notify)
        assertEquals(s, out.state)
    }

    @Test fun `trusted device knocking does not notify`() {
        val s = state(Device(laptop, access = Access.TRUSTED))
        assertFalse(Policy.onKnock(s, laptop, t0).notify)
    }

    @Test fun `a guest whose pass ended knocks like anyone else`() {
        val s = state(Device(roommate, access = Access.GUEST, guestUntil = t0 - 1))
        val out = Policy.onKnock(s, roommate, t0)
        assertTrue(out.notify)
        assertEquals(Access.UNDECIDED, out.state.device(roommate)!!.access)
    }

    // ---------------------------------------------------------------- decisions

    @Test fun `decisions set access`() {
        var s = Policy.onKnock(state(), roommate, t0).state
        s = Policy.decide(s, roommate, Decision.AllowUntilHotspotOff, t0)
        assertEquals(Access.GUEST, s.device(roommate)!!.access)
        assertNull(s.device(roommate)!!.guestUntil)

        s = Policy.decide(s, roommate, Decision.AllowFor(3_600_000), t0)
        assertEquals(t0 + 3_600_000, s.device(roommate)!!.guestUntil)

        s = Policy.decide(s, roommate, Decision.AlwaysAllow, t0)
        assertEquals(Access.TRUSTED, s.device(roommate)!!.access)
        assertNull(s.device(roommate)!!.guestUntil)

        s = Policy.decide(s, roommate, Decision.Block, t0)
        assertEquals(Access.BLOCKED, s.device(roommate)!!.access)

        s = Policy.decide(s, roommate, Decision.Forget, t0)
        assertNull(s.device(roommate))
    }

    @Test fun `deciding keeps the device name`() {
        var s = Policy.rename(state(), roommate, "  Rahul's phone ")
        s = Policy.decide(s, roommate, Decision.Block, t0)
        assertEquals("Rahul's phone", s.device(roommate)!!.displayName)
    }

    @Test fun `blank rename falls back to the default name`() {
        val s = Policy.rename(state(), roommate, "   ")
        assertEquals(Device.defaultName(roommate), s.device(roommate)!!.displayName)
    }

    // ---------------------------------------------------------------- expiry

    @Test fun `hotspot off ends untimed guest passes only`() {
        val s = state(
            Device(laptop, access = Access.TRUSTED),
            Device(roommate, access = Access.GUEST),
            Device(stranger, access = Access.GUEST, guestUntil = t0 + 100),
        )
        val after = Policy.onHotspotStopped(s)
        assertEquals(Access.TRUSTED, after.device(laptop)!!.access)
        assertEquals(Access.UNDECIDED, after.device(roommate)!!.access)
        assertEquals(Access.GUEST, after.device(stranger)!!.access)
    }

    @Test fun `timed guest passes expire at their deadline`() {
        val s = state(
            Device(roommate, access = Access.GUEST, guestUntil = t0 + 100),
            Device(stranger, access = Access.GUEST, guestUntil = t0 + 200),
        )
        assertEquals(t0 + 100, Policy.nextExpiry(s, t0))
        val mid = Policy.expireGuests(s, now = t0 + 100)
        assertEquals(Access.UNDECIDED, mid.device(roommate)!!.access)
        assertEquals(Access.GUEST, mid.device(stranger)!!.access)
        assertEquals(t0 + 200, Policy.nextExpiry(mid, t0 + 100))
        assertNull(Policy.nextExpiry(Policy.expireGuests(mid, t0 + 200), t0 + 200))
    }

    // ---------------------------------------------------------------- protection switch

    @Test fun `first enable trusts the devices connected right now`() {
        val s = Policy.enableProtection(state(protection = false), listOf("AA:BB:CC:00:00:01"), t0)
        assertTrue(s.protectionOn)
        assertEquals(Access.TRUSTED, s.device(laptop)!!.access)
    }

    @Test fun `enable never auto-trusts a blocked device`() {
        val s = Policy.enableProtection(
            state(Device(stranger, access = Access.BLOCKED), protection = false),
            listOf(stranger, laptop), t0,
        )
        assertEquals(Access.BLOCKED, s.device(stranger)!!.access)
        assertEquals(Access.TRUSTED, s.device(laptop)!!.access)
    }

    @Test fun `later enables do not trust whoever happens to be connected`() {
        val s = Policy.enableProtection(
            state(Device(laptop, access = Access.TRUSTED), protection = false),
            listOf(roommate), t0,
        )
        assertNull(s.device(roommate))
    }

    // ---------------------------------------------------------------- system sync

    @Test fun `first sync imports the system blocklist`() {
        val s = Policy.syncFromSystem(state(), listOf("CE:00:28:FF:A6:1D"))
        assertEquals(Access.BLOCKED, s.device("ce:00:28:ff:a6:1d")!!.access)
    }

    @Test fun `blocking in system settings is adopted`() {
        val s0 = Policy.recordApplied(state(Device(roommate, access = Access.TRUSTED)), emptySet())
        val s = Policy.syncFromSystem(s0, listOf(roommate))
        assertEquals(Access.BLOCKED, s.device(roommate)!!.access)
    }

    @Test fun `unblocking in system settings is adopted`() {
        val s0 = Policy.recordApplied(state(Device(roommate, access = Access.BLOCKED)), setOf(roommate))
        val s = Policy.syncFromSystem(s0, emptyList())
        assertEquals(Access.UNDECIDED, s.device(roommate)!!.access)
    }

    @Test fun `unchanged system blocklist changes nothing`() {
        val s0 = Policy.recordApplied(state(Device(roommate, access = Access.BLOCKED)), setOf(roommate))
        assertEquals(s0, Policy.syncFromSystem(s0, listOf(roommate)))
    }

    @Test fun `first sync adopts an allowlist that is already switched on`() {
        val system = SystemLists(allowlistMode = true, allowed = setOf(laptop), blocked = setOf(stranger))
        val s = Policy.syncFromSystem(Policy.adoptExisting(state(protection = false), system), system.blocked)
        assertTrue(s.protectionOn)
        assertEquals(Access.TRUSTED, s.device(laptop)!!.access)
        assertEquals(Access.BLOCKED, s.device(stranger)!!.access)
    }

    @Test fun `later syncs do not re-adopt the system allowlist`() {
        val synced = Policy.recordApplied(state(protection = false), emptySet())
        val system = SystemLists(allowlistMode = true, allowed = setOf(roommate), blocked = emptySet())
        val s = Policy.adoptExisting(synced, system)
        assertFalse(s.protectionOn)
        assertNull(s.device(roommate))
    }

    // ---------------------------------------------------------------- seen

    @Test fun `markSeen updates known devices only`() {
        val s = Policy.markSeen(state(Device(laptop, access = Access.TRUSTED)), listOf(laptop, stranger), t0)
        assertEquals(t0, s.device(laptop)!!.lastSeenAt)
        assertNull(s.device(stranger))
    }
}
