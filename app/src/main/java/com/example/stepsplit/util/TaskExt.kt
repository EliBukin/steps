package com.example.stepsplit.util

import com.google.android.gms.tasks.Task
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Minimal suspend bridge for Play Services [Task], avoiding a dependency on the whole
 * `kotlinx-coroutines-play-services` artifact for a single extension function.
 *
 * A task Play services cancels by itself - not because the waiting coroutine was cancelled, which
 * ends the wait on its own - is an ordinary failure ([IOException]), never a bare
 * `CancellationException`: callers rethrow that as their own cancellation, so it would silently
 * end work nobody cancelled (a Finish, the notification's Mark spot) instead of being handled as
 * the failure it is.
 */
suspend fun <T> Task<T>.await(): T {
    if (isComplete) {
        val currentException = exception
        return if (currentException != null) {
            throw currentException
        } else {
            @Suppress("UNCHECKED_CAST")
            (if (isCanceled) throw IOException("Task was cancelled") else result)
        }
    }
    return suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { completed ->
            val exception = completed.exception
            when {
                exception != null -> continuation.resumeWithException(exception)
                completed.isCanceled -> continuation.resumeWithException(IOException("Task was cancelled"))
                else -> continuation.resume(completed.result)
            }
        }
    }
}
