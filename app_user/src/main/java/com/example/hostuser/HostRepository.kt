package com.example.hostuser

import kotlinx.coroutines.flow.Flow

/**
 * Host verilerine erisimi soyutlayan repository.
 */
class HostRepository(private val hostDao: HostDao) {

    val savedHostFlow: Flow<SavedHost?> = hostDao.getSavedHost()

    suspend fun getHost(): SavedHost? = hostDao.getHost()

    suspend fun saveHost(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isNotEmpty()) {
            hostDao.saveHost(SavedHost(id = 1, url = trimmed))
        }
    }

    suspend fun deleteHost() {
        hostDao.deleteHost()
    }
}
