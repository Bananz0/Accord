package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.File

/**
 * The on-disk media cache shared by streaming and offline downloads.
 *
 * media3 throws if two [SimpleCache] instances are constructed over the same directory, so both
 * the playback service and (later) the download manager must go through this single instance.
 */
@OptIn(UnstableApi::class)
object JellyfinMediaCache {

    private const val CACHE_DIR_NAME = "jellyfin_media"

    /**
     * Downloads and the streaming cache live in one directory. Evicting by LRU would happily
     * delete a track the user explicitly downloaded for offline use, so eviction is disabled and
     * cache size is managed explicitly from settings instead.
     */
    private const val USE_LRU_EVICTION = false
    private const val LRU_CACHE_SIZE_BYTES = 1024L * 1024L * 1024L

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val dir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)
                val evictor = if (USE_LRU_EVICTION) {
                    LeastRecentlyUsedCacheEvictor(LRU_CACHE_SIZE_BYTES)
                } else {
                    NoOpCacheEvictor()
                }
                SimpleCache(
                    dir,
                    evictor,
                    StandaloneDatabaseProvider(context.applicationContext)
                ).also { cache = it }
            }
        }
    }

    /**
     * The factory ExoPlayer should read through: cache first, Jellyfin over the shared OkHttp
     * connection pool otherwise.
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(JellyfinClientHolder.mediaHttpClient())
        return CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            // Without this a transient network error while part of the track is already cached
            // aborts playback instead of serving what we have.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun currentSizeBytes(context: Context): Long = get(context).cacheSpace

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
