package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource

/**
 * Rewrites a request to the quality the user asked for, at the moment it is opened.
 *
 * Deliberately not done when the library is built. A [androidx.media3.common.MediaItem] made at
 * sync time would carry whichever quality was configured then, so changing the setting would mean
 * rebuilding nine thousand items, and walking out of the house mid-album would keep streaming
 * lossless over mobile data because the URI was decided indoors. Resolving per request means the
 * answer is always current and nothing upstream has to know about it.
 *
 * Each variant gets its own explicit cache key. Without one, media3 derives the key from the URI
 * and every quality would occupy a separate, unpredictable entry - so eviction after a server-side
 * edit could clear one and leave the others serving pre-edit tags. Keying on the item id plus the
 * quality makes the whole set enumerable from a track alone.
 */
@OptIn(UnstableApi::class)
object StreamQualityResolver {

    /** Query parameters the transcoding request needs and a static request must not carry. */
    private const val PARAM_STATIC = "static"
    private const val PARAM_MAX_BITRATE = "maxStreamingBitrate"
    private const val PARAM_AUDIO_BITRATE = "audioBitRate"
    private const val PARAM_CONTAINER = "container"
    private const val PARAM_AUDIO_CODEC = "audioCodec"
    private const val PARAM_TRANSCODE_CONTAINER = "transcodingContainer"
    private const val PARAM_TRANSCODE_PROTOCOL = "transcodingProtocol"

    /**
     * AAC in an ADTS stream. Chosen over Opus or Vorbis because every Android decoder handles it,
     * and over MP3 because it is materially better at these bitrates.
     */
    private const val TRANSCODE_CODEC = "aac"
    private const val TRANSCODE_CONTAINER = "ts"

    /**
     * @param quality supplied per call rather than read here, so playback and downloading can ask
     *   for different things - they are different settings and a download is about disk, not data.
     */
    fun factory(
        context: Context,
        upstream: androidx.media3.datasource.DataSource.Factory,
        quality: () -> StreamQuality,
    ): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(upstream) { dataSpec ->
            resolve(dataSpec, quality())
        }

    fun resolve(dataSpec: DataSpec, quality: StreamQuality): DataSpec {
        val itemId = dataSpec.uri.itemId() ?: return dataSpec
        val key = cacheKey(itemId, quality)
        if (quality.isOriginal) {
            // Still keyed explicitly, so the untouched file is one enumerable variant among the
            // rest rather than whatever media3 would have derived from the URI.
            return dataSpec.buildUpon().setKey(key).build()
        }
        return dataSpec.buildUpon()
            .setUri(dataSpec.uri.withTranscoding(quality))
            .setKey(key)
            .build()
    }

    /** Every cache key a track could occupy, for eviction that has to clear all of them. */
    fun allCacheKeys(streamUri: String): List<String> {
        val itemId = Uri.parse(streamUri).itemId() ?: return listOf(streamUri)
        return StreamQuality.entries.map { cacheKey(itemId, it) }
    }

    private fun cacheKey(itemId: String, quality: StreamQuality) = "$itemId${quality.cacheSuffix}"

    /**
     * The item's GUID, taken from `/Audio/{id}/stream` or `/Audio/{id}/universal`.
     *
     * Read from the path rather than a query parameter because the id is what identifies the track
     * across every quality, and the query is exactly the part that differs between them.
     */
    private fun Uri.itemId(): String? {
        val segments = pathSegments ?: return null
        val audioIndex = segments.indexOf("Audio")
        if (audioIndex < 0 || audioIndex + 1 >= segments.size) return null
        return segments[audioIndex + 1]
    }

    private fun Uri.withTranscoding(quality: StreamQuality): Uri {
        val builder = buildUpon().clearQuery()
        // Everything except the flags that force direct play, which would make the cap meaningless,
        // and the source container, which no longer describes what arrives.
        queryParameterNames.forEach { name ->
            if (name == PARAM_STATIC || name == PARAM_CONTAINER) return@forEach
            getQueryParameter(name)?.let { builder.appendQueryParameter(name, it) }
        }
        return builder
            .appendQueryParameter(PARAM_MAX_BITRATE, quality.bitrateBps.toString())
            .appendQueryParameter(PARAM_AUDIO_BITRATE, quality.bitrateBps.toString())
            .appendQueryParameter(PARAM_AUDIO_CODEC, TRANSCODE_CODEC)
            .appendQueryParameter(PARAM_TRANSCODE_CONTAINER, TRANSCODE_CONTAINER)
            .appendQueryParameter(PARAM_TRANSCODE_PROTOCOL, "http")
            .build()
    }
}
