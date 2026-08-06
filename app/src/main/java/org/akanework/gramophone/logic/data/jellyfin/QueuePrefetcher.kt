package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.offline.DownloadRequest
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Warms up the next few tracks so skipping does not wait on the network.
 *
 * Every track streams from Jellyfin, and nothing beyond the one playing was ever fetched ahead. On
 * a shuffled library that means each skip is a fresh connection before a note is heard and before
 * the artwork exists - which is the pause that reads as the app hanging.
 *
 * Only the beginning of each track is fetched. That is what a skip needs: enough for playback to
 * start immediately while the rest streams normally behind it. Fetching whole tracks ahead would be
 * a download, not a prefetch, and the user did not ask for one.
 */
@OptIn(UnstableApi::class)
object QueuePrefetcher {

    /** How many tracks ahead to warm. Beyond this, a listener has usually skipped somewhere else. */
    private const val LOOKAHEAD = 5

    /** Roughly the first few seconds of a lossless track - enough to start instantly. */
    private const val PREFETCH_BYTES = 1_500_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Media ids already warmed, so a queue that shuffles back does not refetch. */
    private val warmed = mutableSetOf<String>()

    /**
     * Warms [upcoming], which should be the tracks after the one playing, nearest first.
     *
     * Cancels any prefetch still running: the queue has moved on, and finishing the old one would
     * compete with the track that is actually playing for the same connection pool.
     */
    fun prefetch(context: Context, upcoming: List<MediaItem>, imageLoader: ImageLoader) {
        job?.cancel()
        if (upcoming.isEmpty()) return
        val appContext = context.applicationContext
        val targets = upcoming.take(LOOKAHEAD)

        job = scope.launch {
            for (item in targets) {
                ensureActive()
                // Artwork first: it is small, and a missing cover is the part of a skip people see.
                item.mediaMetadata.artworkUri?.let { uri ->
                    imageLoader.enqueue(ImageRequest.Builder(appContext).data(uri).build())
                }
                if (!warmed.add(item.mediaId)) continue
                ensureActive()
                warmAudio(appContext, item)
            }
        }
    }

    /** Pulls the first stretch of a track into the same cache playback reads from. */
    private fun warmAudio(context: Context, item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        runCatching {
            val source = JellyfinMediaCache.dataSourceFactory(context).createDataSource()
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0)
                .setLength(PREFETCH_BYTES)
                // Same key playback will use, or the warmed bytes would sit under a key nothing reads.
                .setKey(DownloadRequest.Builder(item.mediaId, uri).build().id)
                .build()
            CacheWriter(source as? CacheDataSource ?: return, spec, null, null).cache()
        }.onFailure {
            // A prefetch is best-effort by definition; the track still plays if this failed.
            Log.d(TAG, "Could not warm ${item.mediaMetadata.title}: $it")
            warmed.remove(item.mediaId)
        }
    }

    /** Drops the record of what has been warmed, for a sign-out or a cleared cache. */
    fun reset() {
        job?.cancel()
        warmed.clear()
    }

    private const val TAG = "QueuePrefetcher"
}
