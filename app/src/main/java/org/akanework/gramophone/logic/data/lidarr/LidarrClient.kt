package org.akanework.gramophone.logic.data.lidarr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the user's Lidarr instance.
 *
 * This is the bridge between "a playlist mentions a song I do not own" and "that song is in my
 * Jellyfin library": Lidarr looks the album up, grabs it from the user's own indexers, and imports
 * it into the music folder Jellyfin already watches.
 *
 * Only lookup and add are implemented. Deleting or re-downloading is destructive and belongs in
 * Lidarr's own interface, not behind a menu item in a music player.
 */
class LidarrClient(
    private val store: LidarrCredentialStore,
    private val http: OkHttpClient = JellyfinClientHolder.apiHttpClient(),
) {

    class LidarrException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** An album as Lidarr's metadata source describes it, before it exists locally. */
    data class AlbumResult(
        val foreignAlbumId: String,
        val title: String,
        val artistName: String,
        val foreignArtistId: String,
        val year: Int?,
        val coverUrl: String?,
        /** True when Lidarr already tracks this album, so requesting it again is pointless. */
        val alreadyAdded: Boolean,
        /** Complete resource returned by Lidarr; POST /album requires its metadata and images. */
        val lidarrJson: String,
    )

    data class RootFolder(val path: String, val freeSpaceBytes: Long?)
    data class Profile(val id: Int, val name: String)

    /** Verifies the address and key, returning the instance version. */
    suspend fun testConnection(): String {
        val json = get("/api/v1/system/status")
        return json.optString("version").ifBlank { "unknown" }
    }

    suspend fun rootFolders(): List<RootFolder> =
        getArray("/api/v1/rootfolder").map { item ->
            RootFolder(
                path = item.optString("path"),
                freeSpaceBytes = item.optLong("freeSpace").takeIf { it > 0 },
            )
        }

    suspend fun qualityProfiles(): List<Profile> =
        getArray("/api/v1/qualityprofile").map {
            Profile(it.optInt("id"), it.optString("name"))
        }

    suspend fun metadataProfiles(): List<Profile> =
        getArray("/api/v1/metadataprofile").map {
            Profile(it.optInt("id"), it.optString("name"))
        }

    /** Searches Lidarr's unified artist/album index and returns the album rows in useful order. */
    suspend fun searchAlbums(term: String): List<AlbumResult> {
        if (term.isBlank()) return emptyList()
        val searchRows = getArray("/api/v1/search", mapOf("term" to term))
        val albums = searchRows.mapNotNull { it.optJSONObject("album") }.toMutableList()

        // A bare artist search otherwise returns only the handful of albums that happen to rank in
        // the server's mixed top 20. If the exact artist is already known locally, include their
        // complete album list before ranking it against the query.
        val needle = term.normaliseForMatch()
        val exactArtistId = searchRows.asSequence()
            .mapNotNull { it.optJSONObject("artist") }
            .firstOrNull { it.optString("artistName").normaliseForMatch() == needle }
            ?.optInt("id", 0)
            ?.takeIf { it > 0 }
        if (exactArtistId != null) {
            albums += getArray("/api/v1/album", mapOf("artistId" to exactArtistId.toString()))
        }

        return rankAlbums(
            term,
            albums.mapNotNull(::albumResult).distinctBy(AlbumResult::foreignAlbumId),
        )
    }

    private fun albumResult(item: JSONObject): AlbumResult? {
        val foreignAlbumId = item.optString("foreignAlbumId").takeIf { it.isNotBlank() }
            ?: return null
        val artist = item.optJSONObject("artist")
        return AlbumResult(
            foreignAlbumId = foreignAlbumId,
            title = item.optString("title").ifBlank { "Untitled" },
            artistName = artist?.optString("artistName").orEmpty(),
            foreignArtistId = artist?.optString("foreignArtistId").orEmpty(),
            year = item.optString("releaseDate").take(4).toIntOrNull(),
            coverUrl = item.optJSONArray("images")?.let { images ->
                (0 until images.length())
                    .mapNotNull { images.optJSONObject(it)?.optString("remoteUrl") }
                    .firstOrNull { it.isNotBlank() }
            },
            // Lidarr gives an album an internal id once it is tracked; zero means it is not.
            alreadyAdded = item.optInt("id", 0) > 0,
            lidarrJson = item.toString(),
        )
    }

    /**
     * Adds an album and asks Lidarr to start searching for it.
     *
     * The artist has to be included even when adding a single album: Lidarr's model hangs albums off
     * artists, and an artist it does not track yet has to be created in the same call. `monitor:
     * specificAlbum` keeps it to the one album rather than pulling in the whole discography, which
     * is what a request from a playlist means.
     */
    suspend fun addAlbum(album: AlbumResult): Boolean {
        if (album.alreadyAdded) return true
        val rootFolder = store.rootFolderPath
            ?: throw LidarrException("No root folder chosen")
        val payload = JSONObject(album.lidarrJson).apply {
            // A lookup result can carry a zero placeholder, but POST treats a real id as an update.
            remove("id")
            put("monitored", true)
            put("addOptions", JSONObject().put("searchForNewAlbum", true))
            val artist = optJSONObject("artist") ?: JSONObject().apply {
                put("foreignArtistId", album.foreignArtistId)
            }
            put("artist", artist.apply {
                put("qualityProfileId", store.qualityProfileId)
                put("metadataProfileId", store.metadataProfileId)
                put("rootFolderPath", rootFolder)
                put("monitored", true)
                // Do not start monitoring everything this artist releases from now on; the user
                // asked for one album.
                put("monitorNewItems", "none")
                put("addOptions", JSONObject().apply {
                    // "none" rather than anything more specific: Lidarr's MonitorTypes has no
                    // per-album value (that is Sonarr's vocabulary, and sending it fails
                    // validation), and it ignores this field entirely when albumsToMonitor is
                    // populated - which is what actually limits the request to one album.
                    put("monitor", "none")
                    put("albumsToMonitor", JSONArray().put(album.foreignAlbumId))
                    put("searchForMissingAlbums", false)
                })
            })
        }
        return try {
            post("/api/v1/album", payload)
            true
        } catch (e: LidarrException) {
            // Lidarr answers 400 with a validation message when the album is already tracked. That
            // is the desired end state, not a failure worth showing the user.
            if (e.message?.contains("already", ignoreCase = true) == true) {
                Log.d(TAG, "Album ${album.title} already tracked by Lidarr")
                true
            } else {
                throw e
            }
        }
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(executeRaw(buildRequest(path, query)))
        }

    private suspend fun getArray(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val array = JSONArray(executeRaw(buildRequest(path, query)))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private suspend fun post(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val request = buildRequest(path).newBuilder()
            .post(body.toString().toRequestBody(JSON))
            .build()
        executeRaw(request)
    }

    private fun buildRequest(path: String, query: Map<String, String> = emptyMap()): Request {
        val base = store.serverUrl?.takeIf { it.isNotBlank() }
            ?: throw LidarrException("Lidarr address is not set")
        val key = store.apiKey?.takeIf { it.isNotBlank() }
            ?: throw LidarrException("Lidarr API key is not set")
        val url = StringBuilder(base).append(path)
        query.entries.forEachIndexed { index, (name, value) ->
            url.append(if (index == 0) '?' else '&')
                .append(name).append('=')
                .append(java.net.URLEncoder.encode(value, "UTF-8"))
        }
        return Request.Builder()
            .url(url.toString())
            // Lidarr accepts the key as a header or a query parameter; the header keeps it out of
            // its own request log.
            .header("X-Api-Key", key)
            .build()
    }

    private fun executeRaw(request: Request): String {
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw LidarrException("Could not reach Lidarr", e)
        }
        if (code !in 200..299) {
            throw LidarrException(describeError(code, text))
        }
        return text
    }

    /**
     * Lidarr reports validation failures as an array of {errorMessage} objects and auth failures as
     * a bare status, so the useful part has to be dug out rather than shown raw.
     */
    private fun describeError(code: Int, body: String): String {
        if (code == 401) return "Lidarr rejected the API key"
        val fromArray = runCatching {
            val array = JSONArray(body)
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.optString("errorMessage") }
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
        val fromObject = runCatching {
            JSONObject(body).optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        // Schema failures come back in a third shape - {"errors": {"$.field": ["why"]}} - which the
        // two above miss entirely, leaving a bare "HTTP 400" that says nothing about what was wrong.
        val fromValidation = runCatching {
            val errors = JSONObject(body).optJSONObject("errors") ?: return@runCatching null
            errors.keys().asSequence().firstNotNullOfOrNull { field ->
                errors.optJSONArray(field)?.optString(0)?.takeIf { it.isNotBlank() }
                    ?.let { "$field: $it" }
            }
        }.getOrNull()
        return fromArray ?: fromObject ?: fromValidation ?: "Lidarr returned HTTP $code"
    }

    companion object {
        private const val TAG = "LidarrClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun rankAlbums(term: String, albums: List<AlbumResult>): List<AlbumResult> {
            val needle = term.normaliseForMatch()
            val familyNeedle = term.editionFamilyForMatch()
            val terms = term.split(NON_WORD)
                .map { it.normaliseForMatch() }
                .filter(String::isNotBlank)
            return albums.sortedWith(compareBy<AlbumResult> { album ->
                val title = album.title.normaliseForMatch()
                val artist = album.artistName.normaliseForMatch()
                val combined = "$artist$title"
                val familyTitle = album.title.editionFamilyForMatch()
                val familyCombined = "$artist$familyTitle"
                when {
                    needle == combined || needle == "$title$artist" ||
                        familyNeedle == familyCombined || familyNeedle == "$familyTitle$artist" -> 0
                    needle == artist -> 1
                    needle == title -> 2
                    title.startsWith(needle) -> 3
                    artist.startsWith(needle) -> 4
                    title.contains(needle) -> 5
                    artist.contains(needle) -> 6
                    terms.isNotEmpty() && terms.all(combined::contains) -> 7
                    else -> 8
                }
            }.thenBy { if (it.title.contains(PREFERRED_EDITION)) 0 else 1 }
                .thenByDescending(AlbumResult::year))
        }

        internal fun String.normaliseForMatch(): String = lowercase().filter(Char::isLetterOrDigit)

        private fun String.editionFamilyForMatch(): String =
            replace(EDITION_MARKER, "").normaliseForMatch()

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val EDITION_MARKER = Regex(
            """\b(deluxe|expanded|anniversary|special|complete|bonus(?:\s+track)?|platinum)\s*(edition|version)?\b""",
            RegexOption.IGNORE_CASE,
        )
        private val PREFERRED_EDITION = Regex(
            """\b(deluxe|expanded|anniversary|special edition|complete edition|bonus track|platinum)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
