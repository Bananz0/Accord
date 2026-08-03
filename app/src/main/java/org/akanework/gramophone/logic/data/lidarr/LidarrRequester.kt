package org.akanework.gramophone.logic.data.lidarr

import android.content.Context
import android.util.Log

/**
 * Turns "tracks I do not own" into Lidarr requests.
 *
 * Lidarr works in albums, not tracks - it grabs releases, not individual songs - so a list of
 * missing tracks collapses to the set of albums they came from. Twenty missing tracks off the same
 * record is one request, which is also what the user would have done by hand.
 */
object LidarrRequester {

    private const val TAG = "LidarrRequester"

    /** A track we want but do not have, in the only terms Lidarr can search on. */
    data class Wanted(val artist: String, val album: String?, val title: String)

    data class Outcome(val requested: Int, val notFound: Int)

    /**
     * Requests the albums behind [wanted]. Blocking network work; call off the main thread.
     *
     * Tracks with no album name fall back to searching on the track title, which Lidarr's metadata
     * source will usually resolve to the record it appeared on - a single is still an album to it.
     */
    suspend fun request(context: Context, wanted: List<Wanted>): Outcome {
        val store = LidarrCredentialStore(context)
        if (!store.isConfigured()) return Outcome(0, wanted.size)
        val client = LidarrClient(store)

        // Collapse to distinct album requests before touching the network.
        val searches = wanted
            .map { it.artist.trim() to (it.album?.trim().orEmpty().ifBlank { it.title.trim() }) }
            .filter { it.first.isNotBlank() || it.second.isNotBlank() }
            .distinct()

        var requested = 0
        var notFound = 0
        val alreadyRequested = mutableSetOf<String>()

        searches.forEach { (artist, album) ->
            val term = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" ")
            val match = try {
                client.searchAlbums(term).firstOrNull { candidate ->
                    // Lidarr ranks by relevance but will still return the wrong artist for a common
                    // title, so require the artist to agree before adding anything.
                    artist.isBlank() || candidate.artistName.matchesLoosely(artist)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lookup failed for $term", e)
                null
            }
            if (match == null) {
                notFound++
                return@forEach
            }
            if (!alreadyRequested.add(match.foreignAlbumId)) return@forEach
            try {
                if (client.addAlbum(match)) requested++ else notFound++
            } catch (e: Exception) {
                Log.w(TAG, "Could not request ${match.title}", e)
                notFound++
            }
        }
        Log.d(TAG, "Requested $requested albums, $notFound unresolved")
        return Outcome(requested, notFound)
    }

    /** Ignores case, punctuation and spacing, which the two sources rarely agree on. */
    private fun String.matchesLoosely(other: String): Boolean {
        val a = lowercase().filter { it.isLetterOrDigit() }
        val b = other.lowercase().filter { it.isLetterOrDigit() }
        return a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
    }
}
