package com.example.stepsplit.ui.settings

import androidx.lifecycle.ViewModel
import com.example.stepsplit.data.mappack.MapPackRepository
import com.example.stepsplit.data.mappack.MapPacksState
import kotlinx.coroutines.flow.StateFlow

/**
 * The Offline maps part of Settings. Opening Settings reads the server's list of maps once (and
 * only then: nothing is checked in the background); everything else is the user's taps.
 */
class OfflineMapsViewModel(private val repository: MapPackRepository) : ViewModel() {
    val state: StateFlow<MapPacksState> = repository.state

    init {
        repository.refreshManifest()
    }

    fun retry() = repository.refreshManifest()

    fun download(regionId: String) = repository.download(regionId)

    fun cancelDownload() = repository.cancelDownload()

    fun delete(regionId: String) = repository.delete(regionId)

    fun setWifiOnly(wifiOnly: Boolean) = repository.setWifiOnly(wifiOnly)
}
