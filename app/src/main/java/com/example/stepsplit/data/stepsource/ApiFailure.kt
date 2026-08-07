package com.example.stepsplit.data.stepsource

import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.fitness.FitnessStatusCodes

/**
 * Sanitized, localizable buckets for why a Local Recording API call (subscribe or read) failed at
 * the Google Play services layer - never raw exception text, which can't be localized and may leak
 * implementation detail into release UI. Every value here is derived from a real, documented
 * `play-services-fitness`/`play-services-base` status constant (see [categorizeStatusCode]) - none
 * are invented, and every mapping below is cross-checked against `LocalRecordingClient`'s own
 * documented `@Throws` table for `subscribe`/`readData`
 * (https://developers.google.com/android/reference/com/google/android/gms/fitness/LocalRecordingClient),
 * not just the generic per-constant descriptions inherited from the older, account-based Fitness
 * API - several of those generic descriptions describe a status code's *original* meaning from a
 * different, unrelated API surface (see [ApiOperation] and [categorizeStatusCode]'s own doc
 * comment for the two concrete cases this caught).
 */
enum class ApiFailureCategory {
    /** The calling package/app or the API itself is not usable in this context (whitelisting, package mismatch, unsupported platform, not connected). */
    PACKAGE_OR_API_UNAVAILABLE,

    /** The requested fitness data type is not allowed/supported for this specific API call. */
    DATA_TYPE_NOT_ALLOWED,

    /** Documented specifically as "no local datasource available to subscribe" - e.g. the device has no matching sensor at all - a materially different, more specific condition than [DATA_TYPE_NOT_ALLOWED]. */
    DATA_SOURCE_UNAVAILABLE,

    /** Mirrors [StepSourceAvailability.PlayServicesUpdateRequired] but observed from an actual API call rather than the upfront version check. */
    PLAY_SERVICES_UPDATE_REQUIRED,

    /** A permission or account/security problem - missing OAuth/BLE/account permission, blocked access, or a thrown [SecurityException]. */
    PERMISSION_OR_SECURITY_FAILURE,

    /** The subscription itself is missing or stale - see [categorizeStatusCode]'s handling of [FitnessStatusCodes.API_EXCEPTION]. */
    SUBSCRIPTION_INVALID,

    /** Any other mapped or unmapped API failure (transient/network/internal errors, timeouts, etc.). */
    OTHER_API_FAILURE,
}

/**
 * Which `LocalRecordingClient` call produced a given status code. Not every status code means the
 * same thing regardless of which call raised it - see [categorizeStatusCode]'s handling of
 * [FitnessStatusCodes.API_EXCEPTION], which `LocalRecordingClient`'s own docs document only for
 * `readData`, not `subscribe`.
 */
enum class ApiOperation { SUBSCRIBE, READ }

/** A sanitized category paired with the real Google status code it was derived from, if any (never a raw message). */
data class ApiFailure(val category: ApiFailureCategory, val statusCode: Int?)

/** Builds an [ApiFailure] directly from a known Google status code - e.g. [com.google.android.gms.common.api.Status.getStatusCode]. */
fun apiFailureForStatusCode(statusCode: Int, operation: ApiOperation): ApiFailure =
    ApiFailure(categorizeStatusCode(statusCode, operation), statusCode)

/**
 * Builds an [ApiFailure] from whatever a Play Services [com.google.android.gms.tasks.Task] (or the
 * gateway wrapping it) actually threw. [ApiException] carries a real status code and is mapped
 * through the same [categorizeStatusCode] table as a non-success `LocalDataReadResponse` status -
 * both are "the API told us something specific went wrong" and deserve the same category space. A
 * bare [SecurityException] (permission revoked in the narrow window between our own check and the
 * call reaching the OS) has no status code to report, but is unambiguously a permission failure.
 * Anything else is an unmapped API failure - still sanitized (no message text carried forward here;
 * callers that want the original for debug-only logging keep the [Throwable] itself, never this).
 */
fun apiFailureForThrowable(throwable: Throwable, operation: ApiOperation): ApiFailure = when (throwable) {
    is ApiException -> apiFailureForStatusCode(throwable.statusCode, operation)
    is SecurityException -> ApiFailure(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, null)
    else -> ApiFailure(ApiFailureCategory.OTHER_API_FAILURE, null)
}

