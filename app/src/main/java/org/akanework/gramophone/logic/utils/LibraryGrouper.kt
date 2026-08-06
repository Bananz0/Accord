/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic.utils

import android.net.Uri
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.putIfAbsentSupport
import org.akanework.gramophone.logic.utils.MediaStoreUtils.Album
import org.akanework.gramophone.logic.utils.MediaStoreUtils.AlbumImpl
import org.akanework.gramophone.logic.utils.MediaStoreUtils.Artist
import org.akanework.gramophone.logic.utils.MediaStoreUtils.Date
import org.akanework.gramophone.logic.utils.MediaStoreUtils.FileNode
import org.akanework.gramophone.logic.utils.MediaStoreUtils.Genre
import org.akanework.gramophone.logic.utils.MediaStoreUtils.LibraryStoreClass
import org.akanework.gramophone.logic.utils.MediaStoreUtils.Playlist
import org.akanework.gramophone.logic.utils.MediaStoreUtils.RecentlyAdded
import java.io.File
import java.util.PriorityQueue

/**
 * [LibraryGrouper] turns a flat list of songs into the album / artist / album artist / genre /
 * date / folder structures the UI binds against.
 *
 * This is the aggregation half of what used to live inside [MediaStoreUtils.getAllSongs], lifted
 * out so it does not depend on MediaStore: any source that can describe its songs as
 * [SongEntry] values gets the exact same grouping semantics, and therefore the exact same
 * behaviour from every comparator in `logic/comparators` and every adapter downstream.
 */
object LibraryGrouper {

    /** Two weeks, matching the window [MediaStoreUtils] used for "recently added". */
    const val DEFAULT_RECENTLY_ADDED_WINDOW_SECONDS = 2L * 7 * 24 * 60 * 60

    /**
     * One song plus the keys it is grouped by. IDs are opaque [Long]s - sources whose native
     * identifiers are not numeric (Jellyfin's GUIDs, for example) are expected to intern them
     * first, so that everything downstream can keep treating IDs as [Long].
     */
    data class SongEntry(
        val mediaItem: MediaItem,
        val artist: String?,
        val artistId: Long?,
        val album: String?,
        val albumId: Long?,
        val albumArtist: String?,
        val albumYear: Int?,
        val genre: String?,
        val genreId: Long?,
        val cover: Uri?,
        /** Seconds since epoch, used for the "recently added" playlist. */
        val addDate: Long?,
        /** Absolute path including the file name, or null for sources without a file layout. */
        val path: String?,
    )

