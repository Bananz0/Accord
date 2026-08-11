package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.preference.PreferenceManager
import java.io.File

/**
 * The on-disk media cache shared by streaming and offline downloads.
 *
 * media3 throws if two [SimpleCache] instances are constructed over the same directory, so both
 * the playback service and (later) the download manager must go through this single instance.
 */
@OptIn(UnstableApi::class)
object JellyfinMediaCache {

    private const val TAG = "JellyfinMediaCache"

    private const val CACHE_DIR_NAME = "jellyfin_media"

    /**
     * Where the cache used to live. Android clears cacheDir under storage pressure without warning,
     * which would silently delete tracks the user downloaded for offline use, so the cache moved to
     * filesDir. The old directory is removed once so it does not sit there occupying space.
     */
    private const val LEGACY_CACHE_DIR_NAME = "jellyfin_media"

    /**
     * Downloads and the streaming cache live in one directory. media3's own evictor cannot tell the
     * two apart and would happily delete a track the user explicitly downloaded for offline use, so
     * it is left disabled and [trimToLimit] does the eviction instead - same least-recently-used
     * rule, but skipping anything the download index claims.
     */
    private const val PREF_KEY_CACHE_LIMIT = "cache_size_limit"

    /** Matches the first entry of `@array/cache_limit_val`; 0 there means "no ceiling". */
    private const val DEFAULT_CACHE_LIMIT_BYTES = 0L

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    /**
     * The index database behind the cache.
     *
     * The download manager has to be handed this same instance: media3 keeps its cache index and its
     * download index in one database, and opening a second provider over the same file gives the two
     * halves inconsistent views of what is on disk.
     */
    fun databaseProvider(context: Context): StandaloneDatabaseProvider {
        databaseProvider?.let { return it }
        return synchronized(this) {
            databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProvider = it }
        }
    }

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val appContext = context.applicationContext
                deleteLegacyCache(appContext)
                val dir = File(appContext.filesDir, CACHE_DIR_NAME)
                SimpleCache(dir, NoOpCacheEvictor(), databaseProvider(appContext))
                    .also { cache = it }
            }
        }
    }

    private fun deleteLegacyCache(context: Context) {
        val legacy = File(context.cacheDir, LEGACY_CACHE_DIR_NAME)
        if (legacy.isDirectory) {
            legacy.deleteRecursively()
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

    /** The ceiling the user chose, in bytes. Zero means the cache may grow without limit. */
    fun cacheLimitBytes(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getString(PREF_KEY_CACHE_LIMIT, null)
            ?.toLongOrNull()
            ?: DEFAULT_CACHE_LIMIT_BYTES

    /**
     * Evicts streamed audio, oldest touch first, until the cache fits under the chosen ceiling.
     *
     * Downloads are never evicted. A download is a fully populated cache entry sharing this
     * directory, so an ordinary size-based sweep would quietly delete music the user asked to keep
     * offline - the one thing a cache limit must not do. Anything the download index knows about is
     * therefore skipped, which means a library downloaded past the ceiling simply stays over it.
     *
     * Reads the cache index and deletes files, so it must not run on the main thread.
     *
     * @return how many bytes were reclaimed.
     */
    fun trimToLimit(context: Context): Long {
        val appContext = context.applicationContext
        val limit = cacheLimitBytes(appContext)
        if (limit <= 0L) return 0L

        val cache = get(appContext)
        if (cache.cacheSpace <= limit) return 0L

        val protectedKeys = downloadedCacheKeys(appContext)
        val evictable = cache.keys
            .filterNot { it in protectedKeys }
            .flatMap { key -> runCatching { cache.getCachedSpans(key) }.getOrDefault(emptySet()) }
            .filter { it.isCached }
            .sortedBy { it.lastTouchTimestamp }

        var freed = 0L
        for (span in evictable) {
            if (cache.cacheSpace <= limit) break
            val length = span.length
            runCatching { cache.removeSpan(span) }
                .onSuccess { freed += length }
                .onFailure { Log.w(TAG, "Could not evict a cache span", it) }
        }
        if (freed > 0L) Log.d(TAG, "Trimmed $freed bytes to stay under $limit")
        return freed
    }

    /**
     * Drops cached audio for tracks whose file changed on the server.
     *
     * Tags and embedded lyrics are read off the decoded stream when a track plays, so a track
     * already in the cache keeps playing the bytes fetched before the edit - the library shows the
     * new metadata while the player shows the old words. Removing the entry makes the next play
     * fetch the current file.
     *
     * Downloads are skipped, for the same reason [trimToLimit] skips them: deleting one silently
     * takes away music somebody chose to keep offline. A downloaded track therefore keeps its old
     * tags until it is removed and downloaded again, which is a trade worth stating rather than a
     * decision to make on the user's behalf.
     *
     * Reads the cache index and deletes files, so it must not run on the main thread.
     *
     * @return how many entries were dropped.
     */
    fun evictStale(context: Context, keys: Set<String>): Int {
        if (keys.isEmpty()) return 0
        val appContext = context.applicationContext
        val cache = get(appContext)
        val protectedKeys = downloadedCacheKeys(appContext)

        var dropped = 0
        keys.forEach { key ->
            if (key in protectedKeys) return@forEach
            runCatching { cache.removeResource(key) }
                .onSuccess { dropped++ }
                .onFailure { Log.w(TAG, "Could not evict stale entry", it) }
        }
        if (dropped > 0) Log.d(TAG, "Evicted $dropped stale cache entries after a server edit")
        return dropped
    }

    /**
     * Cache keys belonging to downloads, in every state.
     *
     * Keyed by request URI rather than media id: playback, prefetch and downloads all leave the
     * DataSpec key unset, so media3 derives the key from the URI, and matching on anything else
     * would fail to protect the very entries this is meant to spare.
     */
    private fun downloadedCacheKeys(context: Context): Set<String> = try {
        buildSet {
            JellyfinDownloadManager.get(context).downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.download.request.uri.toString())
                }
            }
        }
    } catch (e: Exception) {
        // Failing open would evict downloads. Protect everything rather than guess.
        Log.w(TAG, "Could not read the download index; skipping this trim", e)
        cache?.keys.orEmpty()
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
