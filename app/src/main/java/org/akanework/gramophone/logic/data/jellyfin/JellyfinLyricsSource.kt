package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.jellyfin.sdk.api.client.extensions.lyricsApi

/**
 * Lyrics served by the Jellyfin server.
 *
 * Covers the case embedded tags cannot: a `.lrc` sitting next to the track on the server, which the
 * player never sees because it only ever receives the audio stream. Jellyfin indexes those sidecars
 * and hands them back as timed lines.
 *
 * The lines are rendered back into LRC text rather than mapped straight to [MediaStoreUtils.Lyric],
 * so they go through the same [LrcUtils.parseLrcString] every other source does and inherit its
 * handling of translations, speaker labels and word timing for free.
 */
object JellyfinLyricsSource {

    private const val TAG = "JellyfinLyricsSource"

    /** Jellyfin timestamps are in ticks of 100 nanoseconds. */
    private const val TICKS_PER_MILLISECOND = 10_000L

    /**
     * Fetches lyrics for [mediaId], or null when the server has none.
     *
     * Suspends on a network call, so it belongs on a background dispatcher - it is already called
     * from the playback service's lyrics worker.
     */
    suspend fun load(
        context: Context,
        mediaId: String?,
        trim: Boolean
    ): MutableList<MediaStoreUtils.Lyric>? {
        if (mediaId == null) return null
        return try {
            val api = JellyfinClientHolder.api() ?: return null
            val remoteId = JellyfinItemResolver.remoteIdForMediaId(context, mediaId) ?: return null
            val uuid = java.util.UUID.fromString(
                with(JellyfinReporter) { remoteId.toDashedUuid() }
            )
            val lines = api.lyricsApi.getLyrics(uuid).content.lyrics
            if (lines.isNullOrEmpty()) return null
            val lrc = buildString {
                lines.forEach { line ->
                    val text = line.text?.takeIf { it.isNotBlank() } ?: return@forEach
                    // Unsynced lyrics come back with no start time. Emitting them without a tag is
                    // correct - the parser treats untagged lines as plain, unsynced lyrics.
                    line.start?.let { append(formatTimestamp(it / TICKS_PER_MILLISECOND)) }
                    append(text).append('\n')
                }
            }
            if (lrc.isBlank()) return null
            LrcUtils.parseLrcString(lrc, trim).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // A server without the lyrics endpoint answers 404, and a track without lyrics is the
            // common case. Neither is worth surfacing - playback carries on without them.
            Log.d(TAG, "No server lyrics for $mediaId: ${e.message}")
            null
        }
    }

    /** Renders milliseconds as an LRC tag: `[mm:ss.cc]`. */
    private fun formatTimestamp(totalMs: Long): String {
        val safeMs = totalMs.coerceAtLeast(0)
        val minutes = safeMs / 60_000
        val seconds = (safeMs % 60_000) / 1000
        val hundredths = (safeMs % 1000) / 10
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }
}
