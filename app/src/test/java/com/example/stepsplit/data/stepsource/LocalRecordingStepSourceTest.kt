package com.example.stepsplit.data.stepsource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.fitness.FitnessStatusCodes
import com.google.android.gms.fitness.data.Bucket
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.fitness.result.DataReadResult
import com.google.android.gms.fitness.result.LocalDataReadResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A [LocalRecordingGateway] test double whose `subscribe()`/`readData()` throw (or return)
 * whatever a single test arms them with - the "narrow injectable wrapper" that makes
 * [LocalRecordingStepSource.subscribeAndRecordOutcome]/[LocalRecordingStepSource.readAndRecordOutcome]
 * testable without a live Play Services connection: those two are deliberately independent of
 * [LocalRecordingStepSource.checkAvailability] (which needs a real/Robolectric [Context] and Play
 * services shadow), so tests here drive them directly.
 */
private class FakeLocalRecordingGateway(
    private val subscribeException: Throwable? = null,
    private val readException: Throwable? = null,
    private val readResponse: LocalDataReadResponse = successResponse(),
) : LocalRecordingGateway {
    override suspend fun subscribe() {
        subscribeException?.let { throw it }
    }

    override suspend fun readData(request: LocalDataReadRequest): LocalDataReadResponse {
        readException?.let { throw it }
        return readResponse
    }

    companion object {
        fun successResponse(): LocalDataReadResponse =
            LocalDataReadResponse(DataReadResult(emptyList<DataSet>(), emptyList<Bucket>(), Status(CommonStatusCodes.SUCCESS)))
    }
}

/**
 * A [StepSourceHealthSink] that throws on every call (or a configured [exception] - by default
 * a plain [RuntimeException] to prove "log and ignore", or a [CancellationException] to prove it
 * still propagates) - see [LocalRecordingStepSource.recordSafely]'s own doc comment for why every
 * health-recording call site must survive this without affecting the real subscribe/read outcome.
 * Call counts are tracked so a test can assert the recorder was actually invoked (not just that the
 * method under test happened to not need it). Non-suspending, matching the real
 * [StepSourceHealthSink] contract - a synchronous throw here still exercises [LocalRecordingStepSource.recordSafely]'s
 * catch/rethrow logic exactly as a misbehaving real implementation would.
 */
private class ThrowingHealthRecorder(
    private val exception: Throwable = RuntimeException("simulated health-recording failure"),
) : StepSourceHealthSink {
    var recordSubscriptionSuccessCalls = 0
        private set
    var recordSubscriptionFailureCalls = 0
        private set
    var recordReadAttemptCalls = 0
        private set
    var recordReadSuccessCalls = 0
        private set
    var recordReadFailureCalls = 0
        private set

    override fun recordSubscriptionSuccess(atEpochSecond: Long) {
        recordSubscriptionSuccessCalls++
        throw exception
    }

    override fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) {
        recordSubscriptionFailureCalls++
        throw exception
    }

    override fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) {
        recordReadAttemptCalls++
        throw exception
    }

    override fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) {
        recordReadSuccessCalls++
        throw exception
    }

    override fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) {
        recordReadFailureCalls++
        throw exception
    }
}

/**
 * Test-only bridge from the non-suspend [StepSourceHealthSink] contract back to a suspend
 * [StepSourceHealthRecorder] delegate (typically a real [StepSourceHealthStore]), via
 * [kotlinx.coroutines.runBlocking] - so a test can assert on [StepSourceHealthStore.snapshot]
 * immediately after calling an acquisition method, without needing
 * [AsyncStepSourceHealthRecorder]'s own async draining (real DataStore writes dispatch onto their
 * own internal executor, not this test's [kotlinx.coroutines.test.TestCoroutineScheduler], so
 * `advanceUntilIdle()` cannot deterministically wait for them the way it can for a simple in-memory
 * delegate - confirmed empirically). [AsyncStepSourceHealthRecorder]'s own ordering/timeout/failure-
 * tolerance contract has its own dedicated tests (`AsyncStepSourceHealthRecorderTest`) and the
 * "never completes" section below - this class exists only to test [LocalRecordingStepSource]'s
 * OWN outcome-to-`ApiFailure`-to-sink mapping in isolation, never used in production.
 */
