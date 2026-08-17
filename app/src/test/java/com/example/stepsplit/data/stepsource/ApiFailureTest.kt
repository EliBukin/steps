package com.example.stepsplit.data.stepsource

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiFailureTest {

    @Test
    fun `a SecurityException maps to PERMISSION_OR_SECURITY_FAILURE`() {
        val failure = apiFailureForThrowable(SecurityException("permission revoked mid-call"))

        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, failure.category)
    }

    @Test
    fun `an unrecognized exception falls back to OTHER_API_FAILURE`() {
        val failure = apiFailureForThrowable(IllegalStateException("unexpected"))

        assertEquals(ApiFailureCategory.OTHER_API_FAILURE, failure.category)
    }

    @Test
    fun `apiFailureForThrowable never carries a status code - no source-specific status space to derive one from`() {
        val failure = apiFailureForThrowable(RuntimeException("some failure"))

        assertEquals(null, failure.statusCode)
    }
}
