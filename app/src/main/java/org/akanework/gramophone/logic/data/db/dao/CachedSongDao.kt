package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.akanework.gramophone.logic.data.db.entity.CACHED_SONG_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.CachedSong

@Dao
interface CachedSongDao {

    @Query("SELECT * FROM $CACHED_SONG_TABLE_NAME")
    fun getAll(): List<CachedSong>

    @Query("SELECT * FROM $CACHED_SONG_TABLE_NAME LIMIT :limit")
    fun getInitialFast(limit: Int = 100): List<CachedSong>

    @Query("SELECT COUNT(*) FROM $CACHED_SONG_TABLE_NAME")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(songs: List<CachedSong>)

    @Query("DELETE FROM $CACHED_SONG_TABLE_NAME")
    fun deleteAll()

    /**
     * Swaps in a freshly synced library.
     *
     * Done in one transaction so a crash or a kill mid-write cannot leave a half-written library
     * that would then be trusted as complete on next launch.
     */
    @Transaction
    fun replaceAll(songs: List<CachedSong>) {
        deleteAll()
        songs.chunked(CHUNK_SIZE).forEach { insertAll(it) }
    }

    companion object {
        /** SQLite caps variables per statement; large libraries must go in batches. */
        private const val CHUNK_SIZE = 500
    }
}
