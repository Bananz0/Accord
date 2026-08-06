package org.akanework.gramophone.logic.data.requests

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.lidarr.LidarrRequester
import org.akanework.gramophone.logic.data.spotify.SpotifyClient
import org.akanework.gramophone.logic.data.spotify.SpotifyCredentialStore
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Turns a shared playlist link into a list of tracks to ask Lidarr for.
 *
 * The point is the gap between "someone sent me a playlist" and "I own these": paste the link, and
 * everything on it that is missing becomes a Lidarr request.
 *
 * Deezer needs no credentials - its playlist endpoint is public. Spotify does, and uses the sign-in
 * this app already has. Apple Music is recognised but cannot be resolved: its API requires a
 * developer token signed with a private key from a paid Apple Developer account, which is not
 * something an open-source client can ship.
 */
object PlaylistLinkResolver {

    sealed class Result {
        data class Resolved(val name: String, val tracks: List<LidarrRequester.Wanted>) : Result()
        data class Failed(val reason: String) : Result()
        /** Recognised provider, but this app cannot read it. */
        data class Unsupported(val provider: String, val reason: String) : Result()
        object NotALink : Result()
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolve(context: Context, url: String): Result {
        val trimmed = url.trim()
        return when {
            trimmed.contains("deezer.", ignoreCase = true) -> resolveDeezer(trimmed)
            trimmed.contains("open.spotify.com", ignoreCase = true) ||
                trimmed.startsWith("spotify:", ignoreCase = true) -> resolveSpotify(context, trimmed)
            trimmed.contains("music.apple.com", ignoreCase = true) -> Result.Unsupported(
                provider = "Apple Music",
                reason = "Apple's API needs a developer token signed with a paid account's private" +
                    " key, so a playlist cannot be read without one."
            )
            else -> Result.NotALink
        }
    }

    /** `https://www.deezer.com/…/playlist/1234567` - the id is the trailing number. */
    private fun resolveDeezer(url: String): Result {
        val id = Regex("playlist/(\\d+)").find(url)?.groupValues?.get(1)
            ?: return Result.Failed("That Deezer link does not point at a playlist.")
        return try {
            val body = http.newCall(
                Request.Builder().url("https://api.deezer.com/playlist/$id").build()
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.Failed("Deezer returned ${response.code}.")
                }
                response.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            if (json.has("error")) {
                return Result.Failed("Deezer could not open that playlist - is it public?")
            }
            val items = json.optJSONObject("tracks")?.optJSONArray("data")
                ?: return Result.Failed("That playlist has no tracks.")
            val tracks = (0 until items.length()).mapNotNull { index ->
                val track = items.optJSONObject(index) ?: return@mapNotNull null
                val title = track.optString("title").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val artist = track.optJSONObject("artist")?.optString("name").orEmpty()
                val album = track.optJSONObject("album")?.optString("title")
                LidarrRequester.Wanted(artist, album, title)
            }
            Result.Resolved(json.optString("title", "Deezer playlist"), tracks)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not reach Deezer.")
        }
    }

    /** `https://open.spotify.com/playlist/<id>` or `spotify:playlist:<id>`. */
    private suspend fun resolveSpotify(context: Context, url: String): Result {
        val id = Regex("playlist[:/]([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: return Result.Failed("That Spotify link does not point at a playlist.")
        val store = SpotifyCredentialStore(context)
        if (!store.hasClientId()) {
            return Result.Failed("Add a Spotify client id in Settings first.")
        }
        if (!store.isLinked()) {
            return Result.Failed("Sign in to Spotify in Settings first.")
        }
        return try {
            val tracks = SpotifyClient(store).playlistTracks(
                context, id, System.currentTimeMillis()
            ).map { LidarrRequester.Wanted(it.artist, it.album, it.title) }
            Result.Resolved("Spotify playlist", tracks)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Could not read that Spotify playlist.")
        }
    }
}
