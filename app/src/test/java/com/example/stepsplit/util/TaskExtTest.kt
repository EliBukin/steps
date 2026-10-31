package com.example.stepsplit.util

import android.os.Looper
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** [await] on Play services' own [com.google.android.gms.tasks.Task]s (Robolectric: their listeners run on the main looper). */
@RunWith(RobolectricTestRunner::class)
class TaskExtTest {

    @Test
    fun `a task Play services cancels by itself is a failure for a caller that wasn't cancelled`() = runBlocking {
        val token = CancellationTokenSource()
        val task = TaskCompletionSource<String>(token.token).task
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { runCatching { task.await() } }

        token.cancel() // not the caller: nothing cancelled the coroutine
        shadowOf(Looper.getMainLooper()).idle()

        val failure = waiting.await().exceptionOrNull()
        assertTrue("got $failure", failure != null && failure !is CancellationException)
        assertTrue(waiting.isCompleted && !waiting.isCancelled)
    }

    @Test
    fun `a task already cancelled before the wait is a failure too`() = runBlocking {
        val token = CancellationTokenSource()
        val task = TaskCompletionSource<String>(token.token).task
        token.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        val failure = runCatching { task.await() }.exceptionOrNull()
        assertTrue("got $failure", failure != null && failure !is CancellationException)
    }

    @Test
    fun `a caller that is cancelled while it waits still ends as cancelled`() = runBlocking {
        val task = TaskCompletionSource<String>().task
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { task.await() }

        waiting.cancel()

        assertTrue(waiting.isCancelled)
        assertTrue(runCatching { waiting.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `a result and a failure come through as before`() = runBlocking {
        val done = TaskCompletionSource<String>()
        val failed = TaskCompletionSource<String>()
        val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { done.task.await() }
        val error = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { runCatching { failed.task.await() } }

        done.setResult("fix")
        failed.setException(SecurityException("no permission"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("fix", result.await())
        assertTrue(error.await().exceptionOrNull() is SecurityException)
    }
}
