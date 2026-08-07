package com.example.stepsplit.data.stepsource

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric does not guarantee a fresh Preferences DataStore file per test method (see
 * [com.example.stepsplit.data.repository.StepRepositoryTest]'s own teardown for the same caveat) -
 * every test below clears the store first via [newStore] so an earlier test's writes can never
 * leak into a later one's "fresh" assertions.
 */
@RunWith(RobolectricTestRunner::class)
class StepSourceHealthStoreTest {

    private suspend fun newStore(): StepSourceHealthStore =
        StepSourceHealthStore(ApplicationProvider.getApplicationContext<Context>()).also { it.clear() }

    @After
    fun tearDown() = runTest {
        newStore()
    }

    @Test
    fun `a fresh store has never observed a sample and is otherwise empty`() = runTest {
        val snapshot = newStore().snapshot.first()

        assertFalse(snapshot.everObservedSample)
        assertNull(snapshot.latestSampleEpochSecond)
        assertEquals(0, snapshot.consecutiveEmptyReads)
        assertNull(snapshot.latestSubscriptionSucceeded)
        assertNull(snapshot.latestRequestedWindowStartEpochSecond)
        assertNull(snapshot.latestRequestedWindowEndEpochSecond)
    }

    @Test
    fun `a read attempt records the requested window bounds`() = runTest {
        val store = newStore()

        store.recordReadAttempt(atEpochSecond = 100L, windowStartEpochSecond = 10L, windowEndEpochSecond = 70L)

        val snapshot = store.snapshot.first()
        assertEquals(10L, snapshot.latestRequestedWindowStartEpochSecond)
        assertEquals(70L, snapshot.latestRequestedWindowEndEpochSecond)
    }

    @Test
    fun `a successful empty read does not set everObservedSample and increments consecutiveEmptyReads`() = runTest {
        val store = newStore()

        store.recordReadSuccess(intervalCount = 0, latestSampleEpochSecond = null, atEpochSecond = 100L)
        store.recordReadSuccess(intervalCount = 0, latestSampleEpochSecond = null, atEpochSecond = 200L)

        val snapshot = store.snapshot.first()
        assertFalse("a zero-sample read must never be treated as evidence collection is active", snapshot.everObservedSample)
        assertEquals(2, snapshot.consecutiveEmptyReads)
        assertEquals(200L, snapshot.latestSuccessfulReadEpochSecond)
    }

    @Test
    fun `a positive read sets everObservedSample permanently and resets consecutiveEmptyReads`() = runTest {
        val store = newStore()
        store.recordReadSuccess(intervalCount = 0, latestSampleEpochSecond = null, atEpochSecond = 100L)

        store.recordReadSuccess(intervalCount = 3, latestSampleEpochSecond = 250L, atEpochSecond = 200L)

        val snapshot = store.snapshot.first()
        assertTrue(snapshot.everObservedSample)
        assertEquals(250L, snapshot.latestSampleEpochSecond)
        assertEquals(0, snapshot.consecutiveEmptyReads)
        assertEquals(3, snapshot.latestReadIntervalCount)
    }

    @Test
    fun `everObservedSample never resets back to false once a positive sample has been seen`() = runTest {
        val store = newStore()
        store.recordReadSuccess(intervalCount = 5, latestSampleEpochSecond = 100L, atEpochSecond = 100L)

        // A later read genuinely finds nothing new - must not un-set the historical fact that a
        // sample was once observed.
        store.recordReadSuccess(intervalCount = 0, latestSampleEpochSecond = null, atEpochSecond = 200L)

        assertTrue(store.snapshot.first().everObservedSample)
    }

    @Test
    fun `a read failure does not touch interval count or consecutive-empty-reads state`() = runTest {
        val store = newStore()
        store.recordReadSuccess(intervalCount = 4, latestSampleEpochSecond = 100L, atEpochSecond = 100L)

        store.recordReadFailure(ApiFailure(ApiFailureCategory.OTHER_API_FAILURE, 13), atEpochSecond = 200L)

        val snapshot = store.snapshot.first()
        assertEquals("a failed read is not evidence of a zero-sample read", 4, snapshot.latestReadIntervalCount)
        assertEquals(0, snapshot.consecutiveEmptyReads)
        assertEquals(ApiFailureCategory.OTHER_API_FAILURE, snapshot.latestReadFailureCategory)
        assertEquals(13, snapshot.latestReadFailureStatusCode)
        assertEquals(200L, snapshot.latestReadFailureAtEpochSecond)
    }

    @Test
    fun `recording a subscription failure then a later success clears the failure fields`() = runTest {
        val store = newStore()
        store.recordSubscriptionFailure(ApiFailure(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, 7), atEpochSecond = 100L)
        assertEquals(false, store.snapshot.first().latestSubscriptionSucceeded)

        store.recordSubscriptionSuccess(atEpochSecond = 200L)

        val snapshot = store.snapshot.first()
        assertEquals(true, snapshot.latestSubscriptionSucceeded)
        assertNull(snapshot.latestSubscriptionFailureCategory)
        assertNull(snapshot.latestSubscriptionFailureStatusCode)
        assertEquals(200L, snapshot.latestSubscriptionAtEpochSecond)
    }

    @Test
    fun `a later successful read does NOT clear a previously recorded read failure - last-failure-ever semantics`() = runTest {
        val store = newStore()
        store.recordReadFailure(ApiFailure(ApiFailureCategory.DATA_TYPE_NOT_ALLOWED, 5), atEpochSecond = 100L)

        store.recordReadSuccess(intervalCount = 1, latestSampleEpochSecond = 200L, atEpochSecond = 200L)

        val snapshot = store.snapshot.first()
        assertEquals(
            "the earlier failure's category must survive a later success - it is the most useful evidence for a real-device investigation",
            ApiFailureCategory.DATA_TYPE_NOT_ALLOWED,
            snapshot.latestReadFailureCategory,
        )
        assertEquals(5, snapshot.latestReadFailureStatusCode)
        assertEquals(100L, snapshot.latestReadFailureAtEpochSecond)
        // The success is still visible too, under its own, separate field - neither signal erases
        // the other.
        assertEquals(200L, snapshot.latestSuccessfulReadEpochSecond)
    }

    @Test
    fun `a newer read failure overwrites an older one, but is itself never cleared by a later success`() = runTest {
        val store = newStore()
        store.recordReadFailure(ApiFailure(ApiFailureCategory.DATA_TYPE_NOT_ALLOWED, 5), atEpochSecond = 100L)
        store.recordReadFailure(ApiFailure(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, 9), atEpochSecond = 150L)

        store.recordReadSuccess(intervalCount = 1, latestSampleEpochSecond = 200L, atEpochSecond = 200L)

        val snapshot = store.snapshot.first()
        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, snapshot.latestReadFailureCategory)
        assertEquals(9, snapshot.latestReadFailureStatusCode)
        assertEquals(150L, snapshot.latestReadFailureAtEpochSecond)
    }
}