private class SynchronousTestHealthSink(private val delegate: StepSourceHealthRecorder) : StepSourceHealthSink {
    override fun recordSubscriptionSuccess(atEpochSecond: Long) = runBlocking { delegate.recordSubscriptionSuccess(atEpochSecond) }
    override fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) = runBlocking { delegate.recordSubscriptionFailure(failure, atEpochSecond) }
    override fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) =
        runBlocking { delegate.recordReadAttempt(atEpochSecond, windowStartEpochSecond, windowEndEpochSecond) }
    override fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) =
        runBlocking { delegate.recordReadSuccess(intervalCount, latestSampleEpochSecond, atEpochSecond) }
    override fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) = runBlocking { delegate.recordReadFailure(failure, atEpochSecond) }
}

/**
 * A [StepSourceHealthRecorder] whose every method suspends forever ([awaitCancellation] - never
 * returns, never throws, only ever ends via cancellation) - the delegate behind
 * [AsyncStepSourceHealthRecorder] in the "never completes" regression tests below. Proves the
 * async telemetry boundary itself, not just [LocalRecordingStepSource.recordSafely]'s try/catch:
 * a `try`/`catch` cannot protect against a suspension that never completes at all, only against one
 * that throws - see [AsyncStepSourceHealthRecorder]'s own doc comment.
 */
private class NeverCompletingHealthRecorder : StepSourceHealthRecorder {
    override suspend fun recordSubscriptionSuccess(atEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long): Nothing = awaitCancellation()
}

/**
 * Exercises [LocalRecordingStepSource] in three layers:
 *
 * 1. [toRawIntervalsOrThrow] - the response-status check pulled out of
 *    [LocalRecordingStepSource.readAndRecordOutcome] - directly, without a real Play Services
 *    connection or a mocking framework. [LocalDataReadResponse] and its underlying
 *    [DataReadResult] are both plain public Google classes constructible outside the SDK, so a
 *    "completed Task, failed response" can be built exactly as play-services-fitness 21.3.0 itself
 *    would deliver one. (A *positive*-sample response would additionally need a constructible
 *    [Bucket]/[DataSet]/`DataPoint` graph, which requires play-services-fitness-internal types
 *    (`RawBucket`'s `Session`/nanosecond fields) that are impractical to fabricate correctly from
 *    outside the SDK - exactly the gap [FakeLocalRecordingGateway] below exists to route around:
 *    the positive-sample health-recording behavior is instead proven directly against
 *    [StepSourceHealthStore] in `StepSourceHealthStoreTest`, one layer below the GMS parsing.)
 * 2. [LocalRecordingStepSource.subscribeAndRecordOutcome]/[LocalRecordingStepSource.readAndRecordOutcome]
 *    against [FakeLocalRecordingGateway] - proving the real production wiring (exception -> sanitized
 *    [ApiFailure] -> [StepSourceHealthStore]) actually runs, not just the pure mapping function in
 *    isolation.
 * 3. The same two functions against [ThrowingHealthRecorder] - proving a broken diagnostic write can
 *    never affect the real subscribe/read outcome (see [LocalRecordingStepSource.recordSafely]).
 */
@RunWith(RobolectricTestRunner::class)
class LocalRecordingStepSourceTest {

    @Test
    fun `a non-success status throws instead of being treated as an empty successful read`() {
        val failed = LocalDataReadResponse(
            DataReadResult(emptyList<DataSet>(), emptyList<Bucket>(), Status(CommonStatusCodes.NETWORK_ERROR)),
        )

        val exception = assertThrows(StepSourceReadException::class.java) { failed.toRawIntervalsOrThrow() }
        assertTrue(exception.message!!.contains(CommonStatusCodes.NETWORK_ERROR.toString()))
    }

