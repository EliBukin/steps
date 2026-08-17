package com.example.stepsplit.data.stepsource

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Configurable [HealthConnectGateway] test double - no Robolectric, no real Health Connect SDK object ever touched. */
private class FakeHealthConnectGateway(
    private var supported: Boolean = true,
    private var status: HealthConnectSdkStatus = HealthConnectSdkStatus.AVAILABLE,
    private var permissionGranted: Boolean = true,
    private var readException: Throwable? = null,
    private var intervals: List<RawStepInterval> = emptyList(),
) : HealthConnectGateway {
    var readCalls = 0
        private set

    fun setSupported(value: Boolean) { supported = value }
    fun setStatus(value: HealthConnectSdkStatus) { status = value }
    fun setPermissionGranted(value: Boolean) { permissionGranted = value }
    fun setReadException(value: Throwable?) { readException = value }
    fun setIntervals(value: List<RawStepInterval>) { intervals = value }

    override fun isOnDeviceStepCountingSupported(): Boolean = supported
    override fun sdkStatus(): HealthConnectSdkStatus = status
    override suspend fun isReadStepsPermissionGranted(): Boolean = permissionGranted

    override suspend fun readStepIntervals(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
        readCalls++
        readException?.let { throw it }
        return intervals
    }
}

/** Records every call for assertions - a plain in-memory [StepSourceHealthSink], no async draining involved. */
private class RecordingHealthSink : StepSourceHealthSink {
    var subscriptionSuccesses = 0
        private set
    var subscriptionFailures = 0
        private set
    var lastSubscriptionFailureCategory: ApiFailureCategory? = null
        private set
    var readAttempts = 0
        private set
    var readSuccesses = 0
        private set
    var lastReadSuccessIntervalCount: Int? = null
        private set
    var lastReadSuccessLatestSample: Long? = null
        private set
    var readFailures = 0
        private set
    var lastReadFailureCategory: ApiFailureCategory? = null
        private set

    override fun recordSubscriptionSuccess(atEpochSecond: Long) { subscriptionSuccesses++ }
    override fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) {
        subscriptionFailures++
        lastSubscriptionFailureCategory = failure.category
    }
    override fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) { readAttempts++ }
    override fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) {
        readSuccesses++
        lastReadSuccessIntervalCount = intervalCount
        lastReadSuccessLatestSample = latestSampleEpochSecond
    }
    override fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) {
        readFailures++
        lastReadFailureCategory = failure.category
    }
}

class HealthConnectStepSourceTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC)

    private fun source(gateway: HealthConnectGateway, healthStore: StepSourceHealthSink = RecordingHealthSink()) =
        HealthConnectStepSource(gateway, healthStore, fixedClock)

    @Test
    fun `id reuses the old Local Recording source's own id - existing history stays visible under it`() {
        assertEquals("local_recording_api", HealthConnectStepSource(FakeHealthConnectGateway(), RecordingHealthSink()).id)
    }

    @Test
    fun `on-device step counting unsupported reports ApiUnavailable regardless of SDK status or permission`() = runTest {
        val gateway = FakeHealthConnectGateway(supported = false, status = HealthConnectSdkStatus.AVAILABLE, permissionGranted = true)
        assertEquals(StepSourceAvailability.ApiUnavailable, source(gateway).checkAvailability())
    }

    @Test
    fun `Health Connect provider not installed reports ApiUnavailable`() = runTest {
        val gateway = FakeHealthConnectGateway(status = HealthConnectSdkStatus.UNAVAILABLE)
        assertEquals(StepSourceAvailability.ApiUnavailable, source(gateway).checkAvailability())
    }

    @Test
    fun `Health Connect provider needing an update reports PlayServicesUpdateRequired`() = runTest {
        val gateway = FakeHealthConnectGateway(status = HealthConnectSdkStatus.UPDATE_REQUIRED)
        assertEquals(StepSourceAvailability.PlayServicesUpdateRequired, source(gateway).checkAvailability())
    }

    @Test
    fun `READ_STEPS not granted reports PermissionNotGranted`() = runTest {
        val gateway = FakeHealthConnectGateway(permissionGranted = false)
        assertEquals(StepSourceAvailability.PermissionNotGranted, source(gateway).checkAvailability())
    }

    @Test
    fun `supported device, available provider, granted permission reports Available`() = runTest {
        val gateway = FakeHealthConnectGateway(supported = true, status = HealthConnectSdkStatus.AVAILABLE, permissionGranted = true)
        assertEquals(StepSourceAvailability.Available, source(gateway).checkAvailability())
    }

    @Test
    fun `ensureSubscribed succeeds and records success when available`() = runTest {
        val healthStore = RecordingHealthSink()
        val result = source(FakeHealthConnectGateway(), healthStore).ensureSubscribed()

        assertTrue(result)
        assertEquals(1, healthStore.subscriptionSuccesses)
        assertEquals(0, healthStore.subscriptionFailures)
    }

    @Test
    fun `ensureSubscribed fails and records a permission failure when READ_STEPS is not granted`() = runTest {
        val healthStore = RecordingHealthSink()
        val result = source(FakeHealthConnectGateway(permissionGranted = false), healthStore).ensureSubscribed()

        assertTrue(!result)
        assertEquals(0, healthStore.subscriptionSuccesses)
        assertEquals(1, healthStore.subscriptionFailures)
        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, healthStore.lastSubscriptionFailureCategory)
    }

    @Test
    fun `ensureSubscribed fails and records an availability failure when unsupported`() = runTest {
        val healthStore = RecordingHealthSink()
        val result = source(FakeHealthConnectGateway(supported = false), healthStore).ensureSubscribed()

        assertTrue(!result)
        assertEquals(ApiFailureCategory.API_UNAVAILABLE, healthStore.lastSubscriptionFailureCategory)
    }

    @Test
    fun `readSteps returns the gateway's own intervals and records a successful read`() = runTest {
        val gateway = FakeHealthConnectGateway(intervals = listOf(RawStepInterval(1_000, 1_060, 42)))
        val healthStore = RecordingHealthSink()

        val result = source(gateway, healthStore).readSteps(Instant.ofEpochSecond(0), Instant.ofEpochSecond(10_000))

        assertEquals(listOf(RawStepInterval(1_000, 1_060, 42)), result)
        assertEquals(1, healthStore.readAttempts)
        assertEquals(1, healthStore.readSuccesses)
        assertEquals(1, healthStore.lastReadSuccessIntervalCount)
        assertEquals(1_060L, healthStore.lastReadSuccessLatestSample)
    }

    @Test
    fun `an empty successful read is still recorded as success, never as a failure`() = runTest {
        val healthStore = RecordingHealthSink()

        val result = source(FakeHealthConnectGateway(intervals = emptyList()), healthStore)
            .readSteps(Instant.ofEpochSecond(0), Instant.ofEpochSecond(10_000))

        assertTrue(result.isEmpty())
        assertEquals(1, healthStore.readSuccesses)
        assertEquals(0, healthStore.readFailures)
        assertNull(healthStore.lastReadSuccessLatestSample)
    }

    @Test
    fun `readSteps throws StepSourceUnavailableException without ever calling the gateway when unavailable`() = runTest {
        val gateway = FakeHealthConnectGateway(permissionGranted = false)
        val healthStore = RecordingHealthSink()

        val thrown = assertThrows(StepSourceUnavailableException::class.java) {
            kotlinx.coroutines.runBlocking { source(gateway, healthStore).readSteps(Instant.ofEpochSecond(0), Instant.ofEpochSecond(10_000)) }
        }

        assertEquals(StepSourceAvailability.PermissionNotGranted, thrown.availability)
        assertEquals(0, gateway.readCalls)
        assertEquals(0, healthStore.readAttempts)
    }

    @Test
    fun `a gateway read failure is wrapped as StepSourceReadException and recorded, never silently empty`() = runTest {
        val gateway = FakeHealthConnectGateway(readException = SecurityException("permission revoked mid-read"))
        val healthStore = RecordingHealthSink()

        val thrown = assertThrows(StepSourceReadException::class.java) {
            kotlinx.coroutines.runBlocking { source(gateway, healthStore).readSteps(Instant.ofEpochSecond(0), Instant.ofEpochSecond(10_000)) }
        }

        assertEquals(ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE, thrown.apiFailure?.category)
        assertEquals(1, healthStore.readAttempts)
        assertEquals(1, healthStore.readFailures)
        assertEquals(0, healthStore.readSuccesses)
    }
}
