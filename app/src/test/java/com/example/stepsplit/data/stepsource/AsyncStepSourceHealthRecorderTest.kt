package com.example.stepsplit.data.stepsource

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [StepSourceHealthRecorder] that suspends forever on every call - see
 * [com.example.stepsplit.data.stepsource.LocalRecordingStepSourceTest]'s own
 * `NeverCompletingHealthRecorder` for the same pattern used from the acquisition side; this local
 * copy avoids a cross-test-file dependency on a `private` class.
 */
private class NeverCompletingDelegate : StepSourceHealthRecorder {
    override suspend fun recordSubscriptionSuccess(atEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long): Nothing = awaitCancellation()
    override suspend fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long): Nothing = awaitCancellation()
}

/** Records every `atEpochSecond` it receives, in the order [StepSourceHealthRecorder] methods were actually called - what the ordering tests below assert against. */
private class RecordingDelegate(private val onEvent: (Long) -> Unit = {}) : StepSourceHealthRecorder {
    val received = mutableListOf<Long>()

    override suspend fun recordSubscriptionSuccess(atEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) = accept(atEpochSecond)

    private fun accept(atEpochSecond: Long) {
        onEvent(atEpochSecond)
        received += atEpochSecond
    }
}

/**
 * Like [RecordingDelegate], but the single event whose `atEpochSecond` equals [hangOn] suspends
 * forever ([awaitCancellation]) instead of ever recording - a genuinely wedged write, never one
 * that merely throws. [hangStarted] proves the delegate call actually began (so a passing test isn't
 * accidentally vacuous because the event was never reached at all).
 */
private class HangsOnceThenRecordsDelegate(private val hangOn: Long) : StepSourceHealthRecorder {
    val received = mutableListOf<Long>()
    var hangStarted = false
        private set

    override suspend fun recordSubscriptionSuccess(atEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) = accept(atEpochSecond)
    override suspend fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) = accept(atEpochSecond)

    private suspend fun accept(atEpochSecond: Long) {
        if (atEpochSecond == hangOn) {
            hangStarted = true
            awaitCancellation()
        }
        received += atEpochSecond
    }
}

/**
 * Unit tests for [AsyncStepSourceHealthRecorder] itself - the non-blocking, order-preserving
 * telemetry boundary. [LocalRecordingStepSourceTest] separately proves the acquisition-facing
 * consequence (a wedged processor can't gate `gateway.subscribe()`/`readData()`); these tests prove
 * the processor's own internal contract: strict ordering, and tolerance of both enqueue and write
 * failures without ever propagating them back to a caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AsyncStepSourceHealthRecorderTest {

    /**
     * A [CoroutineScope] backed by the SAME [kotlinx.coroutines.test.TestCoroutineScheduler] this
     * [TestScope] itself uses, so [advanceUntilIdle] actually drives coroutines launched in it - but
     * with its own independent [kotlinx.coroutines.Job], not a child of the test body's own tracked
     * job tree. `TestScope.backgroundScope` was tried here first and does NOT work for this: its
     * coroutines are excluded from `advanceUntilIdle`'s scheduling entirely (confirmed empirically -
     * a `println` inside a `backgroundScope.launch` block never ran even after `advanceUntilIdle()`),
     * and a plain, undecorated `launch { }` on the test body's own scope does the opposite problem -
     * `advanceUntilIdle` DOES drive it, but `runTest` then fails at the end because
     * [AsyncStepSourceHealthRecorder]'s consumer loop never completes on its own (`for (event in
     * channel)` has no terminating `close()`). A dedicated scope sharing only the scheduler, always
     * explicitly [kotlinx.coroutines.cancel]led once assertions are done, is the one combination that
     * is both deterministically drainable and never leaks past its own test - the same pattern
     * `StepRepositoryTest`'s own `TimerTestHarness` already uses for an analogous reason.
     */
    private fun TestScope.newDrainableScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun `events are applied to the delegate strictly in the order they were enqueued`() = runTest {
        val delegate = RecordingDelegate()
        val processorScope = newDrainableScope()
        val recorder = AsyncStepSourceHealthRecorder(delegate, processorScope)

        for (i in 1..50L) recorder.recordReadAttempt(atEpochSecond = i, windowStartEpochSecond = 0, windowEndEpochSecond = 0)
        advanceUntilIdle()

        assertEquals((1..50L).toList(), delegate.received)
        processorScope.cancel()
    }

    @Test
    fun `a delegate write failure for one event does not stop later events from being processed`() = runTest {
        val delegate = RecordingDelegate(onEvent = { if (it == 2L) throw RuntimeException("simulated write failure") })
        val processorScope = newDrainableScope()
        val recorder = AsyncStepSourceHealthRecorder(delegate, processorScope)

        recorder.recordReadAttempt(1L, 0, 0)
        recorder.recordReadAttempt(2L, 0, 0) // throws inside the delegate once the consumer reaches it
        recorder.recordReadAttempt(3L, 0, 0)
        advanceUntilIdle()

        assertEquals(
            "event 2's write failure must be dropped, not crash the consumer loop or block event 3",
            listOf(1L, 3L),
            delegate.received,
        )
        processorScope.cancel()
    }

    @Test
    fun `a delegate call that never completes on one event is timed out, allowing a later event to still be processed`() = runTest {
        val delegate = HangsOnceThenRecordsDelegate(hangOn = 1L)
        val processorScope = newDrainableScope()
        // A short, explicit timeout (rather than the production default) keeps this test's intent
        // unambiguous and decoupled from that default's own tuning - the actual duration is virtual
        // time under runTest regardless, so this costs nothing in real wall-clock test time.
        val recorder = AsyncStepSourceHealthRecorder(delegate, processorScope, eventTimeoutMillis = 100L)

        recorder.recordReadAttempt(1L, 0, 0) // wedges the consumer inside the delegate - forever, unless bounded
        recorder.recordReadAttempt(2L, 0, 0) // must still eventually be processed once event 1 times out
        advanceUntilIdle()

        assertTrue(
            "event 1's delegate call must actually have started - otherwise this test would pass vacuously",
            delegate.hangStarted,
        )
        assertEquals(
            "event 1 must be dropped (timed out), never recorded, but event 2 must still get its turn - the wedge must not permanently freeze the consumer",
            listOf(2L),
            delegate.received,
        )
        processorScope.cancel()
    }

    @Test
    fun `enqueue tolerates a full channel without throwing or blocking the caller`() = runTest {
        // The single consumer coroutine pulls exactly one event out to process, then suspends
        // forever inside NeverCompletingDelegate - it never comes back for a second one, so once
        // the buffer (capacity 2) fills behind it, every further trySend fails on a full channel.
        val recorder = AsyncStepSourceHealthRecorder(NeverCompletingDelegate(), backgroundScope, channelCapacity = 2)

        // None of these ten enqueues may throw, block, or hang - including the ones past capacity
        // that can never possibly be delivered.
        repeat(10) { i -> recorder.recordReadAttempt(i.toLong(), 0, 0) }
    }

    @Test
    fun `recordX calls return without waiting for the delegate to actually process the event`() = runTest {
        val recorder = AsyncStepSourceHealthRecorder(NeverCompletingDelegate(), backgroundScope)

        // If this awaited real delegate processing, it would never return at all - reaching this
        // line (and the test completing) is itself the proof.
        recorder.recordSubscriptionSuccess(atEpochSecond = 1L)
        recorder.recordReadAttempt(atEpochSecond = 2L, windowStartEpochSecond = 0, windowEndEpochSecond = 0)
    }
}
