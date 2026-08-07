package com.example.stepsplit.domain.model

import com.example.stepsplit.data.stepsource.StepSourceAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepCollectionHealthTest {

    @Test
    fun `availability not yet known produces null rather than a guessed state`() {
        assertNull(deriveStepCollectionHealth(availability = null, syncFailure = null, everObservedSample = false))
    }

    @Test
    fun `any non-Available availability is UNAVAILABLE regardless of sample history`() {
        assertEquals(
            StepCollectionHealth.UNAVAILABLE,
            deriveStepCollectionHealth(StepSourceAvailability.PermissionNotGranted, syncFailure = null, everObservedSample = true),
        )
        assertEquals(
            StepCollectionHealth.UNAVAILABLE,
            deriveStepCollectionHealth(StepSourceAvailability.ApiUnavailable, syncFailure = null, everObservedSample = false),
        )
    }

    @Test
    fun `a recorded SUBSCRIPTION_FAILED sync failure wins over everObservedSample`() {
        val failure = SyncFailure(SyncFailureCategory.SUBSCRIPTION_FAILED, 100L)

        assertEquals(
            StepCollectionHealth.SUBSCRIPTION_FAILED,
            deriveStepCollectionHealth(StepSourceAvailability.Available, failure, everObservedSample = true),
        )
    }

    @Test
    fun `a recorded READ_FAILED or UNKNOWN sync failure is READ_FAILED, also winning over everObservedSample`() {
        assertEquals(
            StepCollectionHealth.READ_FAILED,
            deriveStepCollectionHealth(
                StepSourceAvailability.Available,
                SyncFailure(SyncFailureCategory.READ_FAILED, 100L),
                everObservedSample = true,
            ),
        )
        assertEquals(
            StepCollectionHealth.READ_FAILED,
            deriveStepCollectionHealth(
                StepSourceAvailability.Available,
                SyncFailure(SyncFailureCategory.UNKNOWN, 100L),
                everObservedSample = true,
            ),
        )
    }

    @Test
    fun `available, no failure, no sample ever observed is WAITING_FOR_FIRST_SAMPLE - never shown as active`() {
        assertEquals(
            StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE,
            deriveStepCollectionHealth(StepSourceAvailability.Available, syncFailure = null, everObservedSample = false),
        )
    }

    @Test
    fun `the first positive sample transitions health from WAITING_FOR_FIRST_SAMPLE to SAMPLE_OBSERVED`() {
        val before = deriveStepCollectionHealth(StepSourceAvailability.Available, syncFailure = null, everObservedSample = false)
        val after = deriveStepCollectionHealth(StepSourceAvailability.Available, syncFailure = null, everObservedSample = true)

        assertEquals(StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE, before)
        assertEquals(StepCollectionHealth.SAMPLE_OBSERVED, after)
    }

    @Test
    fun `one positive read followed by unlimited subsequent empty reads still yields SAMPLE_OBSERVED, never a stronger currently-active claim`() {
        // everObservedSample is the only input this function has for "was a sample ever seen" -
        // it stays true regardless of how many empty reads follow (see
        // StepSourceHealthStore.recordReadSuccess's own doc comment), and this function must never
        // read anything else (e.g. a hypothetical "consecutive empty reads" count) to manufacture a
        // higher-confidence "currently collecting" state on top of that. Simulates an arbitrarily
        // long run of subsequent empty reads by simply calling this pure function repeatedly with
        // the same (now-permanently-true) everObservedSample input, since that is genuinely the
        // only thing that could change from the function's own point of view.
        repeat(500) {
            val health = deriveStepCollectionHealth(StepSourceAvailability.Available, syncFailure = null, everObservedSample = true)
            assertEquals(StepCollectionHealth.SAMPLE_OBSERVED, health)
        }
    }
}
