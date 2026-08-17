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
        // Step import is foreground-only - see SyncScheduler's own doc comment. This only cancels
        // an old periodic WorkManager job that may still be persisted from a prior app version; it
        // schedules nothing new, and is a safe no-op once that job is gone.
        SyncScheduler.cleanUp(this)
    }

    // On-demand WorkManager initialization (see the manifest's own doc comment on why this stays)
    // with a plain default Configuration - no custom WorkerFactory: this app defines no Worker
    // class of its own anymore.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
