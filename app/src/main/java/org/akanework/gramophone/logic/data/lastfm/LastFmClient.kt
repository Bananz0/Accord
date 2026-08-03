package org.akanework.gramophone.logic.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A minimal Last.fm 2.0 API client covering what a music player needs: linking an account,
 * announcing the current track, and submitting scrobbles.
 *
 * Hand-rolled rather than pulled from a library because the surface is three endpoints and one
 * signing rule, and every maintained JVM Last.fm wrapper is either abandoned or drags in a second
 * HTTP stack. It reuses the app's shared OkHttp connection pool for the same reason the Jellyfin SDK
 * does - a separate pool would mean separate TLS handshakes.
 *
 * Every call is a suspending function that does its network work on [Dispatchers.IO] and throws
 * [LastFmException] with the server's own message on failure, so callers can show something better
 * than "something went wrong".
 */
class LastFmClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val http: OkHttpClient = JellyfinClientHolder.mediaHttpClient(),
) {

    /**
     * Exchanges a username and password for a permanent session key.
     *
     * This is the "mobile" auth flow, which exists precisely so an app can ask for credentials
     * directly instead of bouncing the user through a browser. Last.fm requires it over HTTPS, and
     * the password is never stored - only the returned session key is.
     */
    suspend fun getMobileSession(username: String, password: String): Session {
        val response = post(
            mapOf(
                "method" to "auth.getMobileSession",
                "username" to username,
                "password" to password,
            )
        )
        val session = response.optJSONObject("session")
            ?: throw LastFmException("Last.fm did not return a session")
        return Session(
            name = session.optString("name", username),
            key = session.optString("key"),
        )
    }

    /**
     * Tells Last.fm what is playing right now. Purely cosmetic - it drives the "now scrobbling"
     * badge on the profile and expires on its own, so failures here are not worth queueing.
     */
    suspend fun updateNowPlaying(sessionKey: String, track: Track) {
        post(
            buildMap {
                put("method", "track.updateNowPlaying")
                put("sk", sessionKey)
                putAll(track.toParams())
            }
        )
    }

    /**
     * Submits up to [MAX_BATCH] scrobbles in one call.
     *
     * Batching matters for the offline queue: a phone that spent a train journey without signal can
     * come back with dozens of plays, and one request per play would be both slow and a good way to
     * get rate-limited.
     *
     * Returns the number Last.fm accepted. A non-zero "ignored" count is not an error - it usually
     * means the metadata was too sparse for Last.fm to match, and retrying would never help.
     */
    suspend fun scrobble(sessionKey: String, entries: List<TimedTrack>): Int {
        require(entries.size <= MAX_BATCH) { "Last.fm accepts at most $MAX_BATCH scrobbles per call" }
        if (entries.isEmpty()) return 0
        val params = buildMap {
            put("method", "track.scrobble")
            put("sk", sessionKey)
            entries.forEachIndexed { index, entry ->
                entry.track.toParams().forEach { (name, value) -> put("$name[$index]", value) }
                put("timestamp[$index]", entry.timestampSeconds.toString())
            }
        }
        val response = post(params)
        val accepted = response.optJSONObject("scrobbles")
            ?.optJSONObject("@attr")
            ?.optInt("accepted")
            ?: entries.size
        val ignored = response.optJSONObject("scrobbles")
            ?.optJSONObject("@attr")
            ?.optInt("ignored")
            ?: 0
        if (ignored > 0) {
            Log.w(TAG, "Last.fm ignored $ignored of ${entries.size} scrobbles")
        }
        return accepted
    }

    /**
     * Artists Last.fm considers similar to [artist].
     *
     * Read-only, so it needs the API key but no session - recommendations work before the user has
     * linked an account.
     */
    suspend fun getSimilarArtists(artist: String, limit: Int = 30): List<String> {
        val response = get(
            mapOf(
                "method" to "artist.getSimilar",
                "artist" to artist,
                "limit" to limit.toString(),
                "autocorrect" to "1",
            )
        )
        val array = response.optJSONObject("similarartists")?.optJSONArray("artist")
            ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private suspend fun get(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = API_ROOT.toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
            addQueryParameter("api_key", apiKey)
            addQueryParameter("format", "json")
        }.build()
        execute(Request.Builder().url(url).build())
    }

    private suspend fun post(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val signed = params + ("api_key" to apiKey)
        val body = FormBody.Builder().apply {
            signed.forEach { (name, value) -> add(name, value) }
            add("api_sig", sign(signed))
            // Deliberately added after signing: "format" is the one parameter Last.fm excludes from
            // the signature, and including it produces a silent "Invalid method signature" instead.
            add("format", "json")
        }.build()
        execute(Request.Builder().url(API_ROOT).post(body).build())
    }

    private fun execute(request: Request): JSONObject {
        val text = try {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw LastFmException("Could not reach Last.fm", e)
        }
        if (text.isBlank()) throw LastFmException("Last.fm returned an empty response")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw LastFmException("Last.fm returned an unreadable response", e)
        }
        if (json.has("error")) {
            // Last.fm answers errors with HTTP 200 and an error code in the body, so the status line
            // says nothing useful and the payload has to be checked on every call.
            throw LastFmException(
                json.optString("message").ifBlank { "Last.fm error ${json.optInt("error")}" },
                code = json.optInt("error"),
            )
        }
        return json
    }

    /**
     * Last.fm's signature: every parameter except `format` and `callback`, sorted by name, joined as
     * name+value with no separators, the shared secret appended, then MD5.
     */
    private fun sign(params: Map<String, String>): String {
        val payload = buildString {
            params.toSortedMap().forEach { (name, value) -> append(name).append(value) }
            append(apiSecret)
        }
        val digest = MessageDigest.getInstance("MD5").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class Session(val name: String, val key: String)

    /**
     * A track as Last.fm wants to hear about it. [artist] and [title] are the only fields it can
     * match on; everything else improves the match but may be omitted.
     */
    data class Track(
        val artist: String,
        val title: String,
        val album: String? = null,
        val albumArtist: String? = null,
        val durationSeconds: Int? = null,
        val trackNumber: Int? = null,
    ) {
        fun toParams(): Map<String, String> = buildMap {
            put("artist", artist)
            put("track", title)
            album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            // Sending an album artist identical to the track artist is noise; Last.fm infers it.
            albumArtist?.takeIf { it.isNotBlank() && it != artist }?.let { put("albumArtist", it) }
            durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
            trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it.toString()) }
        }
    }

    /** A track plus the moment playback started, in Unix seconds, as scrobbling requires. */
    data class TimedTrack(val track: Track, val timestampSeconds: Long)

    class LastFmException(
        message: String,
        cause: Throwable? = null,
        val code: Int = 0,
    ) : Exception(message, cause) {
        /**
         * Whether retrying later could plausibly succeed. Authentication and validation failures
         * never fix themselves, so queued scrobbles that hit them are dropped rather than retried
         * forever.
         */
        val isTransient: Boolean
            get() = code == 0 || code == 11 || code == 16 || code == 29
    }

    companion object {
        private const val TAG = "LastFmClient"
        private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"

        /** Last.fm's documented per-request cap for track.scrobble. */
        const val MAX_BATCH = 50
    }
}
