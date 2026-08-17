package com.example.stepsplit.data.stepsource

/**
 * Sanitized, localizable buckets for why a step-source API call failed - never raw exception
 * text, which can't be localized and may leak implementation detail into release UI.
 */
enum class ApiFailureCategory {
    /** A permission or security problem - missing permission, blocked access, or a thrown [SecurityException]. */
    PERMISSION_OR_SECURITY_FAILURE,

    /** The source's own provider is not usable right now (not installed, needs an update, disabled). */
    API_UNAVAILABLE,

    /** Any other failure (transient/internal errors, timeouts, etc.). */
    OTHER_API_FAILURE,
}

/** A sanitized category, never a raw message. [statusCode] is source-specific and optional - not every source reports one. */
data class ApiFailure(val category: ApiFailureCategory, val statusCode: Int? = null)

/**
 * Builds an [ApiFailure] from whatever a step source's underlying call actually threw. A bare
 * [SecurityException] (permission revoked in the narrow window between our own check and the call
 * reaching the OS) is unambiguously a permission failure; anything else is an unmapped API
 * failure - still sanitized (no message text carried forward here; callers that want the original
 * for debug-only logging keep the [Throwable] itself, never this).
 */
fun apiFailureForThrowable(throwable: Throwable): ApiFailure = when (throwable) {
    is SecurityException -> ApiFailure(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE)
    else -> ApiFailure(ApiFailureCategory.OTHER_API_FAILURE)
}
