package org.akanework.gramophone.logic.data.spotify

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.Playlist

/**
 * Recreates a Spotify playlist locally out of the user's own Jellyfin tracks.
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
        val matched = LinkedHashSet<Long>()
        val missingTracks = mutableListOf<SpotifyClient.Track>()

        tracks.forEach { track ->
            val localId = index.find(track)
            if (localId == null) {
                missingTracks += track
            } else {
                // A Spotify playlist can list the same track twice; a local playlist should not.
                matched.add(localId)
            }
        }

        if (matched.isNotEmpty()) {
            val database = AppDatabase.getInstance(context)
            val playlistDao = database.playlistDao()
            val mediaItemDao = database.mediaItemDao()
            // Named for where it came from, so it is obvious later which playlists are imports.
            val name = "$playlistName (Spotify)"
            // Playlist ids are not auto-generated, so one has to be allocated here. Inserting with
            // id 0 collides with the "favourite" playlist, and addPlaylist ignores conflicts, so
            // the row would be dropped without a word and the tracks attached to nothing.
            val playlistId = (playlistDao.getAllPlaylists()
                .maxOfOrNull { it.playlist.playlistId } ?: 0L) + 1L
            playlistDao.addPlaylist(Playlist(playlistId, name, null))
            matched.forEach { localId ->
                mediaItemDao.addMediaItem(
                    org.akanework.gramophone.logic.data.db.entity.MediaItem(localId)
                )
                mediaItemDao.addMediaItemToPlaylist(playlistId, localId)
            }
        }

        Log.d(TAG, "Imported $playlistName: ${matched.size} matched, ${missingTracks.size} missing")
        return Result(playlistName, matched.size, missingTracks.size, missingTracks)
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

        private val byTitleAndArtist = HashMap<String, Long>()
        private val byTitle = HashMap<String, MutableList<Long>>()

        init {
            library.forEach { item ->
                val id = item.mediaId.toLongOrNull() ?: return@forEach
                val title = item.mediaMetadata.title?.toString()?.normalise() ?: return@forEach
                val artist = item.mediaMetadata.artist?.toString()?.normalise().orEmpty()
                byTitleAndArtist.putIfAbsent("$title|$artist", id)
                byTitle.getOrPut(title) { mutableListOf() }.add(id)
            }
        }

        fun find(track: SpotifyClient.Track): Long? {
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
