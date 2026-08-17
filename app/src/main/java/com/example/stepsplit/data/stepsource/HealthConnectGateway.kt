package com.example.stepsplit.data.stepsource

import android.content.Context
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/** The one Health Connect permission this app ever requests - read-only step counts. */
val READ_STEPS_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)

/**
 * On-device native step counting requires Android 14+ (API 34) with SDK extension version >= 20 -
 * see [HealthConnectGateway.isOnDeviceStepCountingSupported]'s own doc comment.
 */
private const val MIN_STEP_COUNTING_EXTENSION_VERSION = 20

/** Health Connect's own [HealthConnectClient.getSdkStatus] outcome, translated into a source-independent type so nothing outside this file ever needs the real SDK's int constants. */
enum class HealthConnectSdkStatus { AVAILABLE, UNAVAILABLE, UPDATE_REQUIRED }

/**
 * The narrow slice of the real Health Connect SDK [HealthConnectStepSource] actually needs -
 * mirrors how the (now removed) Local Recording source confined every real Play-services-fitness
 * type to its own dedicated gateway. Keeping this boundary narrow means [HealthConnectStepSource]
 * itself stays a plain, pure-JVM-testable class against [FakeHealthConnectGateway] in tests - no
 * Robolectric shadow for Health Connect exists (or is needed).
 */
interface HealthConnectGateway {
    /** See this app's product requirement: Android 14+ with SDK extension level >= 20. */
    fun isOnDeviceStepCountingSupported(): Boolean

    fun sdkStatus(): HealthConnectSdkStatus

    suspend fun isReadStepsPermissionGranted(): Boolean

    /**
     * Positive-only step intervals for `[fromInclusive, toExclusive)`, whose starts are always
     * exact UTC epoch-minute boundaries regardless of [fromInclusive]'s own seconds/nanoseconds -
     * see [alignedStepIntervalRequestWindows]. The very last interval's *end* may still be
     * [toExclusive] itself (an honest, shorter-than-a-minute trailing edge ending "now") - only
     * interval starts are guaranteed aligned. Health Connect's own `aggregateGroupByDuration` call
     * caps how many buckets a single request may produce, so a window wider than [CHUNK_DURATION]
     * is split into several sequential calls and stitched back together here - callers never need
     * to know about that limit.
     */
    suspend fun readStepIntervals(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval>

    companion object {
        /** Comfortably under Health Connect's own per-request bucket cap at a 1-minute slicer. */
        val CHUNK_DURATION: Duration = Duration.ofHours(12)
    }
}

/**
 * Splits `[fromInclusive, toExclusive)` into a sequence of sequential, non-overlapping request
 * windows suitable for [HealthConnectGateway.readStepIntervals]'s own chunked
 * `aggregateGroupByDuration` calls, enforcing the alignment contract every one of those calls
 * depends on: **every** window start must land on an exact UTC epoch-minute boundary, never on
 * whatever arbitrary second/nanosecond [fromInclusive] happens to carry.
 *
 * This matters because `aggregateGroupByDuration`'s one-minute buckets are anchored to the
 * request's *own* start time, not to wall-clock minute boundaries - a request starting at
 * `12:34:37` produces buckets like `12:34:37-12:35:37`, not `12:34:00-12:35:00`. Every consumer
 * downstream of this (starting with [com.example.stepsplit.domain.aggregation.BucketNormalizer])
 * assumes a bucket that isn't already minute-aligned spans two calendar minutes and splits its
 * steps across both - silently misattributing real step data by up to a minute. [fromInclusive]
 * is therefore floored down to the start of its own minute before anything else happens; a first
 * sync on a fresh, empty database (whose window start is `now.minus(retentionWindow)` - itself as
 * arbitrarily unaligned as `now`) follows exactly the same rule as every later resync.
 *
 * [HealthConnectGateway.CHUNK_DURATION] is an exact multiple of one minute (12h = 720 minutes), so
 * once the very first window start is aligned, every subsequent chunk boundary
 * (`chunkStart + CHUNK_DURATION`) stays aligned automatically by simple addition - no per-chunk
 * realignment is needed. Only the very last window's end is ever [toExclusive] itself, which is
 * deliberately left as-is: a final partial-minute interval ending "now" is an accepted, honest
 * trailing edge (see [HealthConnectGateway.readStepIntervals]'s own doc comment), not a bug -
 * every window's *start* is still guaranteed aligned, which is the only thing
 * [com.example.stepsplit.domain.aggregation.BucketNormalizer] actually depends on to avoid
 * splitting a bucket across two minutes.
 *
 * A pure function of its inputs (no Health Connect SDK types involved) specifically so this exact
 * boundary math - not a reimplementation of it - can be unit tested directly with clocks carrying
 * nonzero seconds/nanoseconds, without needing Robolectric or a fake Health Connect client.
 */
internal fun alignedStepIntervalRequestWindows(fromInclusive: Instant, toExclusive: Instant): List<ClosedOpenInstantRange> {
    val alignedStart = alignDownToMinute(fromInclusive)
    if (!alignedStart.isBefore(toExclusive)) return emptyList()

    val windows = mutableListOf<ClosedOpenInstantRange>()
    var chunkStart = alignedStart
    while (chunkStart < toExclusive) {
        val chunkEnd = minOf(chunkStart + HealthConnectGateway.CHUNK_DURATION, toExclusive)
        windows += ClosedOpenInstantRange(chunkStart, chunkEnd)
        chunkStart = chunkEnd
    }
    return windows
}

/** Floors [instant] down to the start of its own UTC minute - e.g. `12:34:37.123Z` -> `12:34:00Z`. */
internal fun alignDownToMinute(instant: Instant): Instant {
    val epochSecond = instant.epochSecond
    return Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, 60L))
}

/** `[start, endExclusive)` - one chunked request window for [HealthConnectGateway.readStepIntervals]. */
internal data class ClosedOpenInstantRange(val start: Instant, val endExclusive: Instant)

/** Real, Health Connect SDK-backed implementation - the only place this app's code touches `androidx.health.connect.client` types directly. */
class PlatformHealthConnectGateway(private val context: Context) : HealthConnectGateway {

    override fun isOnDeviceStepCountingSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= MIN_STEP_COUNTING_EXTENSION_VERSION

    override fun sdkStatus(): HealthConnectSdkStatus = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectSdkStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectSdkStatus.UPDATE_REQUIRED
        else -> HealthConnectSdkStatus.UNAVAILABLE
    }

    override suspend fun isReadStepsPermissionGranted(): Boolean {
        if (sdkStatus() != HealthConnectSdkStatus.AVAILABLE) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().contains(READ_STEPS_PERMISSION)
    }

    override suspend fun readStepIntervals(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
        val client = HealthConnectClient.getOrCreate(context)
        val result = mutableListOf<RawStepInterval>()
        for (window in alignedStepIntervalRequestWindows(fromInclusive, toExclusive)) {
            val response = client.aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(window.start, window.endExclusive),
                    timeRangeSlicer = Duration.ofMinutes(1),
                ),
            )
            for (bucket in response) {
                val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L
                if (steps > 0) {
                    result += RawStepInterval(bucket.startTime.epochSecond, bucket.endTime.epochSecond, steps)
                }
            }
        }
        return result
    }
}
