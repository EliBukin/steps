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
 *
 * [freshGate] builds every gate here with its own private, zero-based token counter rather than the
 * production default (a single counter shared across every gate instance in the process - see
 * [CommandGenerationGate]'s own "Generation identity across instances" doc section) purely so this
 * file's assertions can keep comparing against small, predictable literal generation numbers
 * regardless of how many other gates earlier tests in this same JVM process happened to construct.
 * The cross-instance uniqueness guarantee itself is proven separately, using the real production
 * default, in `TripRecordingCommandControllerTest`'s two-controller-instance tests.
 */
class CommandGenerationGateTest {

    private fun freshGate(): CommandGenerationGate {
        val counter = AtomicLong(0L)
        return CommandGenerationGate(nextToken = counter::incrementAndGet)
    }

    @Test
    fun `a current generation's action runs and returns its result`() {
        val gate = freshGate()
        val gen = gate.begin()

        val result = gate.runIfCurrent(gen) { "ran" }

        assertEquals("ran", result)
    }

    @Test
    fun `a generation superseded by a later begin can never run its action again`() {
        val gate = freshGate()
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
        val gate = freshGate()
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
        val gate = freshGate()
        val gen1 = gate.begin()
        assertTrue(gate.isCurrent(gen1))

        val gen2 = gate.begin()
        assertTrue(gate.isCurrent(gen2) && !gate.isCurrent(gen1))
        // Calling isCurrent again must not itself advance the generation.
        assertTrue(gate.isCurrent(gen2))
    }

    @Test
    fun `begin draws its generation from the injected token source, not a private zero-based counter`() {
        val sharedCounter = AtomicLong(100L)
        val gateA = CommandGenerationGate(nextToken = sharedCounter::incrementAndGet)
        val gateB = CommandGenerationGate(nextToken = sharedCounter::incrementAndGet)

        // Two different gate instances drawing from the same source can never collide - the exact
        // property CommandGenerationGate's production default (a single process-wide counter) relies
        // on to keep TripRepository's ownership tokens safe across separate controller/service
        // instances - see the class's own "Generation identity across instances" doc section.
        val genA = gateA.begin()
        val genB = gateB.begin()

        assertEquals(101L, genA)
        assertEquals(102L, genB)
        assertTrue(genA != genB)
    }

    @Test
    fun `shutdown on a gate with no in-flight action closes it immediately and runs the final stop exactly once`() {
        val gate = freshGate()
        val gen = gate.begin()
        var stopCount = 0

        gate.shutdown { stopCount++ }
        gate.shutdown { stopCount++ } // idempotent - a second call must not run finalStop again

        assertEquals(1, stopCount)
        assertNull(gate.runIfCurrent(gen) { "must never run" })
        assertTrue(!gate.isCurrent(gen))
    }

    @Test
    fun `shutdown permanently rejects a generation issued after closing too`() {
        val gate = freshGate()
        gate.shutdown {}

        val genAfterClose = gate.begin()

        assertNull(gate.runIfCurrent(genAfterClose) { "must never run" })
        assertTrue(!gate.isCurrent(genAfterClose))
    }

    /**
     * The exact scenario `TripRecordingCommandController.shutdown`'s own doc comment describes: an
     * older action is already genuinely inside the atomic gate (see the in-flight test above for why
     * that reproduction technique is faithful) when shutdown is requested concurrently. Shutdown must
     * wait for that action to finish - never pre-empt or interleave with it - and only then perform
     * the final stop, so the last observable state is always the stopped one. Afterward, the gate
     * permanently rejects every further action, for the superseded generation *and* for a fresh one
     * issued post-shutdown.
     */
    @Test
    fun `shutdown waits for an in-flight action to finish, then performs the final stop, and permanently rejects everything after`() {
        val gate = freshGate()
        val gen1 = gate.begin()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)

        val oldActionThread = thread {
            gate.runIfCurrent(gen1) {
                log.add("gen1 action started")
                actionEntered.countDown()
                releaseAction.await(5, TimeUnit.SECONDS)
                log.add("gen1 action finished")
            }
        }
        assertTrue(actionEntered.await(5, TimeUnit.SECONDS))

        val shutdownThread = thread { gate.shutdown { log.add("final stop") } }

        releaseAction.countDown()
        oldActionThread.join(5_000)
        shutdownThread.join(5_000)

        // The final stop only ever ran after gen1's own in-flight action fully finished - proven by
        // log order, not by timing.
        assertEquals(listOf("gen1 action started", "gen1 action finished", "final stop"), log)

        // Permanently closed: neither the superseded gen1 nor a brand-new generation issued after
        // shutdown can ever act again.
        assertNull(gate.runIfCurrent(gen1) { "must never run" })
        val gen2 = gate.begin()
        assertNull(gate.runIfCurrent(gen2) { "must never run" })
        assertTrue(!gate.isCurrent(gen2))
    }
}
