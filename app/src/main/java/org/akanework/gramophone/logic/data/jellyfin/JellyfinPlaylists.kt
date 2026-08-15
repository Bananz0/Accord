package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.undashed
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Playlists that live on the Jellyfin server.
 *
 * The app's own library has no writable playlist store - every song in it belongs to the server -
 * so "add to a playlist" has to mean the server's playlists, or it means nothing the user will see
 * again from any other client.
 *
 * Written against the HTTP API rather than the SDK for the same reason [JellyfinReporter.setFavourite]
 * is: the bundled SDK is older than this server, and where its route and the server's disagree the
 * call still answers 200 while writing nothing. These three routes were checked against the live
 * 12.0.0 server - create returns the new playlist's id, add returns 204, and both read back.
 */
object JellyfinPlaylists {

    /** One of the server's playlists, as much of it as a picker needs. */
    data class RemotePlaylist(
        val id: String,
        val name: String,
        val songCount: Int,
        /** Null when the playlist has no artwork of its own, which is the case until it has items. */
        val imageUrl: String?,
    )

    /**
     * Every audio playlist the signed-in user can see, newest last.
     *
     * Returns empty rather than throwing when signed out or unreachable: the picker still has to
     * open and offer "New Playlist", which is the more useful half of it anyway.
     */
    @WorkerThread
    fun list(context: Context? = null): List<RemotePlaylist> {
        val session = session() ?: return emptyList()
        val url = "${session.server}/Items" +
                "?userId=${session.userId.undashed()}" +
                "&includeItemTypes=Playlist" +
                "&recursive=true" +
                "&fields=ChildCount" +
                "&sortBy=SortName" +
                "&limit=10000"
        val body = get(url, session) ?: return emptyList()
        return runCatching {
            val items = JSONObject(body).optJSONArray("Items") ?: return emptyList()
            (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                // Empty and older playlists frequently report MediaType as "Unknown". They are
                // still real playlists and must not disappear from the browser because of that.
                val id = item.optString("Id").ifEmpty { return@mapNotNull null }
                RemotePlaylist(
                    id = id,
                    name = item.optString("Name").ifEmpty { return@mapNotNull null },
                    songCount = item.optInt("ChildCount", 0),
                    imageUrl = item.optJSONObject("ImageTags")?.optString("Primary")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { tag ->
                            "${session.server}/Items/$id/Images/Primary" +
                                    "?tag=$tag&maxWidth=$COVER_MAX_WIDTH&quality=90" +
                                    "&api_key=${session.token.encoded()}"
                        },
                )
            }.also { playlists -> context?.let { cacheList(it, playlists) } }
        }.getOrElse {
            Log.w(TAG, "Could not parse playlist list", it)
            emptyList()
        }
    }

