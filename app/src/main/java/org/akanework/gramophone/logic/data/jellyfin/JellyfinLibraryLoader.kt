package org.akanework.gramophone.logic.data.jellyfin

import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.delay
import org.akanework.gramophone.logic.data.db.dao.CachedSongDao
import org.akanework.gramophone.logic.data.db.entity.CachedSong
import org.akanework.gramophone.logic.data.db.entity.JellyfinId
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.toDashedUuid
import org.akanework.gramophone.logic.utils.LibraryGrouper
import org.akanework.gramophone.logic.utils.MediaStoreUtils.LibraryStoreClass
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import java.time.ZoneOffset
import java.util.UUID

/**
 * Pulls the user's audio library off a Jellyfin server and shapes it into the same
 * [LibraryStoreClass] the rest of the app already consumes.
 *
 * The whole library is fetched in one paged sweep and grouped locally by [LibraryGrouper], rather
 * than asking the server separately for albums, artists and genres. One request per page keeps the
 * sync O(pages) instead of O(items), and it guarantees the groupings match what the songs actually
 * say - a server-side album list can contain albums whose tracks the user cannot see.
 */
class JellyfinLibraryLoader(
    private val api: ApiClient? = null,
    private val idMap: JellyfinIdMap,
) {

    /**
     * Local IDs the server considers favourites, populated by [load] and [loadFromCache].
     *
     * Favourites live on the server, so a sync is the point at which this device finds out what
     * was starred from any other client.
     */
    val favouriteLocalIds = mutableSetOf<Long>()

    /**
     * Rebuilds the library from the local cache without touching the network.
     *
     * Returns null when nothing is cached yet. Stream and artwork URLs are regenerated here rather
     * than stored, because they embed the access token and would break after a re-login.
     */
    fun loadFromCacheFast(dao: CachedSongDao, limit: Int = 100): LibraryStoreClass? {
        val cached = dao.getInitialFast(limit)
        if (cached.isEmpty()) return null
        val entries = cached.map { row -> cachedToEntry(row) }
        return LibraryGrouper.group(entries)
    }

    fun loadFromCache(dao: CachedSongDao): LibraryStoreClass? {
        val cached = dao.getAll()
        if (cached.isEmpty()) return null
        favouriteLocalIds.clear()
        val entries = cached.map { row ->
            if (row.isFavourite) favouriteLocalIds.add(row.localId)
            cachedToEntry(row)
        }
        Log.d(TAG, "Loaded ${entries.size} songs from cache")
        return LibraryGrouper.group(entries)
    }

    /**
     * Runs a full sync. Must be called off the main thread.
     *
     * @param dao when given, the synced library replaces the cache so the next launch is instant.
     * @param onProgress invoked with (loaded, total) as pages arrive.
     */
    suspend fun load(
        dao: CachedSongDao? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): LibraryStoreClass {
        idMap.load()
        favouriteLocalIds.clear()

        val rows = mutableListOf<CachedSong>()
        var startIndex = 0
        var total = -1

        while (true) {
            val response = fetchPageWithRetry(startIndex)
            val items = response.items
            if (total < 0) total = response.totalRecordCount
            if (items.isEmpty()) break

            for (item in items) {
                val row = toCachedSong(item) ?: continue
                if (row.isFavourite) favouriteLocalIds.add(row.localId)
                rows += row
            }
            startIndex += items.size
            onProgress?.invoke(startIndex, total)
            if (startIndex >= total) break
        }

        // Written once, after every ID for this sync has been allocated.
        idMap.flush()
        dao?.replaceAll(rows)
        Log.d(TAG, "Loaded ${rows.size} songs from Jellyfin")

        return LibraryGrouper.group(rows.map { cachedToEntry(it) })
    }

    /**
     * Fetches one page, retrying transient failures.
     *
     * A sync of a large library is dozens of sequential requests over minutes, and a phone's WiFi
     * dropping for a moment is routine. Without this, one hiccup on page 33 throws away the 32
     * pages already fetched and the user gets an empty library.
     */
    private suspend fun fetchPageWithRetry(startIndex: Int): BaseItemDtoQueryResult {
        var lastError: Exception? = null
        val client = checkNotNull(api) { "ApiClient is required for network sync" }
        repeat(MAX_PAGE_ATTEMPTS) { attempt ->
            try {
                val response by client.itemsApi.getItems(
                    includeItemTypes = setOf(BaseItemKind.AUDIO),
                    recursive = true,
                    sortBy = setOf(ItemSortBy.SORT_NAME),
                    fields = REQUESTED_FIELDS,
                    startIndex = startIndex,
                    limit = PAGE_SIZE,
                )
                return response
            } catch (e: ApiClientException) {
                lastError = e
                Log.w(TAG, "Page at $startIndex failed (attempt ${attempt + 1})", e)
                if (attempt < MAX_PAGE_ATTEMPTS - 1) {
                    delay(RETRY_BASE_DELAY_MS * (1L shl attempt))
                }
            }
        }
        throw lastError ?: IllegalStateException("Failed to fetch page at $startIndex")
    }

    /** Flattens a server item into the row that is both cached and turned into a [MediaItem]. */
    private fun toCachedSong(item: BaseItemDto): CachedSong? {
        val songId = idMap.intern(item.id.toString(), JellyfinId.TYPE_AUDIO) ?: return null

        val trackArtists = item.artistItems
            ?.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            ?.ifEmpty { null }
            ?: item.artists?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            ?: emptyList()
        val artistItem = item.artistItems?.firstOrNull()
        val artistName = artistItem?.name ?: item.artists?.firstOrNull()
        val artistId = artistItem?.id?.let { idMap.intern(it.toString(), JellyfinId.TYPE_ARTIST) }
            ?: idMap.internName(artistName, JellyfinId.TYPE_ARTIST)

        val albumId = item.albumId?.let { idMap.intern(it.toString(), JellyfinId.TYPE_ALBUM) }

        val genreItem = item.genreItems?.firstOrNull()
        val genreName = genreItem?.name ?: item.genres?.firstOrNull()
        val genreId = genreItem?.id?.let { idMap.intern(it.toString(), JellyfinId.TYPE_GENRE) }
            ?: idMap.internName(genreName, JellyfinId.TYPE_GENRE)

        val userData = item.userData

        return CachedSong(
            localId = songId,
            jellyfinId = item.id.toString(),
            title = item.name,
            artist = artistName,
            trackArtists = trackArtists.distinctBy(String::lowercase).joinToString(TRACK_ARTIST_SEPARATOR)
                .takeIf(String::isNotBlank),
            artistId = artistId,
            album = item.album,
            albumId = albumId,
            albumArtist = item.albumArtist,
            genre = genreName,
            genreId = genreId,
            albumYear = item.productionYear,
            trackNumber = item.indexNumber,
            discNumber = item.parentIndexNumber,
            // Milliseconds, matching what MediaStore reported and what the UI formats.
            durationMs = item.runTimeTicks?.let { it / TICKS_PER_MILLISECOND },
            // Seconds since epoch, matching MediaStore's DATE_ADDED.
            addDate = item.dateCreated?.toEpochSecond(ZoneOffset.UTC),
            path = item.path,
            container = item.container,
            mediaSourceId = item.mediaSources?.firstOrNull()?.id,
            albumJellyfinId = item.albumId?.toString(),
            albumImageTag = item.albumPrimaryImageTag,
            ownImageTag = item.imageTags?.get(ImageType.PRIMARY),
            playCount = userData?.playCount ?: 0,
            isFavourite = userData?.isFavorite == true,
            lastPlayed = userData?.lastPlayedDate?.toEpochSecond(ZoneOffset.UTC),
        )
    }

    /** Builds the playable [MediaItem] and grouping keys from a row, cached or freshly synced. */
    private fun cachedToEntry(row: CachedSong): LibraryGrouper.SongEntry {
        val coverUri = artworkUrl(row)?.toUri()

        val song = MediaItem.Builder()
            // The stream URL already carries api_key, so playback never has to resolve anything.
            .setUri(streamUrl(row))
            .setMediaId(row.localId.toString())
            // Deliberately no setMimeType: Jellyfin reports a container ("flac"), not a MIME type,
            // and a wrong MIME stops ExoPlayer picking an extractor. Sniffing is reliable here.
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setTitle(row.title)
                    .setArtist(row.artist)
                    .setAlbumTitle(row.album)
                    .setAlbumArtist(row.albumArtist)
                    .setArtworkUri(coverUri)
                    .setTrackNumber(row.trackNumber)
                    .setDiscNumber(row.discNumber)
                    .setGenre(row.genre)
                    .setReleaseYear(row.albumYear)
                    .setRecordingYear(row.albumYear)
                    .setExtras(Bundle().apply {
                        row.artistId?.let { putLong("ArtistId", it) }
                        row.albumId?.let { putLong("AlbumId", it) }
                        row.genreId?.let { putLong("GenreId", it) }
                        row.addDate?.let { putLong("AddDate", it) }
                        row.durationMs?.let { putLong("Duration", it) }
                        // ModifiedDate has no Jellyfin equivalent; sorting by it falls back to
                        // the date the server first saw the file.
                        row.addDate?.let { putLong("ModifiedDate", it) }
                        // Server-side listening history. This is what makes recommendations
                        // reflect everything the user has played on any client, not just what
                        // happened to be played on this phone.
                        putInt(EXTRA_PLAY_COUNT, row.playCount)
                        putBoolean(EXTRA_IS_FAVOURITE, row.isFavourite)
                        row.lastPlayed?.let { putLong(EXTRA_LAST_PLAYED, it) }
                        row.trackArtists
                            ?.split(TRACK_ARTIST_SEPARATOR)
                            ?.filter(String::isNotBlank)
                            ?.let { putStringArrayList(EXTRA_TRACK_ARTISTS, ArrayList(it)) }
                    })
                    .build()
            ).build()

        return LibraryGrouper.SongEntry(
            mediaItem = song,
            artist = row.artist,
            artistId = row.artistId,
            album = row.album,
            albumId = row.albumId,
            albumArtist = row.albumArtist,
            albumYear = row.albumYear,
            genre = row.genre,
            genreId = row.genreId,
            cover = coverUri,
            addDate = row.addDate,
            path = row.path,
        )
    }

    /**
     * Direct stream of the original file. Deliberately not the transcoding endpoint: this app
     * bundles an FFmpeg decoder and relies on real container metadata for gapless playback, both of
     * which server-side transcoding would throw away.
     */
    private fun streamUrl(row: CachedSong): String {
        val client = api ?: JellyfinClientHolder.api() ?: return ""
        return client.audioApi.getAudioStreamUrl(
            itemId = UUID.fromString(row.jellyfinId.toDashedUuid()),
            container = row.container,
            mediaSourceId = row.mediaSourceId,
            static = true,
        )
    }

    /**
     * Prefers the album's artwork so every track in an album shares one cache entry, and falls
     * back to a track-specific image when the album has none.
     */
    private fun artworkUrl(row: CachedSong): String? {
        val client = api ?: JellyfinClientHolder.api() ?: return null
        val albumTag = row.albumImageTag
        val albumGuid = row.albumJellyfinId
        if (albumTag != null && albumGuid != null) {
            return client.imageApi.getItemImageUrl(
                itemId = UUID.fromString(albumGuid.toDashedUuid()),
                imageType = ImageType.PRIMARY,
                tag = albumTag,
            )
        }
        val ownTag = row.ownImageTag ?: return null
        return client.imageApi.getItemImageUrl(
            itemId = UUID.fromString(row.jellyfinId.toDashedUuid()),
            imageType = ImageType.PRIMARY,
            tag = ownTag,
        )
    }

    companion object {
        /** Extras carrying Jellyfin's server-side listening history onto each MediaItem. */
        const val EXTRA_PLAY_COUNT = "JellyfinPlayCount"
        const val EXTRA_IS_FAVOURITE = "JellyfinIsFavourite"
        const val EXTRA_LAST_PLAYED = "JellyfinLastPlayed"
        const val EXTRA_TRACK_ARTISTS = "JellyfinTrackArtists"

        private const val TAG = "JellyfinLibraryLoader"
        private const val PAGE_SIZE = 500
        private const val TICKS_PER_MILLISECOND = 10_000L
        private const val MAX_PAGE_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private const val TRACK_ARTIST_SEPARATOR = "\u001f"

        private val REQUESTED_FIELDS = setOf(
            ItemFields.GENRES,
            ItemFields.DATE_CREATED,
            ItemFields.MEDIA_SOURCES,
            ItemFields.PATH,
            ItemFields.PARENT_ID,
            ItemFields.SORT_NAME,
        )
    }
}
