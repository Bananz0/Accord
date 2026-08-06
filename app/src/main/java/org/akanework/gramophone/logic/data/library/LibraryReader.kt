package org.akanework.gramophone.logic.data.library

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.reader.FlowReader

/**
 * Where the Accord screens get their library from.
 *
 * Upstream Accord reads MediaStore directly through libPhonograph's [FlowReader], which every one
 * of its fragments and adapters collects from. This app's library lives on a Jellyfin server, so
 * that single dependency is turned into an interface: the screens keep collecting the same flows,
 * and what fills them - local files, the server, or both - is decided here.
 *
 * Deliberately narrower than [FlowReader]. Only the flows the Accord UI actually collects are
 * modelled; folder structure has no meaning for a remote library and no ported screen asks for it.
 */
interface LibraryReader {
    val songListFlow: Flow<List<MediaItem>>
    val albumListFlow: Flow<List<Album>>
    val albumArtistListFlow: Flow<List<Artist>>
    val artistListFlow: Flow<List<Artist>>
    val genreListFlow: Flow<List<Genre>>
    val dateListFlow: Flow<List<Date>>
    val playlistListFlow: Flow<List<Playlist>>

    /** Re-reads the library. Cheap and incremental for MediaStore, a network sync for Jellyfin. */
    suspend fun refresh()
}

/** The local library, straight off libPhonograph. */
class MediaStoreLibraryReader(private val delegate: FlowReader) : LibraryReader {
    override val songListFlow get() = delegate.songListFlow
    override val albumListFlow get() = delegate.albumListFlow
    override val albumArtistListFlow get() = delegate.albumArtistListFlow
    override val artistListFlow get() = delegate.artistListFlow
    override val genreListFlow get() = delegate.genreListFlow
    override val dateListFlow get() = delegate.dateListFlow
    override val playlistListFlow get() = delegate.playlistListFlow
    override suspend fun refresh() = delegate.refresh()
}

/**
 * Both libraries at once, concatenated - server first, since that is the larger collection here and
 * the one worth seeing at the top of a list.
 *
 * No attempt is made to detect the same song appearing in both. Matching a local file against a
 * server item reliably needs more than title and artist, and showing a track twice is a much
 * smaller problem than hiding one because a fuzzy match went wrong.
 */
class CompositeLibraryReader(
    private val remote: LibraryReader,
    private val local: LibraryReader
) : LibraryReader {
    override val songListFlow = concat(remote.songListFlow, local.songListFlow)
    override val albumListFlow = concat(remote.albumListFlow, local.albumListFlow)
    override val albumArtistListFlow = concat(remote.albumArtistListFlow, local.albumArtistListFlow)
    override val artistListFlow = concat(remote.artistListFlow, local.artistListFlow)
    /**
     * Merged by name rather than concatenated. A genre is the same genre whichever library it came
     * from, and straight concatenation put two "Unknown genre" rows next to each other - one from
     * the server, one from local files - and would split any genre both libraries carry.
     */
    override val genreListFlow: Flow<List<Genre>> =
        combine(remote.genreListFlow, local.genreListFlow) { remoteGenres, localGenres ->
            val merged = LinkedHashMap<String, Genre>()
            (remoteGenres + localGenres).forEach { genre ->
                val key = genre.title?.lowercase().orEmpty()
                val existing = merged[key]
                merged[key] = if (existing == null) {
                    genre
                } else {
                    Genre(existing.id, existing.title, existing.songList + genre.songList)
                }
            }
            merged.values.toList()
        }
    override val dateListFlow = concat(remote.dateListFlow, local.dateListFlow)
    override val playlistListFlow = concat(remote.playlistListFlow, local.playlistListFlow)

    override suspend fun refresh() {
        remote.refresh()
        local.refresh()
    }

    private fun <T> concat(a: Flow<List<T>>, b: Flow<List<T>>): Flow<List<T>> =
        combine(a, b) { first, second -> first + second }
}
