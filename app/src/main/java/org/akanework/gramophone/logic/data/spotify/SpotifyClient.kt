package org.akanework.gramophone.logic.data.spotify

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Reads the user's own Spotify playlists through the official Web API.
 *
 * Only read scopes are requested, and nothing but playlist names and track titles ever leaves
 * Spotify - the tracks themselves are then matched against the user's Jellyfin library. Reading your
 * own library is what these endpoints are for, and the authorisation works on a free account.
 *
 * Authorisation is the Authorization Code flow with PKCE, which is the flow Spotify documents for
 * apps that cannot hold a client secret.
 */
class SpotifyClient(
    private val store: SpotifyCredentialStore,
    private val http: OkHttpClient = JellyfinClientHolder.apiHttpClient(),
) {

    class SpotifyException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Playlist(
        val id: String,
        val name: String,
        val owner: String?,
        val trackCount: Int,
        val imageUrl: String?,
    )

    /** A track as Spotify describes it. Matched against the local library by name. */
    data class Track(val title: String, val artist: String, val album: String?)

    /**
     * Builds the URL to send the user to, and stores the PKCE verifier the callback will need.
     */
    fun buildAuthorizationUrl(): String {
        val verifier = randomCodeVerifier()
        store.pendingCodeVerifier = verifier
        return AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", store.clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", SpotifyCredentialStore.REDIRECT_URI)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", codeChallengeFor(verifier))
            .addQueryParameter("scope", SCOPES)
            .build()
            .toString()
    }

    /**
     * Redeems the code returned to the redirect URI.
     *
     * [nowMillis] is passed in rather than read here so token expiry stays a function of its inputs.
     */
    suspend fun exchangeCode(context: Context, code: String, nowMillis: Long) {
        val verifier = store.pendingCodeVerifier
            ?: throw SpotifyException("This sign-in did not start on this device")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", SpotifyCredentialStore.REDIRECT_URI)
            .add("client_id", store.clientId)
            .add("code_verifier", verifier)
            .build()
        val json = post(TOKEN_URL, body)
        store.saveTokens(
            context,
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optLong("expires_in", 3600),
            nowMillis = nowMillis,
        )
    }

    /**
     * Returns a usable access token, refreshing first if the stored one has lapsed.
     *
     * Spotify access tokens last an hour, so anything that outlives one sitting has to refresh.
     */
    private suspend fun validAccessToken(context: Context, nowMillis: Long): String {
        val current = store.accessToken
        if (!current.isNullOrBlank() && nowMillis < store.expiresAt) return current
        val refresh = store.refreshToken
            ?: throw SpotifyException("Not connected to Spotify")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", store.clientId)
            .build()
        val json = post(TOKEN_URL, body)
        val token = json.optString("access_token")
        if (token.isBlank()) throw SpotifyException("Spotify did not return a token")
        store.saveTokens(
            context,
            accessToken = token,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optLong("expires_in", 3600),
            nowMillis = nowMillis,
        )
        return token
    }

    suspend fun currentUserName(context: Context, nowMillis: Long): String? {
        val json = get("$API_ROOT/me", validAccessToken(context, nowMillis))
        return json.optString("display_name").takeIf { it.isNotBlank() }
            ?: json.optString("id").takeIf { it.isNotBlank() }
    }

    /** Every playlist the user can see, following Spotify's paging to the end. */
    suspend fun playlists(context: Context, nowMillis: Long): List<Playlist> {
        val token = validAccessToken(context, nowMillis)
        val result = mutableListOf<Playlist>()
        var url: String? = "$API_ROOT/me/playlists?limit=50"
        while (url != null) {
            val json = get(url, token)
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                result += Playlist(
                    id = item.optString("id"),
                    name = item.optString("name").ifBlank { "Untitled" },
                    owner = item.optJSONObject("owner")?.optString("display_name"),
                    trackCount = item.optJSONObject("tracks")?.optInt("total") ?: 0,
                    imageUrl = item.optJSONArray("images")
                        ?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() },
                )
            }
            url = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return result
    }

    /**
     * The tracks of one playlist.
     *
     * `fields` trims the response to what matching needs. A playlist track object is otherwise
     * enormous, and on a large playlist most of it would be parsed and discarded.
     */
    suspend fun playlistTracks(
        context: Context,
        playlistId: String,
        nowMillis: Long,
    ): List<Track> {
        val token = validAccessToken(context, nowMillis)
        val result = mutableListOf<Track>()
        var url: String? = "$API_ROOT/playlists/$playlistId/tracks" +
                "?limit=100&fields=next,items(track(name,album(name),artists(name)))"
        while (url != null) {
            val json = get(url, token)
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                // Local files and removed tracks come back as a null track object.
                val track = items.optJSONObject(i)?.optJSONObject("track") ?: continue
                val title = track.optString("name").takeIf { it.isNotBlank() } ?: continue
                val artist = track.optJSONArray("artists")
                    ?.optJSONObject(0)?.optString("name").orEmpty()
                result += Track(
                    title = title,
                    artist = artist,
                    album = track.optJSONObject("album")?.optString("name"),
                )
            }
            url = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        return result
    }

    private suspend fun get(url: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        execute(
            Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        )
    }

    private suspend fun post(url: String, body: FormBody): JSONObject = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).post(body).build())
    }

    private fun execute(request: Request): JSONObject {
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw SpotifyException("Could not reach Spotify", e)
        }
        if (text.isBlank()) throw SpotifyException("Spotify returned an empty response")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw SpotifyException("Spotify returned an unreadable response", e)
        }
        if (code !in 200..299) {
            val message = json.optJSONObject("error")?.optString("message")
                ?: json.optString("error_description").takeIf { it.isNotBlank() }
                ?: "Spotify error $code"
            throw SpotifyException(message)
        }
        return json
    }

    /**
     * PKCE verifier: 64 random bytes, base64url encoded, which lands comfortably inside the
     * 43-128 character range the spec requires.
     */
    private fun randomCodeVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, BASE64_URL_FLAGS)
    }

    private fun codeChallengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, BASE64_URL_FLAGS)
    }

    companion object {
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API_ROOT = "https://api.spotify.com/v1"

        /** Read only. Accord never writes to the user's Spotify account. */
        private const val SCOPES = "playlist-read-private playlist-read-collaborative"

        private const val BASE64_URL_FLAGS =
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        /** Pulls the authorisation code (or error) out of the redirect. */
        fun codeFrom(uri: Uri): Result<String> {
            uri.getQueryParameter("error")?.let {
                return Result.failure(SpotifyException(it))
            }
            val code = uri.getQueryParameter("code")
                ?: return Result.failure(SpotifyException("Spotify did not return a code"))
            return Result.success(code)
        }
    }
}