/**
 * Maps a real `CommonStatusCodes`/`FitnessStatusCodes`/`ConnectionResult` constant to a sanitized
 * [ApiFailureCategory], cross-checked against `LocalRecordingClient`'s own documented `@Throws`
 * table for `subscribe`/`readData`/`unsubscribe` (see this file's own top-level doc comment) rather
 * than assumed from the constants' generic per-field descriptions alone. Two corrections that table
 * exposed, both confirmed against the live reference docs (last verified 2025-08-05 per that page's
 * own "Last updated" stamp):
 *
 * 1. **[FitnessStatusCodes.API_EXCEPTION] (5011) is operation-specific.** `readData`'s own `@Throws`
 *    entry documents it verbatim as "if a valid subscription for a requested data type is not
 *    found" - a genuine subscription problem. `subscribe`'s own `@Throws` table does **not**
 *    document this code at all, so treating an `API_EXCEPTION` from a *subscribe* failure as the
 *    same "subscription invalid" signal would be unfounded - it falls back to
 *    [ApiFailureCategory.OTHER_API_FAILURE] for [ApiOperation.SUBSCRIBE] instead.
 * 2. **[FitnessStatusCodes.EQUIVALENT_SESSION_ENDED] (5009) does not apply here at all.** Its own
 *    documented description - "a session could not be started because an equivalent session has
 *    already ended" - is about the older, unrelated session-based Recording API
 *    (`SessionsClient.startSession`), not `LocalRecordingClient`'s data-type subscriptions; it does
 *    not appear anywhere in `LocalRecordingClient`'s own `@Throws` tables. Previously mapped here to
 *    [ApiFailureCategory.SUBSCRIPTION_INVALID] on the mistaken assumption that "session" and
 *    "subscription" were the same concept for this API - they are not, so it is deliberately left
 *    unmapped (falls through to [ApiFailureCategory.OTHER_API_FAILURE]) rather than continuing to
 *    invent a Local-Recording-specific meaning the docs never give it.
 *
 * [ConnectionResult.API_UNAVAILABLE] (16) is documented on every `LocalRecordingClient` method as
 * "if the calling package is not allowed to use the Recording API on mobile" - exactly
 * [ApiFailureCategory.PACKAGE_OR_API_UNAVAILABLE]. Numerically it collides with
 * [CommonStatusCodes.CANCELED] (also 16, from a completely different, unrelated status space) -
 * `play-services-base` reuses small integers across independent constant families - but
 * `LocalRecordingClient`'s own `@Throws` tables never document `CANCELED` for any of its calls, so a
 * 16 actually observed from this API reliably means `API_UNAVAILABLE`, not a cancellation.
 *
 * Deliberately exhaustive-by-`else` rather than exhaustive-by-`when` over the constants themselves -
 * these are plain `Int` constants (not a sealed/enum type), so every currently-undocumented or
 * future status code safely falls into [ApiFailureCategory.OTHER_API_FAILURE] instead of failing to
 * compile.
 */
private fun categorizeStatusCode(statusCode: Int, operation: ApiOperation): ApiFailureCategory = when (statusCode) {
    ConnectionResult.API_UNAVAILABLE,
    FitnessStatusCodes.APP_MISMATCH,
    FitnessStatusCodes.INCONSISTENT_PACKAGE_NAME,
    FitnessStatusCodes.REQUIRES_APP_WHITELISTING,
    FitnessStatusCodes.UNSUPPORTED_PLATFORM,
    CommonStatusCodes.API_NOT_CONNECTED,
    -> ApiFailureCategory.PACKAGE_OR_API_UNAVAILABLE

    FitnessStatusCodes.DATA_TYPE_NOT_ALLOWED_FOR_API,
    FitnessStatusCodes.DATA_TYPE_NOT_FOUND,
    FitnessStatusCodes.CONFLICTING_DATA_TYPE,
    FitnessStatusCodes.INCONSISTENT_DATA_TYPE,
    FitnessStatusCodes.AGGREGATION_NOT_SUPPORTED,
    -> ApiFailureCategory.DATA_TYPE_NOT_ALLOWED

    FitnessStatusCodes.DATA_SOURCE_NOT_FOUND -> ApiFailureCategory.DATA_SOURCE_UNAVAILABLE

    CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED, // numerically the same as ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED (both 2)
    -> ApiFailureCategory.PLAY_SERVICES_UPDATE_REQUIRED

    FitnessStatusCodes.INVALID_PERMISSION,
    FitnessStatusCodes.ACCESS_BLOCKED,
    FitnessStatusCodes.MISSING_BLE_PERMISSION,
    FitnessStatusCodes.NEEDS_OAUTH_PERMISSIONS,
    FitnessStatusCodes.UNKNOWN_AUTH_ERROR,
    FitnessStatusCodes.UNSUPPORTED_ACCOUNT,
    CommonStatusCodes.SIGN_IN_REQUIRED,
    CommonStatusCodes.INVALID_ACCOUNT,
    -> ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE

    FitnessStatusCodes.API_EXCEPTION ->
        if (operation == ApiOperation.READ) ApiFailureCategory.SUBSCRIPTION_INVALID else ApiFailureCategory.OTHER_API_FAILURE

    else -> ApiFailureCategory.OTHER_API_FAILURE
}
