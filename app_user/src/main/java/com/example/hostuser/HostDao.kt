package com.example.hostuser

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Kayitli sunucu adresine erisim DAO'su.
 * TODO.md kurali: getSavedHost(), tek kaydi ekleyen/guncelleyen ve silen islemler.
 */
@Dao
interface HostDao {

    @Query("SELECT * FROM saved_host WHERE id = 1 LIMIT 1")
    fun getSavedHost(): Flow<SavedHost?>

    @Query("SELECT * FROM saved_host WHERE id = 1 LIMIT 1")
    suspend fun getHost(): SavedHost?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveHost(host: SavedHost)

    @Query("DELETE FROM saved_host WHERE id = 1")
    suspend fun deleteHost()
}
