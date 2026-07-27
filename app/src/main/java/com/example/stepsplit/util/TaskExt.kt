package com.example.stepsplit.util

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Minimal suspend bridge for Play Services [Task], avoiding a dependency on the whole
 * `kotlinx-coroutines-play-services` artifact for a single extension function.
 */
suspend fun <T> Task<T>.await(): T {
    if (isComplete) {
        val currentException = exception
        return if (currentException != null) {
            throw currentException
        } else {
            @Suppress("UNCHECKED_CAST")
            (if (isCanceled) throw CancellationException("Task was cancelled") else result)
        }
    }
    return suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { completed ->
            val exception = completed.exception
            when {
                exception != null -> continuation.resumeWithException(exception)
                completed.isCanceled -> continuation.cancel()
                else -> continuation.resume(completed.result)
            }
        }
    }
}
