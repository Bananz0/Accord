package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID

/**
 * Tells the Jellyfin server what is being played, and syncs favourites.
 *
 * Without this the server never learns a track was played, so play counts, "recently played" and
 * resume position stay frozen at whatever the last web-client session left behind - and the same
 * library looks untouched from every other client.
 *
 * Every call is fire-and-forget on a background scope: reporting is best-effort telemetry and must
 * never delay or break playback if the server is unreachable.
 */
class JellyfinReporter(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ticks are Jellyfin's unit throughout its API: 100 nanoseconds. Reporting milliseconds
     * directly would tell the server every track resumes 10,000x too early.
     */
    private fun Long.msToTicks(): Long = this * TICKS_PER_MILLISECOND

    fun reportStart(mediaId: String?, positionMs: Long, isPaused: Boolean) = report(mediaId) { api, id ->
        api.playStateApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = id,
                positionTicks = positionMs.msToTicks(),
                isPaused = isPaused,
                canSeek = true,
                isMuted = false,
                // Tracks stream with static=true, so the server is serving the original file
                // untouched rather than transcoding.
                playMethod = PlayMethod.DIRECT_PLAY,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
        Log.d(TAG, "Reported playback start for $id at ${positionMs}ms")
    }

    fun reportProgress(mediaId: String?, positionMs: Long, isPaused: Boolean) = report(mediaId) { api, id ->
        api.playStateApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                itemId = id,
                positionTicks = positionMs.msToTicks(),
                isPaused = isPaused,
                canSeek = true,
                isMuted = false,
                // Tracks stream with static=true, so the server is serving the original file
                // untouched rather than transcoding.
                playMethod = PlayMethod.DIRECT_PLAY,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    }

    fun reportStopped(mediaId: String?, positionMs: Long) = report(mediaId) { api, id ->
        api.playStateApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = id,
                positionTicks = positionMs.msToTicks(),
                failed = false,
            )
        )
        Log.d(TAG, "Reported playback stopped for $id at ${positionMs}ms")
    }

    /** Mirrors a favourite toggle to the server so other clients see it. */
    fun setFavourite(mediaId: String?, favourite: Boolean) = report(mediaId) { api, id ->
        if (favourite) {
            api.userLibraryApi.markFavoriteItem(itemId = id)
        } else {
            api.userLibraryApi.unmarkFavoriteItem(itemId = id)
        }
        Log.d(TAG, "Marked $id favourite=$favourite")
    }

    private inline fun report(
        mediaId: String?,
        crossinline block: suspend (org.jellyfin.sdk.api.client.ApiClient, UUID) -> Unit
    ) {
        if (mediaId == null) return
        scope.launch(NonCancellable) {
            try {
                val api = JellyfinClientHolder.api() ?: return@launch
                val remote = JellyfinItemResolver.remoteIdForMediaId(context, mediaId) ?: return@launch
                block(api, UUID.fromString(remote.toDashedUuid()))
            } catch (e: Exception) {
                // Telemetry only - a failure here must not surface to the user or stop playback.
                Log.w(TAG, "Reporting call failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "JellyfinReporter"
        private const val TICKS_PER_MILLISECOND = 10_000L

        /**
         * Jellyfin returns GUIDs without dashes ("a1b2..."), but UUID.fromString() demands the
         * dashed form, so it has to be reinserted before parsing.
         */
        fun String.toDashedUuid(): String {
            if (length != 32) return this
            return buildString(36) {
                append(this@toDashedUuid, 0, 8).append('-')
                append(this@toDashedUuid, 8, 12).append('-')
                append(this@toDashedUuid, 12, 16).append('-')
                append(this@toDashedUuid, 16, 20).append('-')
                append(this@toDashedUuid, 20, 32)
            }
        }
    }
}