    /**
     * Creates a playlist holding [mediaIds], and returns its id.
     *
     * The songs go in on creation rather than in a second call - the server accepts them there, and
     * a playlist that briefly exists empty is a playlist that shows up wrong if the second call
     * fails.
     */
    @WorkerThread
    fun create(context: Context, name: String, mediaIds: List<String>): String? {
        val session = session() ?: return null
        val remoteIds = mediaIds.mapNotNull { JellyfinItemResolver.remoteIdForMediaId(context, it) }
        if (remoteIds.isEmpty()) {
            Log.w(TAG, "Not creating \"$name\": none of ${mediaIds.size} ids resolved")
            return null
        }
        val payload = JSONObject()
            .put("Name", name)
            .put("Ids", JSONArray(remoteIds))
            .put("UserId", session.userId.undashed())
            .put("MediaType", "Audio")
            .toString()
        val request = Request.Builder()
            .url("${session.server}/Playlists")
            .header("Authorization", session.authHeader)
            .post(payload.toRequestBody(JSON))
            .build()
        return runCatching {
            CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Creating \"$name\" rejected with HTTP ${response.code}")
                    return null
                }
                val id = JSONObject(response.body?.string().orEmpty()).optString("Id")
                    .takeIf { it.isNotEmpty() }
                    ?: return null
                if (verifyCreatedPlaylist(session, id, remoteIds.distinct().size)) {
                    Log.i(TAG, "Created and verified \"$name\" with ${remoteIds.distinct().size} items")
                    id
                } else {
                    Log.w(TAG, "Created \"$name\" as $id, but Jellyfin read-back did not contain its items")
                    null
                }
            }
        }.getOrElse {
            Log.w(TAG, "Creating \"$name\" failed", it)
            null
        }
    }

    /** A successful create response is not enough: only report success once Jellyfin reads it back. */
    private fun verifyCreatedPlaylist(session: Session, playlistId: String, expectedCount: Int): Boolean {
        val url = "${session.server}/Playlists/${playlistId.undashed()}/Items" +
            "?userId=${session.userId.undashed()}"
        repeat(3) { attempt ->
            val body = get(url, session)
            val count = body?.let { response ->
                runCatching {
                    val json = JSONObject(response)
                    json.optInt("TotalRecordCount", json.optJSONArray("Items")?.length() ?: 0)
                }.getOrNull()
            }
            if (count != null && count >= expectedCount) return true
            if (attempt < 2) Thread.sleep(200L)
        }
        return false
    }

    /** Appends [mediaIds] to an existing playlist. Returns whether the server took them. */
    @WorkerThread
    fun addTo(context: Context, playlistId: String, mediaIds: List<String>): Boolean {
        val session = session() ?: return false
        val remoteIds = mediaIds.mapNotNull { JellyfinItemResolver.remoteIdForMediaId(context, it) }
        if (remoteIds.isEmpty()) return false
        val request = Request.Builder()
            .url(
                "${session.server}/Playlists/${playlistId.undashed()}/Items" +
                        "?ids=${remoteIds.joinToString(",") { it.encoded() }}" +
                        "&userId=${session.userId.undashed()}"
            )
            .header("Authorization", session.authHeader)
            .post(EMPTY_BODY)
            .build()
        return runCatching {
            CLIENT.newCall(request).execute().use { response ->
                response.isSuccessful.also {
                    if (!it) Log.w(TAG, "Add to $playlistId rejected with HTTP ${response.code}")
                }
            }
        }.getOrElse {
            Log.w(TAG, "Add to $playlistId failed", it)
            false
        }
    }

    /**
     * Replaces an imported playlist after the user explicitly chose exact mirroring.
     *
     * Create and verify the corrected copy before deleting the stale one. If removing the old copy
     * fails, roll the new copy back so a failed replacement never leaves two identically named
     * playlists behind.
     */
    @WorkerThread
    fun replace(
        context: Context,
        playlistId: String,
        name: String,
        mediaIds: List<String>,
    ): String? {
        val replacementId = create(context, name, mediaIds) ?: return null
        if (delete(playlistId)) return replacementId
        Log.w(TAG, "Could not remove stale playlist $playlistId; rolling back $replacementId")
        delete(replacementId)
        return null
    }

    /** Deletes a playlist from Jellyfin. The tracks themselves are never deleted. */
    @WorkerThread
    fun delete(playlistId: String): Boolean {
        val session = session() ?: return false
        val request = Request.Builder()
            .url("${session.server}/Items/${playlistId.undashed()}")
            .header("Authorization", session.authHeader)
            .delete()
            .build()
        return runCatching {
            CLIENT.newCall(request).execute().use { response ->
                response.isSuccessful.also { deleted ->
                    if (deleted) Log.i(TAG, "Deleted playlist ${playlistId.undashed()}")
                    else Log.w(TAG, "Delete rejected with HTTP ${response.code}")
                }
            }
        }.getOrElse {
            Log.w(TAG, "Deleting playlist failed", it)
            false
        }
    }

    /** Last successful server list, used immediately while a fresh request is in flight. */
    fun cachedList(context: Context): List<RemotePlaylist> {
        val raw = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                RemotePlaylist(
                    id = item.optString("id").ifBlank { return@mapNotNull null },
                    name = item.optString("name").ifBlank { return@mapNotNull null },
                    songCount = item.optInt("songCount", 0),
                    imageUrl = null,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun cacheList(context: Context, playlists: List<RemotePlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("songCount", playlist.songCount)
            )
        }
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CACHE_KEY, array.toString())
            .apply()
    }

    /** Resolves a server playlist, in server order, onto the already loaded playable library. */
    @WorkerThread
    fun items(
        context: Context,
        playlistId: String,
        library: List<MediaItem>,
    ): List<MediaItem> {
        val session = session() ?: return emptyList()
        val url = "${session.server}/Playlists/${playlistId.undashed()}/Items" +
            "?userId=${session.userId.undashed()}&limit=10000"
        val body = get(url, session) ?: return emptyList()
        val remoteIds = runCatching {
            val array = JSONObject(body).optJSONArray("Items") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("Id")?.takeIf { it.isNotBlank() }
            }
        }.getOrElse {
            Log.w(TAG, "Could not parse items for playlist $playlistId", it)
            return emptyList()
        }
        val remoteToLocal = org.akanework.gramophone.logic.data.db.AppDatabase
            .getInstance(context)
            .jellyfinIdDao()
            .getAll()
            .associate { it.jellyfinId.normalizedId() to it.localId }
        val byLocalId = library.associateBy { it.mediaId.toLongOrNull() }
        return remoteIds.mapNotNull { id ->
            remoteToLocal[id.normalizedId()]?.let(byLocalId::get)
        }
    }

    private fun get(url: String, session: Session): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", session.authHeader)
            .build()
        return runCatching {
            CLIENT.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    private class Session(val server: String, val token: String, val userId: String) {
        val authHeader get() = "MediaBrowser Token=\"$token\""
    }

    private fun session(): Session? {
        val credentials = JellyfinClientHolder.credentials
        val server = credentials.serverUrl?.trimEnd('/') ?: return null
        val token = credentials.accessToken ?: return null
        val userId = credentials.userId ?: return null
        return Session(server, token, userId)
    }

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.normalizedId(): String = replace("-", "").lowercase()

    private const val TAG = "JellyfinPlaylists"
    private const val COVER_MAX_WIDTH = 512
    private const val CACHE_PREFS = "jellyfin_playlist_cache"
    private const val CACHE_KEY = "playlists"
    private val JSON = "application/json".toMediaType()
    private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

    /**
     * The shared client, not one of our own.
     *
     * Every separately built client brings its own connection pool, so a call here would open a new
     * connection to a server the app already has one to. Going through the shared pool reuses it,
     * and inherits the timeouts [JellyfinClientHolder.apiHttpClient] sets for small API calls.
     */
    private val CLIENT: OkHttpClient get() = JellyfinClientHolder.apiHttpClient()
}
