package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.datasource.cache.ContentMetadata
import org.akanework.gramophone.logic.GramophoneDownloadService
import org.akanework.gramophone.logic.getUri
import java.util.concurrent.Executors

/**
 * Offline downloads, stored in the same [JellyfinMediaCache] the player streams through.
 *
 * That sharing is the whole point: a downloaded track is simply a fully populated cache entry, so
 * playback needs no offline branch at all - the existing [CacheDataSource][androidx.media3.datasource.cache.CacheDataSource]
 * finds every byte locally and never reaches the network. It also means a track that was streamed
 * recently may already be partly downloaded.
 */
@OptIn(UnstableApi::class)
object JellyfinDownloadManager {

    private const val TAG = "JellyfinDownloadManager"

    /**
     * media3 downloads one item at a time by default. Three keeps an album moving without saturating
     * a phone's uplink or the server, which for a self-hosted Jellyfin is usually the weaker end.
     */
    private const val PARALLEL_DOWNLOADS = 3

    @Volatile
    private var manager: DownloadManager? = null

    fun get(context: Context): DownloadManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: run {
                val appContext = context.applicationContext
                DownloadManager(
                    appContext,
                    JellyfinMediaCache.databaseProvider(appContext),
                    JellyfinMediaCache.get(appContext),
                    OkHttpDataSource.Factory(JellyfinClientHolder.mediaHttpClient()),
                    Executors.newFixedThreadPool(PARALLEL_DOWNLOADS)
                ).apply {
                    maxParallelDownloads = PARALLEL_DOWNLOADS
                }.also { manager = it }
            }
        }
    }

    /**
     * Queues [items] for download, skipping any that are already complete.
     *
     * The download id is the media id, which is the interned Jellyfin GUID - stable across launches,
     * so a download survives process death and can be matched back to a library item later.
     *
     * Reads the download index, so it must be called off the main thread.
     */
    fun download(context: Context, items: List<MediaItem>) {
        val existing = completedIds(context)
        items.forEach { item ->
            val id = item.mediaId
            if (id in existing) return@forEach
            val uri = item.getUri() ?: return@forEach
            enqueue(context, id, uri)
        }
    }

    private fun enqueue(context: Context, id: String, uri: Uri) {
        try {
            DownloadService.sendAddDownload(
                context,
                GramophoneDownloadService::class.java,
                DownloadRequest.Builder(id, uri).build(),
                /* foreground = */ false
            )
        } catch (e: Exception) {
            // Queueing goes through startService, which the platform refuses in some background
            // states. A download that failed to start must not take the UI down with it.
            Log.w(TAG, "Could not queue download for $id", e)
        }
    }

    fun remove(context: Context, items: List<MediaItem>) {
        items.forEach { item ->
            try {
                DownloadService.sendRemoveDownload(
                    context,
                    GramophoneDownloadService::class.java,
                    item.mediaId,
                    /* foreground = */ false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove download for ${item.mediaId}", e)
            }
        }
    }

    fun removeAll(context: Context) {
        try {
            DownloadService.sendRemoveAllDownloads(
                context,
                GramophoneDownloadService::class.java,
                /* foreground = */ false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear downloads", e)
        }
    }

    /** Media ids that are fully downloaded. Reads the index, so call it off the main thread. */
    fun completedIds(context: Context): Set<String> = try {
        buildSet {
            get(context).downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.download.request.id)
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read download index", e)
        emptySet()
    }

    /**
     * Media ids that can be played without reaching Jellyfin.
     *
     * Downloads and streamed/prefetched audio intentionally share one SimpleCache. The download
     * index only describes items added through DownloadService, so older downloads and fully
     * prefetched tracks must also be discovered from complete cache entries.
     */
    fun availableOfflineIds(
        context: Context,
        items: Collection<MediaItem>,
    ): Set<String> {
        if (items.isEmpty()) return emptySet()
        val completed = completedIds(context)
        val cache = JellyfinMediaCache.get(context)
        return buildSet {
            addAll(completed)
            items.forEach { item ->
                if (item.mediaId.isBlank() || item.mediaId in completed) return@forEach
                val key = item.getUri()?.toString() ?: return@forEach
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                if (length > 0L && cache.isCached(key, 0L, length)) add(item.mediaId)
            }
        }
    }

    /**
     * Whether every playable item in a collection is already available offline.
     *
     * This reads Media3's download index, so callers must invoke it away from the main thread.
     * Keeping the rule here ensures albums, artists, playlists, stations and individual-song
     * menus all agree about whether to offer Download or Delete from device.
     */
    fun areAllDownloaded(context: Context, items: Collection<MediaItem>): Boolean {
        if (items.isEmpty()) return false
        val ids = items.asSequence().map(MediaItem::mediaId).filter(String::isNotBlank).toSet()
        return ids.isNotEmpty() && completedIds(context).containsAll(ids)
    }

    /** Reads the same index for one row/player item. Call off the main thread. */
    fun isDownloaded(context: Context, item: MediaItem): Boolean =
        item.mediaId.isNotBlank() && item.mediaId in completedIds(context)

    /** Bytes occupied by completed downloads, as opposed to incidentally cached streaming data. */
    fun downloadedBytes(context: Context): Long = try {
        var total = 0L
        get(context).downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) {
                total += cursor.download.bytesDownloaded
            }
        }
        total
    } catch (e: Exception) {
        Log.w(TAG, "Could not measure downloads", e)
        0L
    }
}
