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

    private data class Lookup(val artist: String, val albumOrTitle: String, val albumKnown: Boolean)

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
            .map {
                val album = it.album?.trim().orEmpty()
                Lookup(
                    artist = it.artist.trim(),
                    albumOrTitle = album.ifBlank { it.title.trim() },
                    albumKnown = album.isNotBlank(),
                )
            }
            .filter { it.artist.isNotBlank() || it.albumOrTitle.isNotBlank() }
            .distinct()

        var requested = 0
        var notFound = 0
        val alreadyRequested = mutableSetOf<String>()

        searches.forEach { (artist, album, albumKnown) ->
            val term = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" ")
            val match = try {
                selectMatch(artist, album, albumKnown, client.searchAlbums(term))
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

    /**
     * Album names are authoritative when Spotify supplied one. Artist agreement breaks ties, but
     * is not mandatory because compilations are commonly credited to "Various Artists" in Lidarr.
     * When Spotify omitted the album, retain the old track-title fallback and trust artist match.
     */
    internal fun selectMatch(
        artist: String,
        albumOrTitle: String,
        albumKnown: Boolean,
        candidates: List<LidarrClient.AlbumResult>,
    ): LidarrClient.AlbumResult? {
        if (!albumKnown) {
            return candidates.firstOrNull {
                artist.isBlank() || it.artistName.matchesLoosely(artist)
            }
        }
        val titleMatches = candidates.filter { it.title.matchesLoosely(albumOrTitle) }
        return titleMatches.firstOrNull {
            artist.isBlank() || it.artistName.matchesLoosely(artist)
        } ?: titleMatches.firstOrNull()
    }
}
