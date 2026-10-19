package com.example.stepsplit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceHealthStore
import com.example.stepsplit.ui.common.StepPermissionAction
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * After two "Don't allow" answers Health Connect stops showing its permission dialog, so the Today
 * banner must offer Health Connect's own screen after a denial, and pick up a permission allowed
 * there as soon as the owner comes back - with no extra tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodayViewModelPermissionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneOffset.UTC)
    private val stepsAt = Instant.parse("2026-03-10T19:00:00Z").epochSecond
    private lateinit var database: StepSplitDatabase
    private lateinit var settings: SettingsRepository
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        // Main runs at once, as Dispatchers.Main.immediate does on the phone.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        settings = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `before any request the banner offers Health Connect's dialog`() = runTest {
        val source = FakeStepSource(availability = StepSourceAvailability.PermissionNotGranted)
        val viewModel = todayViewModel(source)

        viewModel.refresh()

        assertEquals(StepPermissionAction.GRANT_PERMISSION, awaitState(viewModel) { it.availability != null }.permissionAction)
    }

    @Test
    fun `a denied request switches the banner to Health Connect's own screen`() = runTest {
        val source = FakeStepSource(availability = StepSourceAvailability.PermissionNotGranted)
        val viewModel = todayViewModel(source)
        viewModel.refresh()
        awaitState(viewModel) { it.availability != null }

        viewModel.onPermissionRequestResult(granted = false)

        val state = awaitState(viewModel) { it.permissionRequestDenied }
        assertEquals(StepSourceAvailability.PermissionNotGranted, state.availability)
        assertEquals(StepPermissionAction.OPEN_HEALTH_CONNECT, state.permissionAction)
    }

    @Test
    fun `a granted request imports the steps and removes the banner`() = runTest {
        val source = FakeStepSource(availability = StepSourceAvailability.PermissionNotGranted)
        source.addInterval(stepsAt, stepsAt + 60, 120)
        val viewModel = todayViewModel(source)
        viewModel.refresh()
        awaitState(viewModel) { it.availability != null }

        source.setAvailability(StepSourceAvailability.Available)
        viewModel.onPermissionRequestResult(granted = true)

        val state = awaitState(viewModel) { it.today?.totalSteps == 120L }
        assertEquals(StepSourceAvailability.Available, state.availability)
        assertNull(state.permissionAction)
    }

    @Test
    fun `coming back after allowing Steps in Health Connect imports the steps and removes the banner`() = runTest {
        val source = FakeStepSource(availability = StepSourceAvailability.PermissionNotGranted)
        source.addInterval(stepsAt, stepsAt + 60, 120)
        val viewModel = todayViewModel(source)
        viewModel.refresh()
        awaitState(viewModel) { it.availability != null }
        viewModel.onPermissionRequestResult(granted = false)

        // Allowed in Health Connect's own screen, then Back.
        source.setAvailability(StepSourceAvailability.Available)
        viewModel.recheckPermission()

        val state = awaitState(viewModel) { it.today?.totalSteps == 120L && it.availability == StepSourceAvailability.Available }
        assertNull(state.permissionAction)
    }

    @Test
    fun `coming back without allowing Steps keeps offering Health Connect's screen`() = runTest {
        val source = FakeStepSource(availability = StepSourceAvailability.PermissionNotGranted)
        val viewModel = todayViewModel(source)
        viewModel.refresh()
        awaitState(viewModel) { it.availability != null }
        viewModel.onPermissionRequestResult(granted = false)

        viewModel.recheckPermission()

        val state = awaitState(viewModel) { it.permissionRequestDenied }
        assertEquals(StepSourceAvailability.PermissionNotGranted, state.availability)
        assertEquals(StepPermissionAction.OPEN_HEALTH_CONNECT, state.permissionAction)
    }

    /**
     * A refresh that started before the owner left can't see a permission allowed meanwhile. The
     * re-check on return must run after it, not be dropped as an overlap - or the banner would stay
     * until the next minute's refresh.
     */
    @Test
    fun `the re-check on return waits for a refresh still running, then checks again`() = runTest {
        val source = SteppedAvailabilitySource(FakeStepSource(availability = StepSourceAvailability.PermissionNotGranted))
        source.delegate.addInterval(stepsAt, stepsAt + 60, 120)
        val viewModel = todayViewModel(source)

        source.stepping = true
        viewModel.refresh()
        source.nextWaitingCheck().complete(Unit) // its check before syncing
        source.nextWaitingCheck().complete(Unit) // the sync's own check
        val lastCheck = source.nextWaitingCheck() // its check after syncing: answered "not allowed"

        source.delegate.setAvailability(StepSourceAvailability.Available)
        viewModel.recheckPermission()
        viewModel.refresh() // the resume that comes with the return
        source.stepping = false
        lastCheck.complete(Unit)

        val state = awaitState(viewModel) { it.today?.totalSteps == 120L && it.availability == StepSourceAvailability.Available }
        assertNull(state.permissionAction)
    }

    /** Holds every availability check while [stepping], so a test can stop a refresh part-way. The answer is fixed when the check starts. */
    private class SteppedAvailabilitySource(val delegate: FakeStepSource) : StepSource by delegate {
        @Volatile var stepping = false
        private val waiting = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)

        override suspend fun checkAvailability(): StepSourceAvailability {
            val answer = delegate.checkAvailability()
            if (stepping) {
                val release = CompletableDeferred<Unit>()
                waiting.send(release)
                release.await()
            }
            return answer
        }

        suspend fun nextWaitingCheck(): CompletableDeferred<Unit> =
            withContext(Dispatchers.Default) { withTimeout(5_000) { waiting.receive() } }
    }

    private fun TestScope.todayViewModel(source: StepSource): TodayViewModel {
        val repository = StepRepository(database, source, settings, clock, backgroundScope)
        val viewModel = viewModelIn(store) { TodayViewModel(repository, settings, StepSourceHealthStore(context), clock) }
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    /** Room answers on its own threads, so this waits in real time. */
    private suspend fun awaitState(viewModel: TodayViewModel, predicate: (TodayUiState) -> Boolean): TodayUiState =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!predicate(viewModel.uiState.value)) delay(10)
                viewModel.uiState.value
            }
        }

    private fun <T : ViewModel> viewModelIn(store: ViewModelStore, create: () -> T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        }
        @Suppress("UNCHECKED_CAST")
        return ViewModelProvider(store, factory)[ViewModel::class.java] as T
    }
}
