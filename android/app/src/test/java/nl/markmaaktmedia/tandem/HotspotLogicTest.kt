package nl.markmaaktmedia.tandem

import nl.markmaaktmedia.tandem.hotspot.AuthResult
import nl.markmaaktmedia.tandem.hotspot.BleState
import nl.markmaaktmedia.tandem.hotspot.ChallengeStore
import nl.markmaaktmedia.tandem.hotspot.FailureLimiter
import nl.markmaaktmedia.tandem.hotspot.HotspotAuth
import nl.markmaaktmedia.tandem.hotspot.HotspotPolicy
import nl.markmaaktmedia.tandem.hotspot.HotspotProtocol
import nl.markmaaktmedia.tandem.hotspot.HotspotRequest
import nl.markmaaktmedia.tandem.hotspot.IdleTracker
import nl.markmaaktmedia.tandem.hotspot.Refusal
import nl.markmaaktmedia.tandem.hotspot.StateCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotLogicTest {
    private val id = "abcdefghijklmnopqrstuvwxyz"
    private val signature = ByteArray(64) { it.toByte() }

    @Test fun requestSurvivesTheWire() {
        val bytes = HotspotRequest(HotspotProtocol.ACTION_ON, 0x0102030405060708L, id, signature).encode()
        val back = HotspotRequest.decode(bytes)!!
        assertEquals(HotspotProtocol.ACTION_ON, back.action)
        assertEquals(0x0102030405060708L, back.timestampMs)
        assertEquals(id, back.deviceId)
        assertArrayEquals(signature, back.signature)
    }

    @Test fun malformedRequestsAreRefused() {
        val good = HotspotRequest(HotspotProtocol.ACTION_OFF, 1, id, signature).encode()
        assertNull(HotspotRequest.decode(ByteArray(0)))
        assertNull(HotspotRequest.decode(good.copyOf(good.size - 1)))
        assertNull(HotspotRequest.decode(good + byteArrayOf(0)))
        assertNull(HotspotRequest.decode(good.copyOf().also { it[0] = 9 }))
        assertNull(HotspotRequest.decode(good.copyOf().also { it[1] = 7 }))
        assertNull(HotspotRequest.decode(good.copyOf().also { it[10] = 0 }))
    }

    @Test fun stateRoundTripsAndUnknownClientsSurvive() {
        assertEquals(BleState.On to 3, StateCodec.decode(StateCodec.encode(BleState.On, 3)))
        assertEquals(BleState.Manual to null, StateCodec.decode(StateCodec.encode(BleState.Manual, null)))
        assertNull(StateCodec.decode(byteArrayOf(99, 0)))
    }

    @Test fun aChallengeWorksOnce() {
        var now = 0L
        val store = ChallengeStore(random = { ByteArray(16) { 7 } }, clock = { now })
        val issued = store.issue("aa")
        assertArrayEquals(issued, store.take("aa"))
        assertNull(store.take("aa"))
    }

    @Test fun aChallengeGoesStaleAndIsPerPeer() {
        var now = 0L
        val store = ChallengeStore(random = { ByteArray(16) { 1 } }, clock = { now }, ttlMs = 1000)
        store.issue("aa")
        assertNull(store.take("bb"))
        now = 1001
        assertNull(store.take("aa"))
    }

    @Test fun readingAgainReplacesTheChallenge() {
        var n = 0
        val store = ChallengeStore(random = { ByteArray(16) { (n++ / 16).toByte() } }, clock = { 0 })
        val first = store.issue("aa")
        val second = store.issue("aa")
        assertFalse(first.contentEquals(second))
        assertArrayEquals(second, store.take("aa"))
    }

    private fun auth(verdict: Boolean, clock: () -> Long = { 0 }): Pair<HotspotAuth, ChallengeStore> {
        val store = ChallengeStore(random = { ByteArray(16) { 5 } }, clock = clock)
        val gate = HotspotAuth(
            store, FailureLimiter(clock),
            buildMessage = { c, i, a, t -> c + i.toByteArray() + byteArrayOf(a.toByte()) + t.toString().toByteArray() },
            verify = { _, _, _ -> verdict },
        )
        return gate to store
    }

    private val request = HotspotRequest(HotspotProtocol.ACTION_ON, 5, id, signature).encode()

    @Test fun aGoodRequestIsAccepted() {
        val (gate, store) = auth(true)
        store.issue("p")
        val result = gate.check("p", request)
        assertTrue(result is AuthResult.Accepted)
        assertEquals(HotspotProtocol.ACTION_ON, (result as AuthResult.Accepted).request.action)
    }

    @Test fun aRequestNeedsAChallengeAndUsesItUp() {
        val (gate, store) = auth(true)
        assertEquals(AuthResult.NoChallenge, gate.check("p", request))
        store.issue("p")
        assertTrue(gate.check("p", request) is AuthResult.Accepted)
        assertEquals(AuthResult.NoChallenge, gate.check("p", request))
    }

    @Test fun aWrongSignatureBurnsTheChallenge() {
        val (gate, store) = auth(false)
        store.issue("p")
        assertEquals(AuthResult.BadSignature, gate.check("p", request))
        assertNull(store.take("p"))
    }

    @Test fun guessingIsStopped() {
        val (gate, store) = auth(false)
        repeat(5) {
            store.issue("p")
            gate.check("p", request)
        }
        store.issue("p")
        assertEquals(AuthResult.Blocked, gate.check("p", request))
    }

    @Test fun failuresAreForgottenAfterAMinute() {
        var now = 0L
        val (gate, store) = auth(false) { now }
        repeat(5) {
            store.issue("p")
            gate.check("p", request)
        }
        now = 61_000
        store.issue("p")
        assertEquals(AuthResult.BadSignature, gate.check("p", request))
    }

    @Test fun policyGuards() {
        assertNull(HotspotPolicy.refusal(true, 80, false, false, false))
        assertEquals(Refusal.Disabled, HotspotPolicy.refusal(false, 80, false, false, false))
        assertEquals(Refusal.Battery, HotspotPolicy.refusal(true, 15, false, false, false))
        assertNull(HotspotPolicy.refusal(true, 16, false, false, false))
        assertNull(HotspotPolicy.refusal(true, 5, true, false, false))
        assertNull(HotspotPolicy.refusal(true, null, false, false, false))
        assertEquals(Refusal.Roaming, HotspotPolicy.refusal(true, 80, false, true, false))
        assertNull(HotspotPolicy.refusal(true, 80, false, true, true))
        assertNotNull(HotspotPolicy.stateFor(Refusal.Roaming))
    }

    @Test fun idleHotspotGoesOffAfterFiveMinutes() {
        val idle = IdleTracker()
        assertFalse(idle.shouldTurnOff(0, false))
        assertFalse(idle.shouldTurnOff(299_000, false))
        assertTrue(idle.shouldTurnOff(300_000, false))
    }

    @Test fun aClientResetsTheIdleClock() {
        val idle = IdleTracker()
        idle.shouldTurnOff(0, false)
        assertFalse(idle.shouldTurnOff(200_000, true))
        assertFalse(idle.shouldTurnOff(400_000, false))
        assertTrue(idle.shouldTurnOff(700_000, false))
    }
}
