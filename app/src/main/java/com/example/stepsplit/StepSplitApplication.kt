package com.example.stepsplit

import android.app.Application
import androidx.work.Configuration
import com.example.stepsplit.di.AppContainer
import com.example.stepsplit.sync.SyncScheduler

class StepSplitApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Idempotent: ExistingPeriodicWorkPolicy.KEEP means this is safe to call on every start.
        SyncScheduler.schedulePeriodicSync(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()
}
