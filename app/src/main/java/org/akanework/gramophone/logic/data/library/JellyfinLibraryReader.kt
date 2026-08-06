package org.akanework.gramophone.logic.data.library

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinIdMap
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import android.net.Uri

/**
 * The Jellyfin library, shaped the way the Accord screens expect it.
 *
 * The sync itself used to live inside the old MainActivity, which meant nothing could reach the
 * library without that activity being alive. It moves here so it belongs to the application and any
 * screen can collect it.
 *
 * Progress is reported through [syncProgress] rather than drawn directly, so whichever UI is on top
 * decides how to show it.
 */
class JellyfinLibraryReader(private val context: Context) : LibraryReader {

    /** null until the first load finishes, so callers can tell "empty" from "not loaded yet". */
    private val store = MutableStateFlow<MediaStoreUtils.LibraryStoreClass?>(null)

    /** `loaded to total` while a server sync runs, null when idle. */
    val syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Set when a sync fails and nothing was cached, so the UI can say the server is unreachable. */
    val lastErrorUnreachable = MutableStateFlow(false)

    private val refreshLock = Mutex()
    private var loadedFromCache = false

    override val songListFlow: Flow<List<MediaItem>> = store.map { it?.songList ?: emptyList() }
    override val albumListFlow: Flow<List<Album>> =
        store.map { s -> s?.albumList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val albumArtistListFlow: Flow<List<Artist>> =
        store.map { s -> s?.albumArtistList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val artistListFlow: Flow<List<Artist>> =
        store.map { s -> s?.artistList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val genreListFlow: Flow<List<Genre>> =
        store.map { s -> s?.genreList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val dateListFlow: Flow<List<Date>> =
        store.map { s -> s?.dateList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val playlistListFlow: Flow<List<Playlist>> =
        store.map { s -> s?.playlistList?.map { it.toLibPhonograph() } ?: emptyList() }

    override suspend fun refresh() = refresh(force = false)

    /**
     * @param force sync from the server even when "sync on startup" is off. An explicit refresh
     *   should always reach the server; a launch should be allowed not to.
     */
    suspend fun refresh(force: Boolean) = refreshLock.withLock {
        val api = JellyfinClientHolder.api()
        if (api == null) {
            // Signed out. Not an error - the sign-in screen is what gets us back here.
            store.value = store.value ?: EMPTY
            return@withLock
        }
        val db = AppDatabase.getInstance(context)
        val cacheDao = db.cachedSongDao()
        val loader = JellyfinLibraryLoader(api, JellyfinIdMap(db.jellyfinIdDao()))

        // Show the cache first. A full sync of a large library takes the better part of a minute,
        // and there is no reason to stare at an empty library while it runs.
        if (!loadedFromCache) {
            val cached = runCatching { loader.loadFromCache(cacheDao) }
                .onFailure { Log.e(TAG, "Reading library cache failed", it) }
                .getOrNull()
            if (cached != null) {
                store.value = cached
                loadedFromCache = true
                val syncOnStartup = PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("sync_on_startup", false)
                if (!force && !syncOnStartup) {
                    Log.d(TAG, "Skipping startup sync (disabled in settings)")
                    return@withLock
                }
            }
        }

        val hadSomething = store.value != null
        syncProgress.value = 0 to 0
        val synced = runCatching {
            loader.load(cacheDao) { loaded, total -> syncProgress.value = loaded to total }
        }.onFailure { Log.e(TAG, "Jellyfin library sync failed", it) }.getOrNull()
        syncProgress.value = null

        if (synced != null) {
            store.value = synced
            lastErrorUnreachable.value = false
        } else {
            // With a library already on screen a failed refresh is not worth shouting about: the
            // user has something usable and the next launch tries again.
            lastErrorUnreachable.value = !hadSomething
            store.value = store.value ?: EMPTY
        }
    }

    companion object {
        private const val TAG = "JellyfinLibraryReader"

        private val EMPTY = MediaStoreUtils.LibraryStoreClass(
            mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(),
            mutableListOf(), mutableListOf(),
            MediaStoreUtils.FileNode(""), MediaStoreUtils.FileNode(""), emptySet()
        )
    }
}

// --- Model bridge -------------------------------------------------------------------------------
//
// This app and libPhonograph describe a library with near-identical types that share no common
// supertype, so the Accord screens cannot read this app's model directly. These map one to the
// other. Where libPhonograph carries a field Jellyfin has no answer for - a file's add and modify
// timestamps - null is passed rather than inventing a value.

private class JellyfinAlbum(
    override val id: Long?,
    override val title: String?,
    override val songList: List<MediaItem>,
    override val albumArtist: String?,
    override val albumArtistId: Long?,
    override val albumYear: Int?,
    override val cover: Uri?
) : Album {
    override val albumAddDate: Long? = null
    override val albumModifiedDate: Long? = null
}

internal fun MediaStoreUtils.Album.toLibPhonograph(): Album = JellyfinAlbum(
    id = id,
    title = title,
    songList = songList,
    albumArtist = artist,
    albumArtistId = artistId,
    albumYear = albumYear,
    cover = cover
)

internal fun MediaStoreUtils.Artist.toLibPhonograph(): Artist = Artist(
    id = id,
    title = title,
    songList = songList,
    albumList = albumList.map { it.toLibPhonograph() }
)

internal fun MediaStoreUtils.Genre.toLibPhonograph(): Genre = Genre(id, title, songList)

internal fun MediaStoreUtils.Date.toLibPhonograph(): Date = Date(id, title, songList)

internal fun MediaStoreUtils.Playlist.toLibPhonograph(): Playlist = Playlist(
    id = id,
    title = title,
    // Server playlists have no file behind them and no gapless information to report.
    path = null,
    dateAdded = null,
    dateModified = null,
    hasGaps = false,
    songList = songList
)
