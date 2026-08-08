package com.example.stepsplit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.motion.ConvertedSampledActivity
import com.example.stepsplit.data.motion.ConvertedSampledBatch
import com.example.stepsplit.data.motion.ConvertedTransitionEvent
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.domain.validation.MotionActivityType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end tests for strict vehicle-aware step validation, exercising the real
 * [StepRepository] pipeline (import -> validate -> classify -> UI-facing reads) against a real
 * in-memory Room database - never the pure [com.example.stepsplit.domain.validation.StrictStepValidationPolicy]
 * or [com.example.stepsplit.domain.validation.IntervalReconstructor] in isolation (see
 * `StrictStepValidationPolicyTest`/`IntervalReconstructorTest` for those). Unlike
 * `StepRepositoryTest` (which predates this feature and seeds a permanently-open synthetic
 * `WALKING` interval so its own sync/classification/override tests keep working unmodified), every
 * test here sets up its own motion evidence explicitly - that IS the thing under test.
 *
 * A plain mutable [Clock] (not a [kotlinx.coroutines.test.TestCoroutineScheduler]-backed one) is
 * used throughout - none of these tests exercise the in-process finalization *timer*
 * ([StepRepository.rescheduleFinalizationJob]; see `StepRepositoryTest`'s own `TimerTestHarness`
 * for that concern). Timeout-driven transitions are instead exercised by directly calling
 * [StepRepository.finalizeDuePendingBuckets] with an advanced clock reading - the durable,
 * process-death-safe half of that mechanism, and the one this repository's public API actually
 * exposes for tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StepValidationIntegrationTest {

    private class MutableClock(var instant: Instant, private val zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
        override fun instant(): Instant = instant
    }

    /**
     * Throws exactly once, on the [armToThrowOnCall]-th call to [instant] after arming - a plain
     * [RuntimeException] - then returns [instant] normally on every other call. Used to inject a
     * failure at a precise, deterministic point *inside* a still-open Room transaction, without
     * needing to hook Room internals directly - see the atomicity/rollback test below for exactly
     * which call this targets and why. Mirrors `StepRepositoryTest`'s own `ArmableThrowOnceClock`,
     * duplicated locally rather than shared since that one is scheduler-driven (built for the
     * finalization-timer tests) and this one only ever needs a single fixed instant.
     */
    private class ArmableThrowOnceClock(private val instant: Instant) : Clock() {
        private var callsUntilThrow = -1
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun armToThrowOnCall(n: Int) {
            callsUntilThrow = n
        }
        override fun instant(): Instant {
            if (callsUntilThrow > 0) {
                callsUntilThrow--
                if (callsUntilThrow == 0) throw RuntimeException("simulated failure inside the merged evidence+revalidation transaction")
            }
            return instant
        }
    }

    private lateinit var database: StepSplitDatabase
    private lateinit var fakeSource: FakeStepSource
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var clock: MutableClock
    private lateinit var repository: StepRepository

    private val zone: ZoneId = ZoneOffset.UTC
    private val baseEpoch = Instant.parse("2026-03-10T12:00:00Z").epochSecond

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        fakeSource = FakeStepSource()
        settingsRepository = SettingsRepository(context)
        clock = MutableClock(Instant.ofEpochSecond(baseEpoch), zone)
        // A plain, never-advanced scope - none of these tests rely on the local finalization
        // timer firing; timeouts are driven explicitly via finalizeDuePendingBuckets instead (see
        // this class's own doc comment).
        repository = StepRepository(database, fakeSource, settingsRepository, clock, CoroutineScope(StandardTestDispatcher()))
    }

    @After
    fun tearDown() = runTest {
        settingsRepository.resetThresholds()
        settingsRepository.clearSyncFailure()
    }

    private fun transitionEvent(
        activityType: MotionActivityType,
        isEnter: Boolean,
        atEpochSecond: Long,
        bootSessionId: Long = 1,
        bootEpochOffsetMillis: Long = 0,
    ) = ConvertedTransitionEvent(
        activityType = activityType,
        isEnter = isEnter,
        eventElapsedRealtimeMillis = atEpochSecond * 1000,
        bootSessionId = bootSessionId,
        bootEpochOffsetMillis = bootEpochOffsetMillis,
        derivedWallClockEpochMilli = atEpochSecond * 1000,
        receivedAtEpochMilli = atEpochSecond * 1000,
        dedupeKey = "TRANSITION:${activityType.name}:${if (isEnter) "ENTER" else "EXIT"}:$atEpochSecond:$bootSessionId",
    )

    private fun sampledBatch(
        activities: List<Pair<MotionActivityType, Int>>,
        atEpochSecond: Long,
        bootSessionId: Long = 1,
        bootEpochOffsetMillis: Long = 0,
    ) = ConvertedSampledBatch(
        activities = activities.map { (type, confidence) -> ConvertedSampledActivity(type, confidence) },
        eventElapsedRealtimeMillis = atEpochSecond * 1000,
        bootSessionId = bootSessionId,
        bootEpochOffsetMillis = bootEpochOffsetMillis,
        derivedWallClockEpochMilli = atEpochSecond * 1000,
        receivedAtEpochMilli = atEpochSecond * 1000,
        batchId = "$bootSessionId:${atEpochSecond * 1000}",
    )

    // ---- 1. Vehicle veto ----

    @Test
    fun `steps entirely within a vehicle interval are never accepted`() = runTest {
        val vehicleStart = baseEpoch
        val vehicleEnd = baseEpoch + 600
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = vehicleStart)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = vehicleEnd)))

        fakeSource.addInterval(vehicleStart + 120, vehicleStart + 180, 80)
        clock.instant = Instant.ofEpochSecond(vehicleEnd + 300)
        repository.syncNow()

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucket.validationState)
        assertEquals(0L, bucket.acceptedSteps)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
    }

    @Test
    fun `a vehicle interval nested inside an otherwise-covering walking period still rejects, not accepts`() = runTest {
        val start = baseEpoch
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = start - 3600)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = start)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = start + 600)))

        fakeSource.addInterval(start + 120, start + 180, 80)
        clock.instant = Instant.ofEpochSecond(start + 900)
        repository.syncNow()

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucket.validationState)
    }

    @Test
    fun `a sampled batch with top-ranked walking but a qualifying co-present vehicle signal still rejects as vehicle`() = runTest {
        val obsStart = baseEpoch
        repository.ingestSampledBatch(
            sampledBatch(listOf(MotionActivityType.WALKING to 90, MotionActivityType.IN_VEHICLE to 55), atEpochSecond = obsStart + 20),
        )

        fakeSource.addInterval(obsStart, obsStart + 60, 80)
        clock.instant = Instant.ofEpochSecond(obsStart + 200)
        repository.syncNow()

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals(
            "top rank alone must never override the confidence>=50 vehicle veto rule",
            "REJECTED_VEHICLE",
            bucket.validationState,
        )
    }

    // ---- 2. Guard window ----

    @Test
    fun `steps just before a vehicle interval within the guard window are rejected even with walking coverage`() = runTest {
        val vehicleStart = baseEpoch + 3600
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = vehicleStart)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = vehicleStart + 300)))

        // Ends exactly when the vehicle interval starts - fully inside the default 30s
        // guard-before window, even though it never technically overlaps IN_VEHICLE itself.
        val bucketStart = vehicleStart - 60
        fakeSource.addInterval(bucketStart, bucketStart + 60, 80)
        clock.instant = Instant.ofEpochSecond(vehicleStart + 600)
        repository.syncNow()

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucket.validationState)
    }

    @Test
    fun `walking after a vehicle EXIT is accepted only once both the guard window and stability period have elapsed`() = runTest {
        val vehicleEnd = baseEpoch
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = baseEpoch - 600)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = vehicleEnd)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = vehicleEnd + 5)))

        // Too soon: starts exactly at the vehicle EXIT - well within the 30s guard-after window.
        // (Kept minute-aligned like every raw interval in this file - BucketNormalizer splits a
        // non-aligned interval across two adjacent minute buckets, which would defeat the
        // startEpochSecond lookups below.)
        fakeSource.addInterval(vehicleEnd, vehicleEnd + 60, 80)
        clock.instant = Instant.ofEpochSecond(vehicleEnd + 500)
        repository.syncNow()
        val tooSoon = database.stepBucketDao().getAllActive().single { it.startEpochSecond == vehicleEnd }
        assertEquals("REJECTED_VEHICLE", tooSoon.validationState)

        // Comfortably past both the vehicle guard and the walking stability period.
        fakeSource.addInterval(vehicleEnd + 120, vehicleEnd + 180, 80)
        repository.syncNow()
        val later = database.stepBucketDao().getAllActive().single { it.startEpochSecond == vehicleEnd + 120 }
        assertEquals("ACCEPTED_WALKING", later.validationState)
    }

    // ---- 3. Whole-observation rejection ----

    @Test
    fun `a single multi-minute observation spanning both walking and vehicle time is rejected as a whole, not split`() = runTest {
        val obsStart = baseEpoch
        val obsEnd = baseEpoch + 180
        // Three consecutive minute rows sharing ONE observation span - models a genuinely
        // multi-minute raw interval (see StepBucketEntity's own doc comment on
        // observationStartEpochSecond/observationEndEpochSecond) rather than the current
        // production source, which always reports 1-minute-aligned intervals.
        database.stepBucketDao().upsertAll(
            (0 until 3).map { i ->
                StepBucketEntity(
                    source = fakeSource.id,
                    startEpochSecond = obsStart + i * 60L,
                    endEpochSecond = obsStart + i * 60L + 60,
                    steps = 80,
                    zoneId = "UTC",
                    localDate = "2026-03-10",
                    importedAtEpochSecond = obsStart,
                    validationState = "PENDING",
                    observationStartEpochSecond = obsStart,
                    observationEndEpochSecond = obsEnd,
                )
            },
        )

        // Walking evidence covers the whole window; a vehicle veto covers only the middle third.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = obsStart - 3600)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = obsStart + 90)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = obsStart + 120)))

        val rows = database.stepBucketDao().getFrom(fakeSource.id, obsStart)
        assertEquals(3, rows.size)
        assertTrue(
            "a vehicle veto anywhere in the observation must reject the entire observation, not just the overlapping minute",
            rows.all { it.validationState == "REJECTED_VEHICLE" },
        )
        assertTrue(rows.all { it.acceptedSteps == 0L })
    }

    // ---- 4. Never approve without positive evidence ----

    @Test
    fun `STILL evidence alone never approves a bucket`() = runTest {
        repository.ingestSampledBatch(sampledBatch(listOf(MotionActivityType.STILL to 90), atEpochSecond = baseEpoch + 30))

        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 500)
        repository.syncNow()

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_UNVERIFIED", bucket.validationState)
        assertEquals(0L, bucket.acceptedSteps)
    }

    @Test
    fun `missing evidence entirely times out to rejected, never silently accepted`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 30)
        repository.syncNow()
        assertEquals("PENDING", database.stepBucketDao().getAllActive().single().validationState)

        clock.instant = Instant.ofEpochSecond(baseEpoch + 60 + 200)
        repository.finalizeDuePendingBuckets(clock.instant)

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_UNVERIFIED", bucket.validationState)
        assertEquals("AWAITING_EVIDENCE_TIMEOUT", bucket.rejectionReason)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
    }

    // ---- 5. Sampled positive evidence ----

    @Test
    fun `at least two consecutive qualifying sampled WALKING results, closely spaced, accept the bucket they fully bracket`() = runTest {
        val obsStart = baseEpoch
        val obsEnd = baseEpoch + 60
        val sampleTimes = listOf(obsStart - 15, obsStart, obsStart + 15, obsStart + 30, obsStart + 45, obsStart + 60)
        for (t in sampleTimes) {
            repository.ingestSampledBatch(sampledBatch(listOf(MotionActivityType.WALKING to 80), atEpochSecond = t))
        }

        fakeSource.addInterval(obsStart, obsEnd, 80)
        clock.instant = Instant.ofEpochSecond(obsEnd + 5)
        repository.syncNow()

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("ACCEPTED_WALKING", bucket.validationState)
        assertEquals(80L, bucket.acceptedSteps)
    }

    // ---- 6. Delayed evidence retroactively changes a decision ----

    @Test
    fun `a delayed vehicle ENTER retroactively rejects an already-imported still-pending bucket`() = runTest {
        val start = baseEpoch
        fakeSource.addInterval(start, start + 60, 80)
        clock.instant = Instant.ofEpochSecond(start + 30)
        repository.syncNow()
        assertEquals("PENDING", database.stepBucketDao().getAllActive().single().validationState)

        // The vehicle ENTER is delivered late, but its own event time falls inside the bucket's
        // observation span.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = start + 10)))

        val rejected = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", rejected.validationState)
        assertEquals(0L, rejected.acceptedSteps)
    }

    @Test
    fun `delayed vehicle evidence revokes an already-accepted bucket and lifetime stats drop in the same update`() = runTest {
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch - 3600)))

        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 200)
        repository.syncNow()
        assertEquals("ACCEPTED_WALKING", database.stepBucketDao().getAllActive().single().validationState)
        assertEquals(80L, repository.observeLifetimeStats().first().lifetimeSteps)

        // A vehicle ENTER, delivered late, whose own event time falls inside the already-accepted
        // bucket's observation span.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = baseEpoch + 20)))

        val revoked = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", revoked.validationState)
        assertEquals(0L, revoked.acceptedSteps)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
    }

    // ---- 7. REJECTED_VEHICLE is terminal - never silently restored ----

    @Test
    fun `a rejected-vehicle bucket is never restored by later walking evidence`() = runTest {
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = baseEpoch)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = baseEpoch + 60)))

        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 200)
        repository.syncNow()
        assertEquals("REJECTED_VEHICLE", database.stepBucketDao().getAllActive().single().validationState)

        // Strong, unambiguous later walking evidence covering the exact same window - must never
        // un-reject a real vehicle detection (rule 11).
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch - 100)))
        for (t in listOf(baseEpoch - 5, baseEpoch + 10, baseEpoch + 25, baseEpoch + 40, baseEpoch + 55, baseEpoch + 70)) {
            repository.ingestSampledBatch(sampledBatch(listOf(MotionActivityType.WALKING to 90), atEpochSecond = t))
        }

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucket.validationState)
        assertEquals(0L, bucket.acceptedSteps)
    }

    // ---- 8. Idempotency ----

    @Test
    fun `replaying identical transition events and a duplicate sync is a no-op, not a duplicate`() = runTest {
        val event = transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch)
        repository.ingestTransitionEvents(listOf(event))
        repository.ingestTransitionEvents(listOf(event))
        repository.ingestTransitionEvents(listOf(event.copy()))

        assertEquals(1, database.activityIntervalDao().count())
        assertEquals(1, database.motionEvidenceDao().count())

        fakeSource.addInterval(baseEpoch + 120, baseEpoch + 180, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 300)
        repository.syncNow()
        repository.syncNow()

        assertEquals(1, database.stepBucketDao().count())
        assertEquals(80L, repository.observeLifetimeStats().first().lifetimeSteps)
    }

    // ---- 9. Reboot / boot-session boundary safety ----

    @Test
    fun `a reboot force-closes an open interval and buckets after it are not silently accepted from stale cross-boot state`() = runTest {
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch, bootSessionId = 1)))

        val rebootAt = baseEpoch + 600
        clock.instant = Instant.ofEpochSecond(rebootAt)
        repository.handleTemporalDiscontinuity(newBootSessionId = 2)

        // The pre-reboot interval survives as a closed historical record, not a deleted one.
        val preRebootStillRecorded = database.activityIntervalDao()
            .getOverlapping(listOf("WALKING"), baseEpoch * 1000L - 1000, baseEpoch * 1000L + 1000)
        assertTrue(preRebootStillRecorded.any { it.startWallClockEpochMilli == baseEpoch * 1000L && it.endWallClockEpochMilli != null })

        // No fresh evidence has arrived yet in the new boot session - a bucket entirely after the
        // reboot must not be accepted purely from the pre-reboot interval's stale state.
        fakeSource.addInterval(rebootAt + 60, rebootAt + 120, 80)
        clock.instant = Instant.ofEpochSecond(rebootAt + 130)
        repository.syncNow()
        val stillUnverified = database.stepBucketDao().getAllActive().single()
        assertTrue(
            "a bucket after a reboot must never be ACCEPTED purely from pre-reboot state",
            stillUnverified.validationState == "PENDING" || stillUnverified.validationState == "REJECTED_UNVERIFIED",
        )

        // Fresh, same-epoch evidence after the reboot restores normal counting automatically - no
        // manual recovery step, no permanent lockout.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = rebootAt + 30, bootSessionId = 2)))
        val restored = database.stepBucketDao().getAllActive().single()
        assertEquals("ACCEPTED_WALKING", restored.validationState)
    }

    // ---- 10. Process-death-while-pending safety ----

    @Test
    fun `a still-pending bucket survives repository recreation and can still be finalized by the new instance`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 30)
        repository.syncNow()
        assertEquals("PENDING", database.stepBucketDao().getAllActive().single().validationState)

        // Simulate process death and restart: a fresh repository instance against the same
        // durable database - finalizationJob/recoveryPerformed in-memory state is gone, but the
        // PENDING row itself is not.
        val freshClock = MutableClock(Instant.ofEpochSecond(baseEpoch + 60 + 200), zone)
        val freshRepository = StepRepository(database, fakeSource, settingsRepository, freshClock, CoroutineScope(StandardTestDispatcher()))
        freshRepository.finalizeDuePendingBuckets(freshClock.instant)

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_UNVERIFIED", bucket.validationState)
    }

    // ---- 11. Today/History/Sessions/Stats exclude pending/rejected ----

    @Test
    fun `observeDailyBreakdowns and observeLifetimeStats never include pending or rejected steps`() = runTest {
        // Accepted: real walking evidence.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch - 3600)))
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)

        // Rejected: a vehicle-vetoed minute later the same day.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = baseEpoch + 3600)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = baseEpoch + 3660)))
        fakeSource.addInterval(baseEpoch + 3600, baseEpoch + 3660, 80)

        // Pending: freshly-imported, no evidence yet, still inside the pending window.
        fakeSource.addInterval(baseEpoch + 7200, baseEpoch + 7260, 80)

        clock.instant = Instant.ofEpochSecond(baseEpoch + 7260 + 10)
        repository.syncNow()

        val states = database.stepBucketDao().getAllActive().associate { it.startEpochSecond to it.validationState }
        assertEquals("ACCEPTED_WALKING", states[baseEpoch])
        assertEquals("REJECTED_VEHICLE", states[baseEpoch + 3600])
        assertEquals("PENDING", states[baseEpoch + 7200])

        assertEquals(80L, repository.observeLifetimeStats().first().lifetimeSteps)

        val today = Instant.ofEpochSecond(baseEpoch).atZone(zone).toLocalDate()
        val breakdown = repository.observeDailyBreakdowns(listOf(today)).first().getValue(today)
        assertEquals(80L, breakdown.totalSteps)
    }

    // ---- 12. Legacy history preservation ----

    @Test
    fun `pre-existing legacy history is preserved and reported separately, never merged into verified stats`() = runTest {
        // Pre-existing history from before this app collected motion evidence at all - migrated
        // as LEGACY_UNVERIFIED (see StepSplitDatabase's own MIGRATION_3_4 doc comment).
        database.stepBucketDao().upsertAll(
            listOf(
                StepBucketEntity(
                    source = fakeSource.id,
                    startEpochSecond = baseEpoch - 86400,
                    endEpochSecond = baseEpoch - 86400 + 60,
                    steps = 500,
                    zoneId = "UTC",
                    localDate = "2026-03-09",
                    importedAtEpochSecond = baseEpoch - 86400,
                    validationState = "LEGACY_UNVERIFIED",
                    acceptedSteps = 0,
                ),
            ),
        )

        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
        assertEquals(500L, repository.observeLegacyStats().first().lifetimeSteps)

        // A later re-sync reporting the SAME minute (e.g. the provider's own retention window
        // still covers it) must never reset it out of LEGACY_UNVERIFIED, even with an updated raw
        // value - historical motion evidence for that period will never exist.
        fakeSource.addInterval(baseEpoch - 86400, baseEpoch - 86400 + 60, 500)
        clock.instant = Instant.ofEpochSecond(baseEpoch)
        repository.syncNow()

        val row = database.stepBucketDao().getAllActive().single { it.startEpochSecond == baseEpoch - 86400 }
        assertEquals("LEGACY_UNVERIFIED", row.validationState)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
        assertEquals(500L, repository.observeLegacyStats().first().lifetimeSteps)
    }

    // ---- 13. Provider reconciliation can never erase older stats ----

    @Test
    fun `a sync's overlap window never deletes buckets outside it - upsert-only, never delete`() = runTest {
        val oldStart = baseEpoch - 7 * 3600 // well beyond StepRepository.SYNC_OVERLAP (6h), minute-aligned
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = oldStart - 3600)))
        fakeSource.addInterval(oldStart, oldStart + 60, 80)
        clock.instant = Instant.ofEpochSecond(oldStart + 200)
        repository.syncNow()
        assertEquals(80L, repository.observeLifetimeStats().first().lifetimeSteps)

        // A much later sync whose own read window no longer reaches back this far - the
        // provider's own retention window has moved on. The old accepted bucket must survive
        // untouched.
        fakeSource.clearIntervals()
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 1)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 200)
        repository.syncNow()

        assertTrue(repository.observeLifetimeStats().first().lifetimeSteps >= 80L)
        val oldRow = database.stepBucketDao().getAllActive().first { it.startEpochSecond == oldStart }
        assertEquals("ACCEPTED_WALKING", oldRow.validationState)
        assertEquals(80L, oldRow.acceptedSteps)
    }

    // ---- 14. No manufacturer/model checks, no accelerometer fallback (source independence) ----

    @Test
    fun `validation runs identically for any StepSource id - no manufacturer or model branching`() = runTest {
        val otherSource = FakeStepSource(id = "future_sensor_step_counter")
        val otherRepository = StepRepository(database, otherSource, settingsRepository, clock, CoroutineScope(StandardTestDispatcher()))

        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch - 3600)))
        otherSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 200)
        otherRepository.syncNow()

        // Exactly the same validator, evaluated against the same shared motion-evidence state,
        // regardless of which StepSource id produced the raw observation - a future
        // Sensor.TYPE_STEP_COUNTER source passes through the identical validator.
        val bucket = database.stepBucketDao().getAllActive().single { it.source == otherSource.id }
        assertEquals("ACCEPTED_WALKING", bucket.validationState)
    }

    // ---- 15. Atomicity: evidence + interval mutation + bucket revalidation commit as one unit ----

    @Test
    fun `a failure between interval mutation and bucket revalidation rolls back atomically - no half-applied state`() = runTest {
        // An already-accepted WALKING bucket that a delayed vehicle ENTER should revoke.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch - 3600)))
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 200)
        repository.syncNow()
        assertEquals("ACCEPTED_WALKING", database.stepBucketDao().getAllActive().single().validationState)
        val intervalCountBefore = database.activityIntervalDao().count()
        val motionEvidenceCountBefore = database.motionEvidenceDao().count()

        // A clock that throws exactly once, timed to fire from inside
        // recomputeClassificationWithinTransaction - i.e. the 2nd StepRepository-side clock read
        // for one ingestTransitionEvents call (1st: the "now" captured at the very start; 2nd:
        // computedAt inside the classifier recompute) - which only runs AFTER interval mutation
        // (applyReconciliationSignalLocked) and bucket revalidation (revalidateSpanWithinTransactionLocked)
        // have already executed within the same still-open transaction. If those two aren't
        // actually one atomic unit with this recompute, this is exactly the window where a process
        // death would leave confirmed vehicle evidence committed while the steps it should have
        // revoked remain ACCEPTED.
        val throwingClock = ArmableThrowOnceClock(Instant.ofEpochSecond(baseEpoch + 300))
        val failingRepository = StepRepository(database, fakeSource, settingsRepository, throwingClock, CoroutineScope(StandardTestDispatcher()))
        throwingClock.armToThrowOnCall(2)

        var thrown = false
        try {
            failingRepository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = baseEpoch + 20)))
        } catch (e: RuntimeException) {
            thrown = true
            assertTrue(e.message.orEmpty().contains("simulated failure"))
        }
        assertTrue("the injected failure must actually have fired for this test to prove anything", thrown)

        // Nothing committed: the bucket is still ACCEPTED_WALKING (not revoked), and no new
        // activity_intervals/motion_evidence rows exist either - evidence insertion, interval
        // mutation, and bucket revalidation all rolled back together as one unit, exactly as if the
        // failed call had never happened at all.
        val bucketAfter = database.stepBucketDao().getAllActive().single()
        assertEquals("ACCEPTED_WALKING", bucketAfter.validationState)
        assertEquals(80L, bucketAfter.acceptedSteps)
        assertEquals(
            "no half-applied interval mutation may survive the rollback",
            intervalCountBefore,
            database.activityIntervalDao().count(),
        )
        assertEquals(
            "no half-applied motion evidence may survive the rollback",
            motionEvidenceCountBefore,
            database.motionEvidenceDao().count(),
        )
        assertEquals(80L, repository.observeLifetimeStats().first().lifetimeSteps)
    }

    // ---- 16. Exact affected-span revalidation reach (not a fixed +/-45min radius) ----

    @Test
    fun `a delayed multi-hour vehicle ENTER and EXIT revalidate every bucket across the whole ride, including the middle, atomically`() = runTest {
        val rideStart = baseEpoch
        val rideMinutes = 100 // > 90 minutes, and specifically > 2x the 45-minute reconciliation window
        val rideEnd = rideStart + rideMinutes * 60L

        // Real walking evidence covers the whole ride comfortably - every minute is imported and
        // accepted BEFORE any vehicle evidence exists at all (the vehicle transition is reported
        // late, well after the fact).
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = rideStart - 3600)))
        for (i in 0 until rideMinutes) {
            fakeSource.addInterval(rideStart + i * 60L, rideStart + i * 60L + 60, 80)
        }
        clock.instant = Instant.ofEpochSecond(rideEnd + 200)
        repository.syncNow()

        val acceptedBefore = database.stepBucketDao().getAllActive().filter { it.startEpochSecond in rideStart until rideEnd }
        assertEquals(rideMinutes, acceptedBefore.size)
        assertTrue(acceptedBefore.all { it.validationState == "ACCEPTED_WALKING" })
        assertEquals(rideMinutes * 80L, repository.observeLifetimeStats().first().lifetimeSteps)
        assertTrue("a 100-minute steady walk must classify as a session before the vehicle evidence arrives", repository.observeSessions().first().isNotEmpty())

        // The delayed vehicle ENTER and EXIT bracket the whole ride. Neither one's own timestamp is
        // anywhere near the ride's middle minutes - under the old fixed +/-45-minute-around-the-
        // event radius, the ENTER's own window reaches only the first 45 minutes and the EXIT's
        // only the last 45, leaving a real gap in the middle of this 100-minute ride untouched by
        // either.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = true, atEpochSecond = rideStart)))
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.IN_VEHICLE, isEnter = false, atEpochSecond = rideEnd)))

        val afterRows = database.stepBucketDao().getAllActive().filter { it.startEpochSecond in rideStart until rideEnd }
        assertEquals(rideMinutes, afterRows.size)
        assertTrue(
            "every bucket across the whole ride, including the middle, must become REJECTED_VEHICLE",
            afterRows.all { it.validationState == "REJECTED_VEHICLE" },
        )
        assertTrue(afterRows.all { it.acceptedSteps == 0L })

        // The specific bucket at minute 50 falls squarely inside the old bug's blind spot: more
        // than 45 minutes after the ENTER (minute 0) and more than 45 minutes before the EXIT
        // (minute 100) - the old code would never have re-evaluated it at all.
        val middleBucket = afterRows.single { it.startEpochSecond == rideStart + 50 * 60L }
        assertEquals("REJECTED_VEHICLE", middleBucket.validationState)
        assertEquals(0L, middleBucket.acceptedSteps)

        // Totals and sessions drop atomically along with every bucket - never a stale total/session
        // visible with some buckets already revoked and others not.
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
        assertTrue(
            "the session built from this ride must be gone once every one of its buckets is revoked",
            repository.observeSessions().first().none { it.startEpochSecond in (rideStart - 3600)..rideEnd },
        )
    }

    // ---- 17. Sampled evidence is direct policy input, independent of interval mutation ----

    /**
     * Accepts one bucket justified by a WALKING interval, then CLOSES that interval well after the
     * bucket's own observation ends - by the time the late sampled batch below arrives, nothing is
     * open for the positive group at all, so [com.example.stepsplit.domain.validation.ReconciliationSignal.SampledInterrupt]
     * has nothing to close and IntervalReconstructor produces an empty mutation set. This is
     * exactly the setup the bug required: an accepted bucket with no currently-open positive
     * interval for a sampled interrupt to touch.
     */
    private suspend fun acceptBucketThenCloseItsJustifyingInterval(): StepBucketEntity {
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = true, atEpochSecond = baseEpoch - 3600)))
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 80)
        clock.instant = Instant.ofEpochSecond(baseEpoch + 200)
        repository.syncNow()
        val accepted = database.stepBucketDao().getAllActive().single()
        assertEquals("ACCEPTED_WALKING", accepted.validationState)

        // Closed well after the bucket's own observation end (baseEpoch+60) - closing here must not
        // itself un-accept the bucket, since coverage up to the close time still fully spans it.
        repository.ingestTransitionEvents(listOf(transitionEvent(MotionActivityType.WALKING, isEnter = false, atEpochSecond = baseEpoch + 500)))
        val stillAccepted = database.stepBucketDao().getAllActive().single()
        assertEquals("ACCEPTED_WALKING", stillAccepted.validationState)
        assertTrue("nothing must be open for the positive group at this point", database.activityIntervalDao().getAllOpen().isEmpty())
        return stillAccepted
    }

    @Test
    fun `a late sampled batch with a qualifying top vehicle activity revokes an accepted bucket even with no open interval to close`() = runTest {
        acceptBucketThenCloseItsJustifyingInterval()

        repository.ingestSampledBatch(sampledBatch(listOf(MotionActivityType.IN_VEHICLE to 90), atEpochSecond = baseEpoch + 20))

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucket.validationState)
        assertEquals(0L, bucket.acceptedSteps)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
        assertTrue(repository.observeSessions().first().isEmpty())
    }

    @Test
    fun `a late sampled batch with top WALKING but a co-present qualifying vehicle signal still revokes an accepted bucket with no open interval`() = runTest {
        acceptBucketThenCloseItsJustifyingInterval()

        // Top activity is WALKING (90 > 55) - ingestSampledBatch never builds a SampledInterrupt at
        // all in this case (see the "topActivity != WALKING/RUNNING" guard), so the interval-
        // mutation path contributes nothing whatsoever. Only the direct sampled-evidence path can
        // catch this.
        repository.ingestSampledBatch(
            sampledBatch(listOf(MotionActivityType.WALKING to 90, MotionActivityType.IN_VEHICLE to 55), atEpochSecond = baseEpoch + 20),
        )

        val bucket = database.stepBucketDao().getAllActive().single()
        assertEquals(
            "a co-present qualifying vehicle signal (confidence>=50) must revoke acceptance even when it isn't the batch's own top activity",
            "REJECTED_VEHICLE",
            bucket.validationState,
        )
        assertEquals(0L, bucket.acceptedSteps)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
    }

    @Test
    fun `replaying the identical sampled batch that revoked an accepted bucket remains idempotent`() = runTest {
        acceptBucketThenCloseItsJustifyingInterval()

        val batch = sampledBatch(listOf(MotionActivityType.IN_VEHICLE to 90), atEpochSecond = baseEpoch + 20)
        repository.ingestSampledBatch(batch)
        val firstMotionEvidenceCount = database.motionEvidenceDao().count()
        val firstIntervalCount = database.activityIntervalDao().count()
        val bucketAfterFirst = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucketAfterFirst.validationState)

        // An identical replay (same batchId, same activities, same dedupeKey) must be a pure no-op:
        // no duplicate motion_evidence rows, no duplicate/altered activity_intervals rows, and the
        // bucket's own state must not be touched a second time.
        repository.ingestSampledBatch(batch)

        assertEquals(firstMotionEvidenceCount, database.motionEvidenceDao().count())
        assertEquals(firstIntervalCount, database.activityIntervalDao().count())
        val bucketAfterReplay = database.stepBucketDao().getAllActive().single()
        assertEquals("REJECTED_VEHICLE", bucketAfterReplay.validationState)
        assertEquals(0L, bucketAfterReplay.acceptedSteps)
        assertEquals(0L, repository.observeLifetimeStats().first().lifetimeSteps)
    }
}
