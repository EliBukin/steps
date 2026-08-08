package com.example.stepsplit

import android.app.Application
import androidx.work.Configuration
import com.example.stepsplit.di.AppContainer
import com.example.stepsplit.sync.SyncScheduler
import kotlinx.coroutines.launch

class StepSplitApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Idempotent: ExistingPeriodicWorkPolicy.KEEP means this is safe to call on every start.
        SyncScheduler.schedulePeriodicSync(this)
        // Idempotent (see MotionEvidenceRegistrar.ensureRegistered's own doc comment) - fire-and-
        // forget on the process-lifetime motionEvidenceScope, since Application.onCreate is not a
        // suspend context. Deliberately NOT tied to any Activity's lifecycle, so registration (and
        // therefore evidence collection) stays live even while the UI is fully closed.
        container.motionEvidenceScope.launch { container.motionEvidenceRegistrar.ensureRegistered() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()
}
