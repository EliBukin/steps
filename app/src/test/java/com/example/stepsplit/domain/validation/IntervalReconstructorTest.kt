package com.example.stepsplit.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalReconstructorTest {

    private val positiveTracked = setOf(MotionActivityType.WALKING, MotionActivityType.RUNNING)
    private val positiveInterrupters = setOf(MotionActivityType.STILL, MotionActivityType.IN_VEHICLE, MotionActivityType.ON_BICYCLE)
    private val vehicleTracked = setOf(MotionActivityType.IN_VEHICLE, MotionActivityType.ON_BICYCLE)
    private val vehicleInterrupters = emptySet<MotionActivityType>()

    private fun enter(type: MotionActivityType, atMillis: Long, epoch: Long = 1L) =
        ReconciliationSignal.Transition(type, isEnter = true, wallClockEpochMilli = atMillis, temporalContinuityEpoch = epoch)

    private fun exit(type: MotionActivityType, atMillis: Long, epoch: Long = 1L) =
        ReconciliationSignal.Transition(type, isEnter = false, wallClockEpochMilli = atMillis, temporalContinuityEpoch = epoch)

    private fun sampledInterrupt(atMillis: Long, epoch: Long = 1L) =
        ReconciliationSignal.SampledInterrupt(wallClockEpochMilli = atMillis, temporalContinuityEpoch = epoch)

    // ---- Basic open/close ----

    @Test
    fun `an ENTER with nothing open inserts a fresh open interval`() {
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, enter(MotionActivityType.IN_VEHICLE, 1_000),
            currentOpenByType = emptyMap(), nearbySignals = emptyList(), failClosedIfLateAndUnconfirmed = false,
        )
        assertTrue(result.closes.isEmpty())
        assertEquals(1, result.inserts.size)
        assertNull(result.inserts.single().endWallClockEpochMilli)
        assertEquals(1_000L, result.inserts.single().startWallClockEpochMilli)
    }

    @Test
    fun `an EXIT closes its matching open interval regardless of how many hours ago it started`() {
        val threeHoursAgo = 0L
        val open = PersistedInterval(id = 42, activityType = MotionActivityType.IN_VEHICLE, startWallClockEpochMilli = threeHoursAgo, endWallClockEpochMilli = null, temporalContinuityEpoch = 1, closedReason = "OPEN")
        val exitTime = threeHoursAgo + 3 * 60 * 60 * 1000L

        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, exit(MotionActivityType.IN_VEHICLE, exitTime),
            currentOpenByType = mapOf(MotionActivityType.IN_VEHICLE to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = false,
        )

        assertEquals(1, result.closes.size)
        assertTrue(result.inserts.isEmpty())
        val close = result.closes.single()
        assertEquals(42L, close.intervalId)
        assertEquals(exitTime, close.endWallClockEpochMilli)
    }

    @Test
    fun `reconstruction never touches rows outside the exact affected segment`() {
        // Seed several unrelated open rows across both tracked types - only IN_VEHICLE's own EXIT
        // may be closed here; ON_BICYCLE must be left completely alone (not even referenced).
        val vehicleOpen = PersistedInterval(1, MotionActivityType.IN_VEHICLE, 0L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, exit(MotionActivityType.IN_VEHICLE, 5_000),
            currentOpenByType = mapOf(MotionActivityType.IN_VEHICLE to vehicleOpen), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = false,
        )
        assertEquals(1, result.closes.size)
        assertEquals(1L, result.closes.single().intervalId)
    }

    // ---- Duplicate re-ENTER ----

    @Test
    fun `a duplicate re-ENTER of the already-open type is a no-op`() {
        val open = PersistedInterval(1, MotionActivityType.WALKING, 1_000L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, enter(MotionActivityType.WALKING, 5_000),
            currentOpenByType = mapOf(MotionActivityType.WALKING to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = true,
        )
        assertTrue(result.isEmpty)
    }

    // ---- Type switching (review-4 point 3) ----

    @Test
    fun `WALKING to RUNNING produces one mutation set closing WALKING and inserting RUNNING atomically`() {
        val open = PersistedInterval(7, MotionActivityType.WALKING, 1_000L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, enter(MotionActivityType.RUNNING, 4_000),
            currentOpenByType = mapOf(MotionActivityType.WALKING to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = true,
        )
        assertEquals(1, result.closes.size)
        assertEquals(7L, result.closes.single().intervalId)
        assertEquals(4_000L, result.closes.single().endWallClockEpochMilli)
        assertEquals(1, result.inserts.size)
        assertEquals(MotionActivityType.RUNNING, result.inserts.single().activityType)
        assertEquals(4_000L, result.inserts.single().startWallClockEpochMilli)
        assertNull(result.inserts.single().endWallClockEpochMilli)
    }

    @Test
    fun `IN_VEHICLE to ON_BICYCLE produces one mutation set closing IN_VEHICLE and inserting ON_BICYCLE atomically`() {
        val open = PersistedInterval(9, MotionActivityType.IN_VEHICLE, 1_000L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, enter(MotionActivityType.ON_BICYCLE, 2_000),
            currentOpenByType = mapOf(MotionActivityType.IN_VEHICLE to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = false,
        )
        assertEquals(1, result.closes.size)
        assertEquals(9L, result.closes.single().intervalId)
        assertEquals(1, result.inserts.size)
        assertEquals(MotionActivityType.ON_BICYCLE, result.inserts.single().activityType)
    }

    // ---- Conflicting interrupter (Transition ENTER) ----

    @Test
    fun `a STILL ENTER closes an open WALKING interval without opening anything`() {
        val open = PersistedInterval(3, MotionActivityType.WALKING, 1_000L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, enter(MotionActivityType.STILL, 6_000),
            currentOpenByType = mapOf(MotionActivityType.WALKING to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = true,
        )
        assertEquals(1, result.closes.size)
        assertEquals(6_000L, result.closes.single().endWallClockEpochMilli)
        assertTrue(result.inserts.isEmpty())
    }

    // ---- Sampled interrupt: can only close, never open (review-4 point 1) ----

    @Test
    fun `a sampled interrupt closes an open WALKING interval and never inserts`() {
        val open = PersistedInterval(4, MotionActivityType.WALKING, 1_000L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, sampledInterrupt(9_000),
            currentOpenByType = mapOf(MotionActivityType.WALKING to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = true,
        )
        assertEquals(1, result.closes.size)
        assertEquals(9_000L, result.closes.single().endWallClockEpochMilli)
        assertTrue("a sampled interrupt must never insert a new interval", result.inserts.isEmpty())
    }

    @Test
    fun `a sampled interrupt with nothing open is a no-op`() {
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, sampledInterrupt(9_000),
            currentOpenByType = emptyMap(), nearbySignals = emptyList(), failClosedIfLateAndUnconfirmed = true,
        )
        assertTrue(result.isEmpty)
    }

    // ---- Reordering: delayed older ENTER after a newer EXIT (fixes point 4) ----

    @Test
    fun `a delayed older ENTER arriving after its newer EXIT produces a closed interval, not a bogus open one`() {
        // Arrival order: EXIT@T2 was already processed first (found nothing open, no-op). Now the
        // older ENTER@T1 arrives late; the EXIT is visible as a nearby signal.
        val t1 = 1_000L
        val t2 = 5_000L
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, enter(MotionActivityType.WALKING, t1),
            currentOpenByType = emptyMap(),
            nearbySignals = listOf(exit(MotionActivityType.WALKING, t2)),
            failClosedIfLateAndUnconfirmed = true,
        )
        assertTrue(result.closes.isEmpty())
        assertEquals(1, result.inserts.size)
        val inserted = result.inserts.single()
        assertEquals(t1, inserted.startWallClockEpochMilli)
        assertEquals(t2, inserted.endWallClockEpochMilli)
        assertEquals(IntervalReconstructor.ClosedReason.OWN_EXIT, inserted.closedReason)
    }

    @Test
    fun `an EXIT arriving before any open interval exists is a no-op`() {
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, exit(MotionActivityType.WALKING, 5_000),
            currentOpenByType = emptyMap(), nearbySignals = emptyList(), failClosedIfLateAndUnconfirmed = true,
        )
        assertTrue(result.isEmpty)
    }

    // ---- Very-late signals (>45 min): vehicle widens, positive fails closed ----

    private val fortyFiveMinMillis = 45 * 60 * 1000L

    @Test
    fun `a very late positive ENTER with no confirming newer signal fails closed`() {
        val lastKnown = 10_000_000L
        val veryLateEnterTime = lastKnown - fortyFiveMinMillis - 1_000L
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, enter(MotionActivityType.WALKING, veryLateEnterTime),
            currentOpenByType = emptyMap(),
            // An unrelated nearby signal establishes "lastKnown" but does not resolve this WALKING ENTER.
            nearbySignals = listOf(exit(MotionActivityType.RUNNING, lastKnown)),
            failClosedIfLateAndUnconfirmed = true,
        )
        assertTrue("a very-late, unconfirmed positive ENTER must contribute nothing", result.isEmpty)
    }

    @Test
    fun `a very late positive ENTER IS used when a newer closing signal is found even beyond the window`() {
        val t1 = 0L
        val t2 = fortyFiveMinMillis + 60_000L // more than 45 minutes after t1
        val result = IntervalReconstructor.applySignal(
            positiveTracked, positiveInterrupters, enter(MotionActivityType.WALKING, t1),
            currentOpenByType = emptyMap(),
            nearbySignals = listOf(exit(MotionActivityType.WALKING, t2)), // the caller already widened this
            failClosedIfLateAndUnconfirmed = true,
        )
        assertEquals(1, result.inserts.size)
        assertEquals(t1, result.inserts.single().startWallClockEpochMilli)
        assertEquals(t2, result.inserts.single().endWallClockEpochMilli)
    }

    @Test
    fun `a very late vehicle ENTER is still incorporated even without an explicit confirming signal`() {
        val lastKnown = 10_000_000L
        val veryLateEnterTime = lastKnown - fortyFiveMinMillis - 1_000L
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, enter(MotionActivityType.IN_VEHICLE, veryLateEnterTime),
            currentOpenByType = emptyMap(),
            nearbySignals = listOf(exit(MotionActivityType.ON_BICYCLE, lastKnown)), // unrelated - establishes lastKnown only
            failClosedIfLateAndUnconfirmed = false,
        )
        assertEquals(
            "the vehicle group must never fail closed - a late vehicle ENTER is still trusted and opens the interval",
            1,
            result.inserts.size,
        )
        assertEquals(veryLateEnterTime, result.inserts.single().startWallClockEpochMilli)
        assertNull(result.inserts.single().endWallClockEpochMilli)
    }

    // ---- Out-of-order interval handling (strict-correctness review) ----

    @Test
    fun `an open interval stays open when a delayed EXIT older than its own start arrives`() {
        // open IN_VEHICLE@200, then a delayed EXIT@150 - the EXIT is chronologically BEFORE the
        // interval even started, so it cannot possibly be this interval's own EXIT. Closing it here
        // would both misattribute the event and construct an invalid end(150) < start(200) interval.
        val open = PersistedInterval(11, MotionActivityType.IN_VEHICLE, 200L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, exit(MotionActivityType.IN_VEHICLE, 150),
            currentOpenByType = mapOf(MotionActivityType.IN_VEHICLE to open), nearbySignals = emptyList(),
            failClosedIfLateAndUnconfirmed = false,
        )
        assertTrue("the open @200 interval must not be closed by an older EXIT", result.isEmpty)
    }

    @Test
    fun `a delayed older same-type ENTER reconstructs its own historical segment without touching a newer open interval`() {
        // Historical order: ENTER@100, EXIT@150, ENTER@200 (still open). Arrival order: EXIT@150
        // first (nothing open yet, no-op), ENTER@200 second (opens fresh), delayed ENTER@100 last.
        // The delayed ENTER must reconstruct [100,150) using the already-logged EXIT@150 as its
        // resolving signal, and must leave the currently-open @200 interval completely untouched.
        val openAt200 = PersistedInterval(21, MotionActivityType.IN_VEHICLE, 200L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, enter(MotionActivityType.IN_VEHICLE, 100),
            currentOpenByType = mapOf(MotionActivityType.IN_VEHICLE to openAt200),
            nearbySignals = listOf(exit(MotionActivityType.IN_VEHICLE, 150), enter(MotionActivityType.IN_VEHICLE, 200)),
            failClosedIfLateAndUnconfirmed = false,
        )
        assertTrue(
            "the currently-open @200 interval must never be referenced by this mutation",
            result.closes.none { it.intervalId == 21L },
        )
        assertTrue(result.closes.isEmpty())
        assertEquals(1, result.inserts.size)
        val reconstructed = result.inserts.single()
        assertEquals(MotionActivityType.IN_VEHICLE, reconstructed.activityType)
        assertEquals(100L, reconstructed.startWallClockEpochMilli)
        assertEquals(150L, reconstructed.endWallClockEpochMilli)
        assertEquals(IntervalReconstructor.ClosedReason.OWN_EXIT, reconstructed.closedReason)
    }

    @Test
    fun `a delayed older different-type ENTER never closes a newer open interval of another type`() {
        // ON_BICYCLE is open, started at 500 (newer). A delayed IN_VEHICLE ENTER@300 arrives - older
        // than the bicycle interval's own start, so it cannot be the event that switched away from
        // vehicle to bicycle. The bicycle interval must be preserved untouched; the vehicle ENTER is
        // still reconstructed on its own, resolved by the bicycle ENTER as a "switch" boundary.
        val bicycleOpen = PersistedInterval(31, MotionActivityType.ON_BICYCLE, 500L, null, 1, "OPEN")
        val result = IntervalReconstructor.applySignal(
            vehicleTracked, vehicleInterrupters, enter(MotionActivityType.IN_VEHICLE, 300),
            currentOpenByType = mapOf(MotionActivityType.ON_BICYCLE to bicycleOpen),
            nearbySignals = listOf(enter(MotionActivityType.ON_BICYCLE, 500)),
            failClosedIfLateAndUnconfirmed = false,
        )
        assertTrue(
            "the newer, currently-open bicycle interval must never be closed by an older delayed vehicle ENTER",
            result.closes.isEmpty(),
        )
        assertEquals(1, result.inserts.size)
        val reconstructed = result.inserts.single()
        assertEquals(MotionActivityType.IN_VEHICLE, reconstructed.activityType)
        assertEquals(300L, reconstructed.startWallClockEpochMilli)
        assertEquals(500L, reconstructed.endWallClockEpochMilli)
    }

    @Test
    fun `PersistedInterval refuses construction with end at or before start`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedInterval(1, MotionActivityType.IN_VEHICLE, 200L, 150L, 1, "OWN_EXIT")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersistedInterval(1, MotionActivityType.IN_VEHICLE, 200L, 200L, 1, "OWN_EXIT")
        }
        // A genuinely valid interval (end strictly after start) must still construct normally.
        val valid = PersistedInterval(1, MotionActivityType.IN_VEHICLE, 200L, 201L, 1, "OWN_EXIT")
        assertEquals(201L, valid.endWallClockEpochMilli)
    }

    @Test
    fun `every close produced across every scenario in this file satisfies end greater than start`() {
        // A structural sweep, not just the three targeted scenarios above: replays every close this
        // file's own test bodies produce and asserts the invariant directly against each one's own
        // originating interval, so a future edit reintroducing an end<=start path anywhere in
        // applySignal fails a test here even if no single scenario test happens to catch it.
        val scenarios = listOf(
            Triple(
                mapOf(MotionActivityType.IN_VEHICLE to PersistedInterval(1, MotionActivityType.IN_VEHICLE, 0L, null, 1, "OPEN")),
                exit(MotionActivityType.IN_VEHICLE, 3 * 60 * 60 * 1000L),
                vehicleTracked to vehicleInterrupters,
            ),
            Triple(
                mapOf(MotionActivityType.WALKING to PersistedInterval(7, MotionActivityType.WALKING, 1_000L, null, 1, "OPEN")),
                enter(MotionActivityType.RUNNING, 4_000),
                positiveTracked to positiveInterrupters,
            ),
            Triple(
                mapOf(MotionActivityType.WALKING to PersistedInterval(3, MotionActivityType.WALKING, 1_000L, null, 1, "OPEN")),
                enter(MotionActivityType.STILL, 6_000),
                positiveTracked to positiveInterrupters,
            ),
        )
        for ((openByType, signal, types) in scenarios) {
            val (tracked, interrupters) = types
            val result = IntervalReconstructor.applySignal(
                tracked, interrupters, signal, openByType, emptyList(), failClosedIfLateAndUnconfirmed = false,
            )
            for (close in result.closes) {
                val original = openByType.values.single { it.id == close.intervalId }
                assertFalse(
                    "close of interval ${close.intervalId} produced end(${close.endWallClockEpochMilli}) <= start(${original.startWallClockEpochMilli})",
                    close.endWallClockEpochMilli <= original.startWallClockEpochMilli,
                )
            }
        }
    }
}
