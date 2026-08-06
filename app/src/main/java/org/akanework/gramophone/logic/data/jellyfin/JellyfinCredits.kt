package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.undashed
import org.json.JSONObject
import java.util.Locale

/**
 * The credits behind a track, read off the Jellyfin server.
 *
 * The build this UI came from sources credits from a commercial catalogue, which this fork
 * deliberately does not do. Everything shown here is what the server already knows about the file:
 * who is credited on it, who released it, and what the audio actually is. The last of those is the
 * part no other screen exposes - the player's badge says "Hi-Res Lossless" but never the numbers
 * behind it.
 */
object JellyfinCredits {

    /** One credited person or organisation. */
    data class Credit(
        val name: String,
        /** What they did - "Composer", "Performer". Null when the server did not say. */
        val role: String?,
        /** Their artist image, when the server has one, for the row thumbnail. */
        val imageUrl: String?,
    )

    data class Section(val title: String, val credits: List<Credit>)

    data class Result(val sections: List<Section>)

    /**
     * Loads the credits for [mediaId], or null when signed out, offline, or the item is a local
     * file rather than a server one.
     */
    @WorkerThread
    fun load(context: Context, mediaId: String?): Result? {
        val credentials = JellyfinClientHolder.credentials
        val server = credentials.serverUrl?.trimEnd('/')
        val token = credentials.accessToken
        val userId = credentials.userId
        if (server == null || token == null || userId == null) {
            Log.w(TAG, "Not signed in; no credits for $mediaId")
            return null
        }
        val remoteId = JellyfinItemResolver.remoteIdForMediaId(context, mediaId)
        if (remoteId == null) {
            Log.w(TAG, "No server id for mediaId $mediaId")
            return null
        }

        val request = Request.Builder()
            .url("$server/Users/${userId.undashed()}/Items/${remoteId.undashed()}")
            .header("Authorization", "MediaBrowser Token=\"$token\"")
            .build()
        val body = runCatching {
            CLIENT.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else {
                    Log.w(TAG, "Credits for $remoteId rejected with HTTP ${response.code}")
                    null
                }
            }
        }.getOrElse {
            Log.w(TAG, "Credits for $remoteId failed: $it")
            null
        } ?: return null

        return runCatching { parse(JSONObject(body), server, token) }.getOrElse {
            Log.w(TAG, "Could not parse credits for $remoteId: $it")
            null
        }
    }

    private fun parse(item: JSONObject, server: String, token: String): Result {
        val sections = mutableListOf<Section>()

        // People arrive with a Type ("Composer", "Artist") and sometimes a free-text Role. The same
        // person is listed once per type, so "Bon Jovi - AlbumArtist" and "Bon Jovi - Artist" are two
        // entries for what a reader would call one credit; they are folded, keeping every distinct
        // role.
        val people = LinkedHashMap<String, MutableSet<String>>()
        val images = LinkedHashMap<String, String?>()
        item.optJSONArray("People")?.let { array ->
            for (index in 0 until array.length()) {
                val person = array.optJSONObject(index) ?: continue
                val name = person.optString("Name").ifEmpty { continue }
                val role = person.optString("Role").ifEmpty { person.optString("Type") }
                people.getOrPut(name) { linkedSetOf() }.apply { if (role.isNotEmpty()) add(role.humanised()) }
                images.getOrPut(name) {
                    person.optString("Id").takeIf { it.isNotEmpty() }?.let { id ->
                        "$server/Items/$id/Images/Primary?maxWidth=$THUMBNAIL_MAX_WIDTH&api_key=$token"
                    }
                }
            }
        }
        if (people.isNotEmpty()) {
            sections += Section(
                SECTION_PERFORMANCE,
                people.map { (name, roles) ->
                    Credit(name, roles.joinToString(", ").ifEmpty { null }, images[name])
                }
            )
        }

        val studios = item.optJSONArray("Studios")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("Name")?.takeIf { it.isNotEmpty() }
            }
        }.orEmpty()
        if (studios.isNotEmpty()) {
            sections += Section(SECTION_RELEASE, studios.map { Credit(it, null, null) })
        }

        // The technical row. Built from the first audio stream, which is the one that plays.
        val audioStream = item.optJSONArray("MediaSources")
            ?.optJSONObject(0)
            ?.optJSONArray("MediaStreams")
            ?.let { streams ->
                (0 until streams.length())
                    .mapNotNull { streams.optJSONObject(it) }
                    .firstOrNull { it.optString("Type") == "Audio" }
            }
        val technical = mutableListOf<Credit>()
        audioStream?.let { stream ->
            stream.optString("Codec").takeIf { it.isNotEmpty() }?.let {
                technical += Credit(it.uppercase(Locale.ROOT), LABEL_FORMAT, null)
            }
            val sampleRate = stream.optInt("SampleRate", 0)
            val bitDepth = stream.optInt("BitDepth", 0)
            if (sampleRate > 0) {
                val khz = "%.1f".format(sampleRate / 1000f).removeSuffix(".0")
                technical += Credit(
                    if (bitDepth > 0) "$bitDepth-bit / $khz kHz" else "$khz kHz",
                    LABEL_QUALITY,
                    null
                )
            }
            stream.optInt("BitRate", 0).takeIf { it > 0 }?.let {
                technical += Credit("${it / 1000} kbps", LABEL_BITRATE, null)
            }
            stream.optString("ChannelLayout").takeIf { it.isNotEmpty() }?.let {
                technical += Credit(it.humanised(), LABEL_CHANNELS, null)
            }
        }
        item.optString("Container").takeIf { it.isNotEmpty() }?.let {
            technical += Credit(it.uppercase(Locale.ROOT), LABEL_CONTAINER, null)
        }
        if (technical.isNotEmpty()) sections += Section(SECTION_AUDIO, technical)

        return Result(sections)
    }

    /** "AlbumArtist" reads badly in a list; "Album Artist" is the same fact, spelled for a person. */
    private fun String.humanised(): String =
        replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            .replaceFirstChar { it.uppercase(Locale.ROOT) }

    private const val TAG = "JellyfinCredits"
    private const val THUMBNAIL_MAX_WIDTH = 128

    const val SECTION_PERFORMANCE = "Performance"
    const val SECTION_RELEASE = "Release"
    const val SECTION_AUDIO = "Audio"
    private const val LABEL_FORMAT = "Format"
    private const val LABEL_QUALITY = "Quality"
    private const val LABEL_BITRATE = "Bit rate"
    private const val LABEL_CHANNELS = "Channels"
    private const val LABEL_CONTAINER = "Container"

    /**
     * The shared client, not one of our own.
     *
     * Every separately built client brings its own connection pool, so a call here would open a new
     * connection to a server the app already has one to. Going through the shared pool reuses it,
     * and inherits the timeouts [JellyfinClientHolder.apiHttpClient] sets for small API calls.
     */
    private val CLIENT: OkHttpClient get() = JellyfinClientHolder.apiHttpClient()
}
