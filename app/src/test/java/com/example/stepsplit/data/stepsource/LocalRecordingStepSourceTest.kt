package com.example.stepsplit.data.stepsource

import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.fitness.data.Bucket
import com.google.android.gms.fitness.data.DataSet
import com.google.android.gms.fitness.result.DataReadResult
import com.google.android.gms.fitness.result.LocalDataReadResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [toRawIntervalsOrThrow] - the response-status check pulled out of
 * [LocalRecordingStepSource.readSteps] - directly, without a real Play Services connection or a
 * mocking framework. [LocalDataReadResponse] and its underlying [DataReadResult] are both plain
 * public Google classes constructible outside the SDK, so a "completed Task, failed response" can
 * be built exactly as play-services-fitness 21.3.0 itself would deliver one.
 */
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
}
