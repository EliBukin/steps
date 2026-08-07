package com.example.stepsplit.data.stepsource

import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.fitness.FitnessStatusCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the [ApiFailure]/[ApiFailureCategory] mapping directly with real, plain-constructible
 * `play-services-base`/`play-services-fitness` classes ([Status], [ApiException]) - the same
 * no-mocking-needed approach [LocalRecordingStepSourceTest] already uses for
 * [toRawIntervalsOrThrow]. Every status code below is a real documented constant, and every
 * expected category here is cross-checked against `LocalRecordingClient`'s own documented
 * `@Throws` table (see [categorizeStatusCode]'s own doc comment), not just assumed from a
 * constant's generic per-field description.
 */
class ApiFailureTest {

    @Test
    fun `ConnectionResult API_UNAVAILABLE maps to PACKAGE_OR_API_UNAVAILABLE for both operations`() {
        // Documented identically on subscribe/readData/unsubscribe: "if the calling package is
        // not allowed to use the Recording API on mobile."
        for (operation in ApiOperation.entries) {
            assertEquals(
                "operation=$operation",
                ApiFailureCategory.PACKAGE_OR_API_UNAVAILABLE,
                apiFailureForStatusCode(ConnectionResult.API_UNAVAILABLE, operation).category,
            )
        }
    }

    @Test
    fun `package or API unavailable status codes map to PACKAGE_OR_API_UNAVAILABLE`() {
        for (code in listOf(
            FitnessStatusCodes.APP_MISMATCH,
            FitnessStatusCodes.INCONSISTENT_PACKAGE_NAME,
            FitnessStatusCodes.REQUIRES_APP_WHITELISTING,
            FitnessStatusCodes.UNSUPPORTED_PLATFORM,
            CommonStatusCodes.API_NOT_CONNECTED,
        )) {
            assertEquals("code=$code", ApiFailureCategory.PACKAGE_OR_API_UNAVAILABLE, apiFailureForStatusCode(code, ApiOperation.READ).category)
        }
    }

    @Test
    fun `data type status codes map to DATA_TYPE_NOT_ALLOWED`() {
        for (code in listOf(
            FitnessStatusCodes.DATA_TYPE_NOT_ALLOWED_FOR_API,
            FitnessStatusCodes.DATA_TYPE_NOT_FOUND,
            FitnessStatusCodes.CONFLICTING_DATA_TYPE,
            FitnessStatusCodes.INCONSISTENT_DATA_TYPE,
            FitnessStatusCodes.AGGREGATION_NOT_SUPPORTED,
        )) {
            assertEquals("code=$code", ApiFailureCategory.DATA_TYPE_NOT_ALLOWED, apiFailureForStatusCode(code, ApiOperation.READ).category)
        }
    }

    @Test
    fun `DATA_SOURCE_NOT_FOUND is its own DATA_SOURCE_UNAVAILABLE category, distinct from DATA_TYPE_NOT_ALLOWED`() {
        // Documented specifically as "no local datasource available to subscribe" - a different,
        // more actionable condition (no matching sensor on this device at all) than "this data
        // type isn't allowed for this API call".
        val failure = apiFailureForStatusCode(FitnessStatusCodes.DATA_SOURCE_NOT_FOUND, ApiOperation.SUBSCRIBE)

        assertEquals(ApiFailureCategory.DATA_SOURCE_UNAVAILABLE, failure.category)
    }

    @Test
    fun `SERVICE_VERSION_UPDATE_REQUIRED maps to PLAY_SERVICES_UPDATE_REQUIRED`() {
        assertEquals(
            ApiFailureCategory.PLAY_SERVICES_UPDATE_REQUIRED,
            apiFailureForStatusCode(CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED, ApiOperation.SUBSCRIBE).category,
        )
    }

    @Test
    fun `permission and security status codes map to PERMISSION_OR_SECURITY_FAILURE`() {
        for (code in listOf(
            FitnessStatusCodes.INVALID_PERMISSION,
            FitnessStatusCodes.ACCESS_BLOCKED,
            FitnessStatusCodes.MISSING_BLE_PERMISSION,
            FitnessStatusCodes.NEEDS_OAUTH_PERMISSIONS,
            FitnessStatusCodes.UNKNOWN_AUTH_ERROR,
            FitnessStatusCodes.UNSUPPORTED_ACCOUNT,
            CommonStatusCodes.SIGN_IN_REQUIRED,
            CommonStatusCodes.INVALID_ACCOUNT,
        )) {
            assertEquals("code=$code", ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, apiFailureForStatusCode(code, ApiOperation.READ).category)
        }
    }

    @Test
    fun `API_EXCEPTION from a read operation maps to SUBSCRIPTION_INVALID, per readData's own documented Throws entry`() {
        assertEquals(
            ApiFailureCategory.SUBSCRIPTION_INVALID,
            apiFailureForStatusCode(FitnessStatusCodes.API_EXCEPTION, ApiOperation.READ).category,
        )
    }

    @Test
    fun `API_EXCEPTION from a subscribe operation is NOT SUBSCRIPTION_INVALID - subscribe's own Throws table never documents this code`() {
        assertEquals(
            ApiFailureCategory.OTHER_API_FAILURE,
            apiFailureForStatusCode(FitnessStatusCodes.API_EXCEPTION, ApiOperation.SUBSCRIBE).category,
        )
    }

    @Test
    fun `EQUIVALENT_SESSION_ENDED is not classified as a Local Recording subscription failure`() {
        // Documented as being about the unrelated, older session-based Recording API
        // (a session could not start because an equivalent session already ended) - it never
        // appears in LocalRecordingClient's own Throws tables, so it must not be special-cased
        // into SUBSCRIPTION_INVALID here; it falls through to the generic bucket like any other
        // unmapped code.
        for (operation in ApiOperation.entries) {
            val category = apiFailureForStatusCode(FitnessStatusCodes.EQUIVALENT_SESSION_ENDED, operation).category
            assertEquals("operation=$operation", ApiFailureCategory.OTHER_API_FAILURE, category)
        }
    }

    @Test
    fun `an unmapped status code falls back to OTHER_API_FAILURE rather than failing to compile or crashing`() {
        assertEquals(ApiFailureCategory.OTHER_API_FAILURE, apiFailureForStatusCode(CommonStatusCodes.TIMEOUT, ApiOperation.READ).category)
        assertEquals(ApiFailureCategory.OTHER_API_FAILURE, apiFailureForStatusCode(-9999, ApiOperation.READ).category)
    }

    @Test
    fun `apiFailureForStatusCode preserves the real status code`() {
        assertEquals(
            FitnessStatusCodes.INVALID_PERMISSION,
            apiFailureForStatusCode(FitnessStatusCodes.INVALID_PERMISSION, ApiOperation.READ).statusCode,
        )
    }

    @Test
    fun `an ApiException is mapped through the same status-code table, preserving the code`() {
        val exception = ApiException(Status(FitnessStatusCodes.DATA_TYPE_NOT_ALLOWED_FOR_API))

        val failure = apiFailureForThrowable(exception, ApiOperation.READ)

        assertEquals(ApiFailureCategory.DATA_TYPE_NOT_ALLOWED, failure.category)
        assertEquals(FitnessStatusCodes.DATA_TYPE_NOT_ALLOWED_FOR_API, failure.statusCode)
    }

    @Test
    fun `a bare SecurityException is treated as a permission failure with no status code`() {
        val failure = apiFailureForThrowable(SecurityException("permission revoked mid-call"), ApiOperation.READ)

        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, failure.category)
        assertNull(failure.statusCode)
    }

    @Test
    fun `any other throwable is an unmapped OTHER_API_FAILURE with no invented status code`() {
        val failure = apiFailureForThrowable(IllegalStateException("unexpected"), ApiOperation.SUBSCRIBE)

        assertEquals(ApiFailureCategory.OTHER_API_FAILURE, failure.category)
        assertNull(failure.statusCode)
    }
}
