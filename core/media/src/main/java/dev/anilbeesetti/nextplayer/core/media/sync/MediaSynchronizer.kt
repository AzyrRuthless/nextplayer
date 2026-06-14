package dev.anilbeesetti.nextplayer.core.media.sync

import kotlinx.coroutines.flow.StateFlow

interface MediaSynchronizer {
    suspend fun refresh(path: String? = null): Boolean
    fun startSync()
    fun stopSync()
    val syncProgress: StateFlow<Int>
}
