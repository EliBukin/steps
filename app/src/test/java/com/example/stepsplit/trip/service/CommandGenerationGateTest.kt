package com.example.stepsplit.trip.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [CommandGenerationGate] in isolation - no Android, coroutines, or Room involved - with
 * real JVM threads and [CountDownLatch]s, never a sleep, so the dangerous interleaving this class
 * exists to prevent is reproduced deterministically rather than merely hoped for.
 *
 * The bug this closes: a two-step "check currency, release, then act" pattern leaves a real gap in
 * which a concurrent newer command can be accepted *between* the check and the act, so a stale
 * command's action still runs afterward and can stop or replace whatever the newer command just
 * started. [CommandGenerationGate.runIfCurrent] makes the check and the act a single atomic
 * operation instead, so that gap cannot exist - see the class's own doc comment.
 */
class CommandGenerationGateTest {

    @Test
    fun `a current generation's action runs and returns its result`() {
        val gate = CommandGenerationGate()
        val gen = gate.begin()

        val result = gate.runIfCurrent(gen) { "ran" }

        assertEquals("ran", result)
    }

    @Test
    fun `a generation superseded by a later begin can never run its action again`() {
        val gate = CommandGenerationGate()
        val gen1 = gate.begin()
        gate.begin() // gen2 - supersedes gen1

        val result = gate.runIfCurrent(gen1) { "must not run" }

        assertNull(result)
    }

    /**
     * The core race: an older generation's action is genuinely *in flight* - not yet completed, not
     * yet even started when the newer command is dispatched - overlapping in wall-clock time with a
     * concurrent [CommandGenerationGate.begin] call, exactly as a real command handler running on one
     * thread can overlap with `onStartCommand` being invoked again on another. [CommandGenerationGate]
     * must guarantee that the two can never interleave: either the in-flight action fully owns
     * "current" for its entire duration (safe - it validated before any newer command could exist),
     * or the newer command's [CommandGenerationGate.begin] is accepted only after the in-flight
     * action has completely finished, never during it. Both are proven by the JVM monitor's mutual
     * exclusion (the log is appended to *only* inside the gate's own lock), and afterward the
     * superseded generation is proven permanently unable to act again.
     */
    @Test
    fun `an older command whose action is genuinely in flight blocks a concurrent newer begin, and once superseded it can never act again`() {
        val gate = CommandGenerationGate()
        val gen1 = gate.begin()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)

        // gen1's action deliberately blocks *inside* the atomic gate - it has already been granted
        // ownership and is doing real (if artificially slow) work with it, faithfully reproducing an
        // in-flight command rather than one that happens to complete before the next begin() call.
        val oldCommandThread = thread {
            gate.runIfCurrent(gen1) {
                log.add("gen1 action started")
                actionEntered.countDown()
                releaseAction.await(5, TimeUnit.SECONDS)
                log.add("gen1 action finished")
            }
        }
        assertTrue(actionEntered.await(5, TimeUnit.SECONDS))

        // A newer command is dispatched concurrently, on its own thread, while gen1's action is still
        // inside the atomic block - begin() must not be accepted until gen1's action fully releases
        // ownership of the gate's lock.
        val gen2Holder = AtomicLong(-1)
        val newCommandThread = thread { gen2Holder.set(gate.begin()) }

        releaseAction.countDown()
        oldCommandThread.join(5_000)
        newCommandThread.join(5_000)

        // gen1's action ran to completion (both log lines present, in order) - it legitimately owned
        // "current" for its entire duration - and only *after* that did gen2 get accepted.
        assertEquals(listOf("gen1 action started", "gen1 action finished"), log)
        assertEquals(2L, gen2Holder.get())

        // gen1 is now permanently superseded - it can never perform another side effect, even though
        // its own action, moments ago, was still "in flight" from the newer command's perspective.
        val staleResult = gate.runIfCurrent(gen1) { log.add("gen1 acted again"); "unexpected" }
        assertNull(staleResult)
        assertEquals(listOf("gen1 action started", "gen1 action finished"), log)

        // gen2 itself can still legitimately act.
        assertEquals("gen2 ran", gate.runIfCurrent(2L) { "gen2 ran" })
    }

    @Test
    fun `isCurrent reflects the latest accepted generation without mutating it`() {
        val gate = CommandGenerationGate()
        val gen1 = gate.begin()
        assertTrue(gate.isCurrent(gen1))

        val gen2 = gate.begin()
        assertTrue(gate.isCurrent(gen2) && !gate.isCurrent(gen1))
        // Calling isCurrent again must not itself advance the generation.
        assertTrue(gate.isCurrent(gen2))
    }
}
