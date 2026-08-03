package org.akanework.gramophone.logic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.akanework.gramophone.logic.data.db.dao.CachedSongDao
import org.akanework.gramophone.logic.data.db.dao.JellyfinIdDao
import org.akanework.gramophone.logic.data.db.dao.MediaItemDao
import org.akanework.gramophone.logic.data.db.dao.PendingScrobbleDao
import org.akanework.gramophone.logic.data.db.dao.PlaylistDao
import org.akanework.gramophone.logic.data.db.entity.CACHED_SONG_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.CachedSong
import org.akanework.gramophone.logic.data.db.entity.JELLYFIN_ID_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.JellyfinId
import org.akanework.gramophone.logic.data.db.entity.MediaItem
import org.akanework.gramophone.logic.data.db.entity.PENDING_SCROBBLE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.PendingScrobble
import org.akanework.gramophone.logic.data.db.entity.Playlist
import org.akanework.gramophone.logic.data.db.entity.PlaylistMediaItemCrossRef

const val APP_DATABASE_FILE_NAME = "app.db"

@Database(
    entities = [
        Playlist::class,
        MediaItem::class,
        PlaylistMediaItemCrossRef::class,
        JellyfinId::class,
        CachedSong::class,
        PendingScrobble::class,
    ],
    version = 4,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun jellyfinIdDao(): JellyfinIdDao
    abstract fun cachedSongDao(): CachedSongDao
    abstract fun pendingScrobbleDao(): PendingScrobbleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Adds the Jellyfin GUID interning table. This is a plain additive migration - destructive
         * fallback is not an option here, because dropping the table would invalidate every ID
         * already written into the saved playback queue and the user's private playlists.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$JELLYFIN_ID_TABLE_NAME` (" +
                            "`${JellyfinId.LOCAL_ID_COLUMN}` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`${JellyfinId.JELLYFIN_ID_COLUMN}` TEXT NOT NULL, " +
                            "`${JellyfinId.ITEM_TYPE_COLUMN}` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_${JELLYFIN_ID_TABLE_NAME}_${JellyfinId.JELLYFIN_ID_COLUMN}` " +
                            "ON `$JELLYFIN_ID_TABLE_NAME` (`${JellyfinId.JELLYFIN_ID_COLUMN}`)"
                )
            }
        }

        /**
         * Adds the library metadata cache. Purely additive; the table starts empty and the next
         * sync fills it.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$CACHED_SONG_TABLE_NAME` (" +
                            "`localId` INTEGER PRIMARY KEY NOT NULL, " +
                            "`jellyfinId` TEXT NOT NULL, " +
                            "`title` TEXT, `artist` TEXT, `artistId` INTEGER, " +
                            "`album` TEXT, `albumId` INTEGER, `albumArtist` TEXT, " +
                            "`genre` TEXT, `genreId` INTEGER, `albumYear` INTEGER, " +
                            "`trackNumber` INTEGER, `discNumber` INTEGER, " +
                            "`durationMs` INTEGER, `addDate` INTEGER, `path` TEXT, " +
                            "`container` TEXT, `mediaSourceId` TEXT, " +
                            "`albumJellyfinId` TEXT, `albumImageTag` TEXT, `ownImageTag` TEXT, " +
                            "`playCount` INTEGER NOT NULL, `isFavourite` INTEGER NOT NULL, " +
                            "`lastPlayed` INTEGER)"
                )
            }
        }

        /**
         * Adds the offline scrobble queue. Additive; it starts empty.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$PENDING_SCROBBLE_TABLE_NAME` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`album` TEXT, `albumArtist` TEXT, " +
                            "`durationSeconds` INTEGER, `trackNumber` INTEGER, " +
                            "`timestampSeconds` INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    APP_DATABASE_FILE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .apply { instance = this }
            }
        }
    }
}
