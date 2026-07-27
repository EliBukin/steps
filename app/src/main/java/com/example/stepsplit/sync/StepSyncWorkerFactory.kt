package com.example.stepsplit.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.stepsplit.data.repository.StepRepository

/** Constructs [StepSyncWorker] with its repository dependency injected manually - no reflection. */
class StepSyncWorkerFactory(private val stepRepository: StepRepository) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        StepSyncWorker::class.java.name -> StepSyncWorker(appContext, workerParameters, stepRepository)
        else -> null
    }
}