    /**
     * Groups [entries] into a [LibraryStoreClass].
     *
     * @param entries every song in the library, already in the desired base order.
     * @param playlists source-provided playlists; a "recently added" entry is appended.
     * @param extraFolders folders to list even when no song currently resolves to them (the
     *   blacklist relies on this so blacklisted folders stay togglable).
     * @param albumIdToArtist optional albumId -> (artistId, artistName) hint. When the name
     *   matches the album's own artist string, the album adopts that artist ID.
     * @param recentlyAddedWindowSeconds how far back the "recently added" playlist reaches.
     */
    fun group(
        entries: List<SongEntry>,
        playlists: List<Playlist> = emptyList(),
        extraFolders: Set<String> = emptySet(),
        albumIdToArtist: Map<Long, Pair<Long, String?>>? = null,
        recentlyAddedWindowSeconds: Long = DEFAULT_RECENTLY_ADDED_WINDOW_SECONDS,
    ): LibraryStoreClass {
        val songs = mutableListOf<MediaItem>()
        val albumMap = hashMapOf<Long?, AlbumImpl>()
        val artistMap = hashMapOf<Long?, Artist>()
        val artistCacheMap = hashMapOf<String?, Long?>()
        val albumArtistMap = hashMapOf<String?, Pair<MutableList<Album>, MutableList<MediaItem>>>()
        // Note: it has been observed on a user's Pixel(!) that MediaStore assigned 3 different IDs
        // for "Unknown genre" (null genre tag), hence we practically ignore genre IDs as key
        val genreMap = hashMapOf<String?, Genre>()
        val dateMap = hashMapOf<Int?, Date>()
        val folders = hashSetOf<String>()
        val folderArray = mutableListOf<String>()
        val root = FileNode("storage")
        val shallowRoot = FileNode("shallow")
        val recentlyAddedMap = PriorityQueue<Pair<Long, MediaItem>>(
            // PriorityQueue throws if initialCapacity < 1
            entries.size.coerceAtLeast(1),
            Comparator { a, b ->
                // reversed int order to sort from most recent to least recent
                return@Comparator if (a.first == b.first) 0 else (if (a.first > b.first) -1 else 1)
            })

        for (entry in entries) {
            val song = entry.mediaItem
            songs.add(song)
            if (entry.addDate != null) {
                recentlyAddedMap.add(Pair(entry.addDate, song))
            }
            artistMap.getOrPut(entry.artistId) {
                Artist(entry.artistId, entry.artist, mutableListOf(), mutableListOf())
            }.songList.add(song)
            artistCacheMap.putIfAbsentSupport(entry.artist, entry.artistId)
            albumMap.getOrPut(entry.albumId) {
                val artistStr = entry.albumArtist ?: entry.artist
                val likelyArtist = entry.albumId
                    ?.let { albumIdToArtist?.get(it) }
                    ?.run { if (second == artistStr) this else null }
                AlbumImpl(
                    entry.albumId,
                    entry.album,
                    artistStr,
                    likelyArtist?.first,
                    entry.albumYear,
                    entry.cover,
                    mutableListOf()
                ).also { alb ->
                    albumArtistMap.getOrPut(artistStr) {
                        Pair(mutableListOf(), mutableListOf())
                    }.first.add(alb)
                }
            }.also { alb ->
                albumArtistMap.getOrPut(alb.artist) {
                    Pair(mutableListOf(), mutableListOf())
                }.second.add(song)
            }.songList.add(song)
            // A track's genre tag is often several genres in one string - "Afrobeat;Afrobeats",
            // "Alt. Metal / Metalcore / Industrial Rock". Keyed whole, every distinct combination
            // became its own genre, so the list held "Afrobeat", "Afrobeats" and "Afrobeat;
            // Afrobeats" as three unrelated entries and none of them held all the tracks. Split
            // them, and a song joins each genre it actually belongs to.
            val genres = entry.genre.splitGenreTag()
            if (genres.isEmpty()) {
                genreMap.getOrPut(null) { Genre(entry.genreId, null, mutableListOf()) }
                    .songList.add(song)
            } else {
                genres.forEach { name ->
                    // Keyed on the lowercased name, not the id: the same genre reached through
                    // different combinations carries different ids, and taggers disagree on case.
                    // The first spelling seen is the one displayed.
                    genreMap.getOrPut(name.lowercase()) { Genre(null, name, mutableListOf()) }
                        .songList.add(song)
                }
            }
            dateMap.getOrPut(entry.albumYear) {
                Date(
                    entry.albumYear?.toLong() ?: 0,
                    entry.albumYear?.toString(),
                    mutableListOf()
                )
            }.songList.add(song)
            // Sources without a file layout (or with an unusable one) simply do not take part in
            // the folder views; everything else about them still groups normally.
            val path = entry.path
            if (path != null && path.startsWith('/') && path.count { it == '/' } >= 2) {
                MediaStoreUtils.handleMediaFolder(path, root).addSong(song, entry.albumId)
                MediaStoreUtils.handleShallowMediaItem(
                    song, entry.albumId, path, shallowRoot, folderArray
                )
                File(path).parentFile?.absolutePath?.let { folders.add(it) }
            }
        }

        val albumList = albumMap.values.onEach {
            if (it.artistId == null) {
                it.artistId = artistCacheMap[it.artist]
            }
            artistMap[it.artistId]?.albumList?.add(it)
        }.toMutableList<Album>()
        val artistList = artistMap.values.toMutableList()
        val albumArtistList = albumArtistMap.entries.map { (artist, albumsAndSongs) ->
            Artist(artistCacheMap[artist], artist, albumsAndSongs.second, albumsAndSongs.first)
        }.toMutableList()
        val genreList = genreMap.values.toMutableList()
        val dateList = dateMap.values.toMutableList()
        val playlistsFinal = playlists.toMutableList()
        playlistsFinal.add(
            RecentlyAdded(
                (System.currentTimeMillis() / 1000) - recentlyAddedWindowSeconds,
                recentlyAddedMap
            )
        )
        folders.addAll(extraFolders)
        return LibraryStoreClass(
            songs,
            albumList,
            albumArtistList,
            artistList,
            genreList,
            dateList,
            playlistsFinal,
            root,
            shallowRoot,
            folders
        )
    }
}

/**
 * Splits a genre tag into the genres it actually names.
 *
 * Tags arrive delimited in whatever way the tagger used - ';', '/', ',' - and with inconsistent
 * spacing, so "Afrobeat;Afrobeats" and "Afrobeat ; Afrobeats" have to land on the same two genres.
 * Case is preserved for display but compared case-insensitively by the caller's map key, which is
 * why the trimmed original is returned rather than a lowercased one.
 */
internal fun String?.splitGenreTag(): List<String> =
    this?.split(';', '/', ',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        // Bare numbers are not genres. Tags carry ID3v1 genre indices and stray numeric fragments,
        // and splitting turned those into entries called "13" and "79" sitting above the real ones.
        ?.filterNot { token -> token.all { it.isDigit() } }
        ?.distinctBy { it.lowercase() }
        .orEmpty()
