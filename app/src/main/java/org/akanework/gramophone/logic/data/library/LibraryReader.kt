package org.akanework.gramophone.logic.data.library

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Returns a usable one-shot library for menu actions.
 *
 * [CompositeLibraryReader] deliberately emits an empty placeholder before its cached/server and
 * local sources are ready. Calling `songListFlow.first()` therefore always returned that placeholder
 * and made Create station, whole-library shuffle and other one-shot controls appear inert.
 */
suspend fun LibraryReader.songListSnapshot(timeoutMillis: Long = 2_000L): List<MediaItem> =
    withTimeoutOrNull(timeoutMillis) {
        songListFlow.first { it.isNotEmpty() }
    }.orEmpty()

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
 * Emits immediately on startup using `onStart { emit(emptyList()) }` so the UI does not wait
 * for both remote and local scanning to complete before rendering initial cached items.
 */
class CompositeLibraryReader(
    private val remote: LibraryReader,
    private val local: LibraryReader
) : LibraryReader {
    override val songListFlow = concat(remote.songListFlow, local.songListFlow)
    override val albumListFlow = concat(remote.albumListFlow, local.albumListFlow)
    override val albumArtistListFlow = concat(remote.albumArtistListFlow, local.albumArtistListFlow)
    override val artistListFlow = concat(remote.artistListFlow, local.artistListFlow)

    override val genreListFlow: Flow<List<Genre>> =
        combine(
            remote.genreListFlow.onStart { emit(emptyList()) },
            local.genreListFlow.onStart { emit(emptyList()) }
        ) { remoteGenres, localGenres ->
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
        combine(
            a.onStart { emit(emptyList()) },
            b.onStart { emit(emptyList()) }
        ) { first, second -> first + second }
}
