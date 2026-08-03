package com.example.stepsplit.data.stepsource

import java.time.Instant
import kotlin.random.Random

/**
 * Deterministic in-memory step source for unit tests and the debug-only sample data facility.
 * Never wired up in release builds. [intervals] is the full synthetic dataset; [readSteps]
 * simply filters it to the requested window, mirroring how a real source would behave.
 */
class FakeStepSource(
    override val id: String = "fake",
    private var availability: StepSourceAvailability = StepSourceAvailability.Available,
    private var subscribeSucceeds: Boolean = true,
    private val intervals: MutableList<RawStepInterval> = mutableListOf(),
) : StepSource {

    fun setAvailability(state: StepSourceAvailability) {
        availability = state
    }

    /** Simulates a subscription-only failure (e.g. Play Services rejecting `subscribe()`) independent of availability. */
    fun setSubscribeSucceeds(succeeds: Boolean) {
        subscribeSucceeds = succeeds
    }

    fun addInterval(startEpochSecond: Long, endEpochSecond: Long, steps: Long) {
        intervals.add(RawStepInterval(startEpochSecond, endEpochSecond, steps))
    }

    /** Simulates the source correcting or forgetting previously reported data on a later read. */
    fun clearIntervals() {
        intervals.clear()
    }

    override suspend fun checkAvailability(): StepSourceAvailability = availability

    override suspend fun ensureSubscribed(): Boolean =
        availability is StepSourceAvailability.Available && subscribeSucceeds

    override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
        // Mirrors LocalRecordingStepSource: unavailable-during-read must throw, never look like a
        // genuinely empty (but successful) read - see StepSource.readSteps's own doc comment. In
        // normal (non-racy) use this is unreachable, since StepRepository always checks
        // availability itself first and never calls readSteps while unavailable; it only matters
        // for tests that deliberately simulate availability being lost between that check and the
        // actual read.
        if (availability !is StepSourceAvailability.Available) throw StepSourceUnavailableException(availability)
        return intervals.filter {
            it.startEpochSecond >= fromInclusive.epochSecond && it.startEpochSecond < toExclusive.epochSecond
        }
    }

    companion object {
        /** Generates a plausible mix of incidental movement and a couple of workout-length bouts per day. */
        fun withSampleData(days: Int, endInstant: Instant, random: Random = Random(42)): FakeStepSource {
            val source = FakeStepSource()
            val dayLength = 24 * 60 * 60L
            val startOfRange = endInstant.epochSecond - days * dayLength

            for (day in 0 until days) {
                val dayStart = startOfRange + day * dayLength

                // Two short incidental bursts (a few minutes of light movement around the house).
                repeat(2) { burst ->
                    val burstStart = dayStart + (8 + burst * 5) * 3600 + random.nextLong(0, 1800)
                    for (minute in 0 until random.nextInt(2, 5)) {
                        source.addInterval(
                            burstStart + minute * 60,
                            burstStart + (minute + 1) * 60,
                            random.nextLong(20, 90),
                        )
                    }
                }

                // One workout-length bout (brisk, sustained cadence for 20+ minutes).
                val workoutStart = dayStart + 18 * 3600 + random.nextLong(0, 1800)
                for (minute in 0 until random.nextInt(20, 35)) {
                    source.addInterval(
                        workoutStart + minute * 60,
                        workoutStart + (minute + 1) * 60,
                        random.nextLong(90, 130),
                    )
                }
            }
            return source
        }
    }
}
