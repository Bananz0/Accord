package org.akanework.gramophone.logic.data.spotify

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlaylists

/**
 * Recreates a Spotify playlist on Jellyfin out of the user's own Jellyfin tracks.
 *
 * Nothing is copied from Spotify but names: each track is looked up in the library, and the playlist
 * that results points at files the user already owns. Anything not in the library is simply reported
 * as missing.
 */
object SpotifyPlaylistImporter {

    private const val TAG = "SpotifyPlaylistImporter"

    /**
     * [missingTracks] carries the tracks that found no local match, so the caller can offer to
     * request them from Lidarr - the whole point of noticing they are missing.
     */
    data class Result(
        val playlistName: String,
        val matched: Int,
        val missing: Int,
        val missingTracks: List<SpotifyClient.Track> = emptyList(),
        val remotePlaylistId: String? = null,
    )

    /**
     * Matches [tracks] against [library] and stores the result as a private playlist.
     *
     * Runs blocking database work, so call it off the main thread.
     */
    fun import(
        context: Context,
        playlistName: String,
        tracks: List<SpotifyClient.Track>,
        library: List<MediaItem>,
    ): Result {
        val index = buildIndex(library)
        val matched = LinkedHashSet<String>()
        val missingTracks = mutableListOf<SpotifyClient.Track>()

        tracks.forEach { track ->
            val mediaId = index.find(track)
            if (mediaId == null) {
                missingTracks += track
            } else {
                // A Spotify playlist can list the same track twice; an imported playlist should not.
                matched.add(mediaId)
            }
        }

        val remoteId = matched.takeIf { it.isNotEmpty() }?.let { mediaIds ->
            val existing = JellyfinPlaylists.list(context)
                .firstOrNull { it.name.equals(playlistName, ignoreCase = true) }
            if (existing == null) {
                JellyfinPlaylists.create(context, playlistName, mediaIds.toList())
            } else {
                // Re-importing is an additive sync: newly available tracks appear without creating
                // another entry point or deleting edits the user made to the Jellyfin playlist.
                val current = JellyfinPlaylists.items(context, existing.id, library)
                    .mapTo(HashSet()) { it.mediaId }
                val additions = mediaIds.filterNot { it in current }
                if (additions.isNotEmpty() && !JellyfinPlaylists.addTo(context, existing.id, additions)) {
                    null
                } else {
                    existing.id
                }
            }
        }
        if (matched.isNotEmpty() && remoteId == null) error("Jellyfin could not create $playlistName")

        Log.d(TAG, "Imported $playlistName: ${matched.size} matched, ${missingTracks.size} missing")
        return Result(playlistName, matched.size, missingTracks.size, missingTracks, remoteId)
    }

    private fun buildIndex(library: List<MediaItem>) = LibraryIndex(library)

    /**
     * Name-based lookup into the library.
     *
     * Two passes, because exact title-and-artist agreement is the safe match but a real library
     * disagrees with Spotify constantly - "(Remastered 2011)", "feat." spelled three ways, a
     * different apostrophe. Title-only is the fallback, and only when it is unambiguous, so a
     * loose match never silently picks the wrong one of four songs sharing a name.
     */
    private class LibraryIndex(library: List<MediaItem>) {

        private val byTitleAndArtist = HashMap<String, String>()
        private val byTitle = HashMap<String, MutableList<String>>()

        init {
            library.forEach { item ->
                val id = item.mediaId.takeIf { it.isNotBlank() } ?: return@forEach
                val title = item.mediaMetadata.title?.toString()?.normalise() ?: return@forEach
                val artist = item.mediaMetadata.artist?.toString()?.normalise().orEmpty()
                byTitleAndArtist.putIfAbsent("$title|$artist", id)
                byTitle.getOrPut(title) { mutableListOf() }.add(id)
            }
        }

        fun find(track: SpotifyClient.Track): String? {
            val title = track.title.normalise()
            val artist = track.artist.normalise()
            byTitleAndArtist["$title|$artist"]?.let { return it }

            // Spotify credits every featured artist on the track; the file often credits one. Try
            // the primary artist against a library artist string that merely contains it.
            byTitleAndArtist.entries.firstOrNull { (key, _) ->
                val (keyTitle, keyArtist) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                keyTitle == title && artist.isNotEmpty() &&
                        (keyArtist.contains(artist) || artist.contains(keyArtist))
            }?.let { return it.value }

            return byTitle[title]?.singleOrNull()
        }

        /**
         * Strips everything the two sources disagree about: case, punctuation, spacing, and the
         * bracketed suffixes labels attach to reissues.
         */
        private fun String.normalise(): String = lowercase()
            .replace(BRACKETED, "")
            .filter { it.isLetterOrDigit() }

        private companion object {
            val BRACKETED = Regex("[(\\[].*?[)\\]]")
        }
    }
}