    @Test
    fun `a success status with no buckets is a genuinely empty read, not a failure`() {
        val emptySuccess = LocalDataReadResponse(
            DataReadResult(emptyList<DataSet>(), emptyList<Bucket>(), Status(CommonStatusCodes.SUCCESS)),
        )

        assertEquals(emptyList<RawStepInterval>(), emptySuccess.toRawIntervalsOrThrow())
    }

    @Test
    fun `a non-success status attaches a sanitized ApiFailure derived from the real status code`() {
        val failed = LocalDataReadResponse(
            DataReadResult(emptyList<DataSet>(), emptyList<Bucket>(), Status(CommonStatusCodes.SIGN_IN_REQUIRED)),
        )

        val exception = assertThrows(StepSourceReadException::class.java) { failed.toRawIntervalsOrThrow() }
        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, exception.apiFailure?.category)
        assertEquals(CommonStatusCodes.SIGN_IN_REQUIRED, exception.apiFailure?.statusCode)
    }

    @Test
    fun `a read-response API_EXCEPTION status is SUBSCRIPTION_INVALID - toRawIntervalsOrThrow always parses a readData response`() {
        val failed = LocalDataReadResponse(
            DataReadResult(emptyList<DataSet>(), emptyList<Bucket>(), Status(FitnessStatusCodes.API_EXCEPTION)),
        )

        val exception = assertThrows(StepSourceReadException::class.java) { failed.toRawIntervalsOrThrow() }
        assertEquals(ApiFailureCategory.SUBSCRIPTION_INVALID, exception.apiFailure?.category)
    }

    // ---- subscribeAndRecordOutcome / readAndRecordOutcome against a fake gateway ----

    // Robolectric does not guarantee a fresh Preferences DataStore file per test method (see
    // StepSourceHealthStoreTest's own doc comment) - cleared immediately so an earlier test's
    // writes can never leak into this test's own before/after snapshot assertions.
    /**
     * Wires a [LocalRecordingStepSource] through [SynchronousTestHealthSink] (never the raw
     * [StepSourceHealthStore] directly, which [LocalRecordingStepSource]'s constructor no longer
     * even accepts - it requires a [StepSourceHealthSink], and [StepSourceHealthStore] doesn't
     * implement one) - see that class's own doc comment for why. Robolectric does not guarantee a
     * fresh Preferences DataStore file per test method (see `StepSourceHealthStoreTest`'s own doc
     * comment) - [healthStore] is cleared immediately so an earlier test's writes can never leak
     * into this test's own before/after snapshot assertions.
     */
    private suspend fun newSource(gateway: LocalRecordingGateway): Pair<LocalRecordingStepSource, StepSourceHealthStore> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val healthStore = StepSourceHealthStore(context).also { it.clear() }
        val source = LocalRecordingStepSource(context, gateway = gateway, healthStore = SynchronousTestHealthSink(healthStore))
        return source to healthStore
    }

    private fun readRequest(): LocalDataReadRequest = LocalDataReadRequest.Builder()
        .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
        .bucketByTime(1, TimeUnit.MINUTES)
        .setTimeRange(WINDOW_START, WINDOW_END, TimeUnit.SECONDS)
        .build()

    @Test
    fun `a successful subscription is recorded as such and returns true`() = runTest {
        val (source, healthStore) = newSource(FakeLocalRecordingGateway())

        assertTrue(source.subscribeAndRecordOutcome())

        val snapshot = healthStore.snapshot.first()
        assertEquals(true, snapshot.latestSubscriptionSucceeded)
    }

    @Test
    fun `each mapped subscription failure category is recorded with its real status code, and subscribe returns false`() = runTest {
        val (source, healthStore) = newSource(
            FakeLocalRecordingGateway(subscribeException = ApiException(Status(CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED))),
        )

        assertFalse(source.subscribeAndRecordOutcome())

        val snapshot = healthStore.snapshot.first()
        assertEquals(false, snapshot.latestSubscriptionSucceeded)
        assertEquals(ApiFailureCategory.PLAY_SERVICES_UPDATE_REQUIRED, snapshot.latestSubscriptionFailureCategory)
        assertEquals(CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED, snapshot.latestSubscriptionFailureStatusCode)
    }

    @Test
    fun `ConnectionResult API_UNAVAILABLE during subscribe is recorded as PACKAGE_OR_API_UNAVAILABLE`() = runTest {
        val (source, healthStore) = newSource(
            FakeLocalRecordingGateway(subscribeException = ApiException(Status(ConnectionResult.API_UNAVAILABLE))),
        )

        assertFalse(source.subscribeAndRecordOutcome())

        assertEquals(ApiFailureCategory.PACKAGE_OR_API_UNAVAILABLE, healthStore.snapshot.first().latestSubscriptionFailureCategory)
    }

    @Test
    fun `an API_EXCEPTION during subscribe is NOT recorded as SUBSCRIPTION_INVALID - that mapping only applies to reads`() = runTest {
        val (source, healthStore) = newSource(
            FakeLocalRecordingGateway(subscribeException = ApiException(Status(FitnessStatusCodes.API_EXCEPTION))),
        )

        assertFalse(source.subscribeAndRecordOutcome())

        assertEquals(ApiFailureCategory.OTHER_API_FAILURE, healthStore.snapshot.first().latestSubscriptionFailureCategory)
    }

    @Test
    fun `a bare SecurityException during subscribe is recorded as a permission failure`() = runTest {
        val (source, healthStore) = newSource(FakeLocalRecordingGateway(subscribeException = SecurityException("revoked")))

        assertFalse(source.subscribeAndRecordOutcome())

        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, healthStore.snapshot.first().latestSubscriptionFailureCategory)
    }

    @Test
    fun `cancellation during subscribe propagates and is never recorded as an ordinary failure`() = runTest {
        val (source, healthStore) = newSource(FakeLocalRecordingGateway(subscribeException = CancellationException("cancelled")))

        try {
            source.subscribeAndRecordOutcome()
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(
            "a cancellation must never be recorded as a subscription outcome at all",
            null,
            healthStore.snapshot.first().latestSubscriptionSucceeded,
        )
    }

    @Test
    fun `a read failure from the underlying Task is mapped, recorded, and rethrown as StepSourceReadException`() = runTest {
        val (source, healthStore) = newSource(
            FakeLocalRecordingGateway(readException = ApiException(Status(FitnessStatusCodes.INVALID_PERMISSION))),
        )

        val exception = try {
            source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)
            null
        } catch (e: StepSourceReadException) {
            e
        }

        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, exception?.apiFailure?.category)
        val snapshot = healthStore.snapshot.first()
        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, snapshot.latestReadFailureCategory)
    }

    @Test
    fun `an API_EXCEPTION Task failure during read is recorded as SUBSCRIPTION_INVALID, per readData's documented Throws entry`() = runTest {
        val (source, healthStore) = newSource(
            FakeLocalRecordingGateway(readException = ApiException(Status(FitnessStatusCodes.API_EXCEPTION))),
        )

        try {
            source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)
            fail("expected a StepSourceReadException")
        } catch (e: StepSourceReadException) {
            // expected
        }

        assertEquals(ApiFailureCategory.SUBSCRIPTION_INVALID, healthStore.snapshot.first().latestReadFailureCategory)
    }

    @Test
    fun `a successful empty read is recorded without ever setting everObservedSample, and the requested window is recorded`() = runTest {
        val (source, healthStore) = newSource(FakeLocalRecordingGateway())

        val intervals = source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)

        assertTrue(intervals.isEmpty())
        val snapshot = healthStore.snapshot.first()
        assertFalse("an empty-but-successful read must never look like collection is active", snapshot.everObservedSample)
        assertEquals(0, snapshot.latestReadIntervalCount)
        assertEquals(1, snapshot.consecutiveEmptyReads)
        assertEquals(WINDOW_START, snapshot.latestRequestedWindowStartEpochSecond)
        assertEquals(WINDOW_END, snapshot.latestRequestedWindowEndEpochSecond)
    }

    @Test
    fun `cancellation during read propagates and is never recorded as an ordinary read failure`() = runTest {
        val (source, healthStore) = newSource(FakeLocalRecordingGateway(readException = CancellationException("cancelled")))

        try {
            source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }

        assertEquals(
            "a cancellation must never be recorded as a read failure at all",
            null,
            healthStore.snapshot.first().latestReadFailureCategory,
        )
    }

    // ---- Health-recording failures must never affect the real subscribe/read outcome ----

    private fun newSourceWithThrowingHealth(
        gateway: LocalRecordingGateway,
        recorder: ThrowingHealthRecorder = ThrowingHealthRecorder(),
    ): LocalRecordingStepSource {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return LocalRecordingStepSource(context, gateway, recorder)
    }

    @Test
    fun `a health-recording failure on subscription success does not turn a real success into a reported failure`() = runTest {
        val recorder = ThrowingHealthRecorder()
        val source = newSourceWithThrowingHealth(FakeLocalRecordingGateway(), recorder)

        val result = source.subscribeAndRecordOutcome()

        assertTrue("the real gateway subscribe() succeeded - a broken diagnostic write must not flip this to false", result)
        assertEquals(1, recorder.recordSubscriptionSuccessCalls)
    }

    @Test
    fun `a health-recording failure while recording a subscription failure does not mask the real failure`() = runTest {
        val recorder = ThrowingHealthRecorder()
        val source = newSourceWithThrowingHealth(
            FakeLocalRecordingGateway(subscribeException = ApiException(Status(CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED))),
            recorder,
        )

        val result = source.subscribeAndRecordOutcome()

        assertFalse("the real gateway subscribe() failed - this must still be reported as a failure", result)
        assertEquals(1, recorder.recordSubscriptionFailureCalls)
    }

    @Test
    fun `a health-recording failure on read attempt does not prevent the actual gateway read from running`() = runTest {
        val recorder = ThrowingHealthRecorder()
        var gatewayReadCalls = 0
        val gateway = object : LocalRecordingGateway {
            override suspend fun subscribe() = Unit
            override suspend fun readData(request: LocalDataReadRequest): LocalDataReadResponse {
                gatewayReadCalls++
                return FakeLocalRecordingGateway.successResponse()
            }
        }
        val source = newSourceWithThrowingHealth(gateway, recorder)

        val intervals = source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)

        assertEquals("recordReadAttempt threw, but the actual gateway read must still have been attempted", 1, gatewayReadCalls)
        assertTrue(intervals.isEmpty())
        assertEquals(1, recorder.recordReadAttemptCalls)
    }

    @Test
    fun `health-recording failures on both read attempt and read success never discard the parsed intervals or throw out of readAndRecordOutcome`() = runTest {
        val recorder = ThrowingHealthRecorder()
        val source = newSourceWithThrowingHealth(FakeLocalRecordingGateway(), recorder)

        // Every health-store write below throws, yet the call must still return normally with
        // whatever the gateway actually produced - never an exception propagated from a
        // diagnostic-write failure, and never a silently discarded result.
        val intervals = source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)

        assertTrue(intervals.isEmpty())
        assertEquals(1, recorder.recordReadAttemptCalls)
        assertEquals(1, recorder.recordReadSuccessCalls)
    }

    @Test
    fun `a health-recording failure while recording a read failure does not replace or mask the real StepSourceReadException`() = runTest {
        val recorder = ThrowingHealthRecorder()
        val source = newSourceWithThrowingHealth(
            FakeLocalRecordingGateway(readException = ApiException(Status(FitnessStatusCodes.INVALID_PERMISSION))),
            recorder,
        )

        val exception = try {
            source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)
            null
        } catch (e: StepSourceReadException) {
            e
        }

        assertEquals(
            "the real read failure's category must survive even though the health recorder itself threw",
            ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE,
            exception?.apiFailure?.category,
        )
        assertEquals(1, recorder.recordReadFailureCalls)
    }

    @Test
    fun `a CancellationException thrown by the health recorder itself propagates rather than being swallowed`() = runTest {
        val recorder = ThrowingHealthRecorder(exception = CancellationException("cancelled during health recording"))
        val source = newSourceWithThrowingHealth(FakeLocalRecordingGateway(), recorder)

        try {
            source.subscribeAndRecordOutcome()
            fail("expected the health recorder's own CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `a CancellationException thrown by the health recorder during a read attempt propagates before the gateway read even starts`() = runTest {
        val recorder = ThrowingHealthRecorder(exception = CancellationException("cancelled during health recording"))
        var gatewayReadCalls = 0
        val gateway = object : LocalRecordingGateway {
            override suspend fun subscribe() = Unit
            override suspend fun readData(request: LocalDataReadRequest): LocalDataReadResponse {
                gatewayReadCalls++
                return FakeLocalRecordingGateway.successResponse()
            }
        }
        val source = newSourceWithThrowingHealth(gateway, recorder)

        try {
            source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)
            fail("expected the health recorder's own CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals("a genuine cancellation must abort before the real read is even attempted", 0, gatewayReadCalls)
    }

    // ---- A health processor that never completes cannot gate or delay real acquisition ----

    @Test
    fun `subscribeAndRecordOutcome returns the real result promptly even when the health processor never completes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // backgroundScope: a TestScope-provided scope whose jobs are cancelled automatically when
        // the test ends and are never required to complete for the test itself to pass - exactly
        // what AsyncStepSourceHealthRecorder's single consumer coroutine needs here, since by
        // design (see NeverCompletingHealthRecorder) it never will.
        val recorder = AsyncStepSourceHealthRecorder(NeverCompletingHealthRecorder(), backgroundScope)
        val source = LocalRecordingStepSource(context, FakeLocalRecordingGateway(), recorder)

        // withTimeout is a belt-and-suspenders assertion, not what actually proves this: if
        // subscribeAndRecordOutcome ever awaited the processor directly, this coroutine would
        // simply never reach the line after it (the test would hang, not fail an assertion) -
        // exactly the bug this class exists to structurally rule out.
        val result = withTimeout(5_000) { source.subscribeAndRecordOutcome() }

        assertTrue("the real gateway.subscribe() succeeded and must be reported as such", result)
    }

    @Test
    fun `subscribeAndRecordOutcome still reports a real failure promptly even when the health processor never completes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = AsyncStepSourceHealthRecorder(NeverCompletingHealthRecorder(), backgroundScope)
        val source = LocalRecordingStepSource(
            context,
            FakeLocalRecordingGateway(subscribeException = ApiException(Status(CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED))),
            recorder,
        )

        val result = withTimeout(5_000) { source.subscribeAndRecordOutcome() }

        assertFalse("the real gateway.subscribe() failed and must be reported as such", result)
    }

    @Test
    fun `readAndRecordOutcome still calls the real gateway and returns its result promptly even when the health processor never completes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = AsyncStepSourceHealthRecorder(NeverCompletingHealthRecorder(), backgroundScope)
        var gatewayReadCalls = 0
        val gateway = object : LocalRecordingGateway {
            override suspend fun subscribe() = Unit
            override suspend fun readData(request: LocalDataReadRequest): LocalDataReadResponse {
                gatewayReadCalls++
                return FakeLocalRecordingGateway.successResponse()
            }
        }
        val source = LocalRecordingStepSource(context, gateway, recorder)

        val intervals = withTimeout(5_000) { source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END) }

        assertEquals("a wedged diagnostic processor must never prevent the real gateway read from running", 1, gatewayReadCalls)
        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `readAndRecordOutcome still rethrows the real read failure promptly even when the health processor never completes`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = AsyncStepSourceHealthRecorder(NeverCompletingHealthRecorder(), backgroundScope)
        val source = LocalRecordingStepSource(
            context,
            FakeLocalRecordingGateway(readException = ApiException(Status(FitnessStatusCodes.INVALID_PERMISSION))),
            recorder,
        )

        val exception = withTimeout(5_000) {
            try {
                source.readAndRecordOutcome(readRequest(), WINDOW_START, WINDOW_END)
                null
            } catch (e: StepSourceReadException) {
                e
            }
        }

        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, exception?.apiFailure?.category)
    }

    private companion object {
        const val WINDOW_START = 1_700_000_000L
        const val WINDOW_END = 1_700_000_060L
    }
}
