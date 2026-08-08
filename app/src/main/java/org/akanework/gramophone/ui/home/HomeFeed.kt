package org.akanework.gramophone.ui.home

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import uk.akane.accord.R
import java.util.Calendar

enum class HomeSectionStyle { ROW, STATION }

enum class HomeCardTarget { MIX, ALBUM }

data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val cards: List<HomeCard>,
    val style: HomeSectionStyle = HomeSectionStyle.ROW,
)

data class HomeCard(
    val title: String,
    val subtitle: String?,
    val cover: Uri?,
    val collageCovers: List<Uri> = emptyList(),
    val songs: List<MediaItem>,
    val startIndex: Int = 0,
    val cachedMediaIds: List<String> = emptyList(),
    val target: HomeCardTarget = HomeCardTarget.MIX,
) {
    val mediaIds: List<String>
        get() = songs.map { it.mediaId }.ifEmpty { cachedMediaIds }
}

/** Builds a deterministic home feed from the listening signals already stored by Jellyfin. */
object HomeFeed {

    private const val ROW_SIZE = 12
    private const val MIX_SIZE = 50
    private const val MIN_MIX_SIZE = 5

    data class ArtistInput(val title: String?, val songList: List<MediaItem>)

    fun build(
        context: Context,
        library: List<MediaItem>,
        artists: List<ArtistInput>,
    ): List<HomeSection> {
        if (library.isEmpty()) return emptyList()

        return buildList {
            jumpBackIn(context, library)?.let(::add)
            madeForYou(context, library, artists)?.let(::add)
            recentlyAddedAlbums(context, library)?.let(::add)
            topMixes(context, artists)?.let(::add)
            genreMixes(library)?.let(::add)
            finishYourAlbums(library)?.let(::add)
            decadeMixes(library)?.let(::add)
        }
    }

    /**
     * Every tile is an independently compiled playlist. This deliberately avoids the old pattern
     * where a row showed thirty covers that all opened the same backing queue at a different index.
     */
    private fun madeForYou(
        context: Context,
        library: List<MediaItem>,
        artists: List<ArtistInput>,
    ): HomeSection? {
        val played = library.filter { it.playCount() > 0 }
        val favourites = library.filter { it.isFavourite() }
        val topArtists = artists
            .filter { it.title?.isNotBlank() == true }
            .sortedByDescending { artist -> artist.songList.sumOf { it.playCount() } }
        val topArtistNames = topArtists.take(8).mapNotNull { it.title?.normaliseForMatch() }.toSet()
        val likedGenres = library
            .filter { it.playCount() > 0 || it.isFavourite() }
            .mapNotNull { item -> item.genreKey()?.let { it to (item.playCount() + if (item.isFavourite()) 5 else 0) } }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedByDescending { entry -> entry.value.sum() }
            .take(6)
            .map { it.key }
            .toSet()

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val partOfDay = when (hour) {
            in 5..10 -> "morning"
            in 11..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "late night"
        }

        val cards = listOfNotNull(
            mixCard(
                title = "Personal anthems",
                subtitle = "Your biggest songs, balanced across the artists you love",
                songs = played.sortedByDescending { it.playCount() }.balancedByArtist(),
            ),
            mixCard(
                title = "Current rotation",
                subtitle = "The music defining your recent listening",
                songs = played.filter { it.lastPlayed() > 0L }
                    .sortedByDescending { it.lastPlayed() }
                    .take(MIX_SIZE * 3)
                    .balancedByArtist(),
            ),
            mixCard(
                title = "Loved essentials",
                subtitle = "A fresh sequence of songs you have favourited",
                songs = favourites.stableShuffle(weeklySeed("loved_essentials")),
            ),
            mixCard(
                title = "Forgotten favourites",
                subtitle = "Old favourites that have been waiting for another play",
                songs = favourites.filter { it.lastPlayed() > 0L }
                    .sortedBy { it.lastPlayed() }
                    .balancedByArtist(),
            ),
            mixCard(
                title = "Deep cuts",
                subtitle = "Less-played tracks from the artists already in your orbit",
                songs = library.filter { item ->
                    item.artistKey() in topArtistNames && item.playCount() <= 1
                }.stableShuffle(weeklySeed("deep_cuts")),
            ),
            mixCard(
                title = "New to you",
                subtitle = "Unplayed tracks close to the genres you return to",
                songs = library.filter { item ->
                    item.playCount() == 0 &&
                        item.genreKey() in likedGenres &&
                        item.artistKey() !in topArtistNames.take(3)
                }.stableShuffle(weeklySeed("new_to_you")),
            ),
            mixCard(
                title = "Soundtrack your $partOfDay",
                subtitle = "A different corner of your library for right now",
                songs = library.stableShuffle(dailySeed("daylist_${hour / 4}")),
            ),
            mixCard(
                title = "Fresh arrivals",
                subtitle = "Recently added music, mixed across artists",
                songs = library.sortedByDescending { it.addDate() }
                    .take(MIX_SIZE * 3)
                    .balancedByArtist(),
            ),
            mixCard(
                title = "Album sampler",
                subtitle = "One doorway into every record in your collection",
                songs = library
                    .distinctBy { it.albumKey() ?: it.mediaId }
                    .stableShuffle(weeklySeed("album_sampler")),
            ),
        ).withoutNearDuplicates()

        if (cards.isEmpty()) return null
        return HomeSection(
            id = "made_for_you_v2",
            title = context.getString(R.string.home_made_for_you),
            subtitle = "Distinct mixes built from different parts of your listening",
            cards = cards,
            style = HomeSectionStyle.STATION,
        )
    }

    private fun mixCard(title: String, subtitle: String, songs: List<MediaItem>): HomeCard? {
        val selected = songs.distinctBy { it.mediaId }.take(MIX_SIZE)
        if (selected.size < MIN_MIX_SIZE) return null
        return HomeCard(
            title = title,
            subtitle = subtitle,
            cover = null,
            collageCovers = selected.mapNotNull { it.mediaMetadata.artworkUri }.distinct().take(4),
            songs = selected,
        )
    }

    private fun jumpBackIn(context: Context, library: List<MediaItem>): HomeSection? {
        val played = library.filter { it.lastPlayed() > 0L }
        val source = if (played.isNotEmpty()) played.sortedByDescending { it.lastPlayed() }
        else library.sortedByDescending { it.addDate() }
        val albums = source.toAlbumGroups().take(ROW_SIZE)
        if (albums.isEmpty()) return null

        return HomeSection(
            id = "jump_back_in_v2",
            title = context.getString(R.string.home_jump_back_in),
            cards = albums.map { (_, tracks) -> tracks.toAlbumCard() },
        )
    }

    private fun recentlyAddedAlbums(context: Context, library: List<MediaItem>): HomeSection? {
        val albums = library
            .sortedByDescending { it.addDate() }
            .toAlbumGroups()
            .take(ROW_SIZE)
        if (albums.isEmpty()) return null

        return HomeSection(
            id = "recently_added_albums_v2",
            title = context.getString(R.string.mix_recently_added),
            subtitle = "The newest records in your library",
            cards = albums.map { (_, tracks) -> tracks.toAlbumCard() },
        )
    }

    private fun topMixes(context: Context, allArtists: List<ArtistInput>): HomeSection? {
        val artists = allArtists
            .filter { it.songList.size >= 3 && it.title?.isNotBlank() == true }
            .sortedByDescending { artist ->
                artist.songList.sumOf { it.playCount() }.takeIf { it > 0 } ?: artist.songList.size
            }
            .take(ROW_SIZE)
        if (artists.isEmpty()) return null

        return HomeSection(
            id = "top_mixes_v2",
            title = context.getString(R.string.home_top_mixes),
            subtitle = context.getString(R.string.home_top_mixes_subtitle),
            cards = artists.map { artist ->
                val title = artist.title ?: context.getString(R.string.unknown_artist)
                val songs = artist.songList
                    .stableShuffle(weeklySeed("artist_mix_$title"))
                    .take(MIX_SIZE)
                HomeCard(
                    title = context.getString(R.string.home_artist_mix, title),
                    subtitle = songs.mapNotNull { it.mediaMetadata.albumTitle?.toString() }
                        .distinct().take(3).joinToString(", "),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.mapNotNull { it.mediaMetadata.artworkUri }.distinct().take(4),
                    songs = songs,
                )
            },
        )
    }

    private fun genreMixes(library: List<MediaItem>): HomeSection? {
        val genres = library
            .mapNotNull { item -> item.genreKey()?.let { key -> Triple(key, item.genreName(), item) } }
            .groupBy({ it.first }, { it })
            .values
            .filter { it.size >= MIN_MIX_SIZE }
            .sortedByDescending { genre ->
                genre.sumOf { it.third.playCount() } * 3 + genre.size
            }
            .take(ROW_SIZE)
        if (genres.isEmpty()) return null

        return HomeSection(
            id = "genre_mixes_v2",
            title = "Your genre mixes",
            subtitle = "Each mix stays inside a different sound in your library",
            cards = genres.map { entries ->
                val displayName = entries.first().second
                val songs = entries.map { it.third }
                    .stableShuffle(weeklySeed("genre_${entries.first().first}"))
                    .take(MIX_SIZE)
                HomeCard(
                    title = "$displayName Mix",
                    subtitle = songs.mapNotNull { it.mediaMetadata.artist?.toString() }
                        .distinct().take(4).joinToString(", "),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.mapNotNull { it.mediaMetadata.artworkUri }.distinct().take(4),
                    songs = songs,
                )
            },
        )
    }

    private fun finishYourAlbums(library: List<MediaItem>): HomeSection? {
        val cards = library.toAlbumGroups()
            .mapNotNull { (_, tracks) ->
                val unheard = tracks.filter { it.playCount() == 0 }
                val playedCount = tracks.size - unheard.size
                if (tracks.size < 4 || playedCount == 0 || unheard.isEmpty()) return@mapNotNull null
                HomeCard(
                    title = tracks.albumTitle(),
                    subtitle = "${unheard.size} unheard · ${tracks.albumArtist()}",
                    cover = tracks.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    songs = unheard,
                )
            }
            .sortedByDescending { it.songs.size }
            .take(ROW_SIZE)
        if (cards.isEmpty()) return null

        return HomeSection(
            id = "finish_your_albums_v2",
            title = "Finish what you started",
            subtitle = "Unheard tracks from albums you have already begun",
            cards = cards,
        )
    }

    private fun decadeMixes(library: List<MediaItem>): HomeSection? {
        val decades = library.mapNotNull { item ->
            val year = item.mediaMetadata.releaseYear?.takeIf { it in 1950..2039 }
                ?: return@mapNotNull null
            (year / 10) * 10 to item
        }.groupBy({ it.first }, { it.second })
            .filterValues { it.size >= MIN_MIX_SIZE }
            .entries
            .sortedByDescending { it.key }
            .take(ROW_SIZE)
        if (decades.isEmpty()) return null

        return HomeSection(
            id = "decade_mixes_v2",
            title = "Time capsules",
            subtitle = "A separate playlist for every era in your collection",
            cards = decades.map { (decade, tracks) ->
                val songs = tracks.stableShuffle(weeklySeed("decade_$decade")).take(MIX_SIZE)
                HomeCard(
                    title = "${decade}s Mix",
                    subtitle = songs.mapNotNull { it.mediaMetadata.artist?.toString() }
                        .distinct().take(4).joinToString(", "),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.mapNotNull { it.mediaMetadata.artworkUri }.distinct().take(4),
                    songs = songs,
                )
            },
        )
    }

    /** Last.fm discovery, narrowed to artists that can actually be played from this library. */
    suspend fun similarArtistSection(
        context: Context,
        artists: List<ArtistInput>,
    ): HomeSection? {
        val store = LastFmCredentialStore(context)
        if (!store.hasApplicationCredentials() || artists.isEmpty()) return null

        val seed = artists
            .filter { artist -> artist.songList.any { it.playCount() > 0 } }
            .maxByOrNull { artist -> artist.songList.sumOf { it.playCount() } }
            ?: artists.firstOrNull()
            ?: return null
        val seedName = seed.title ?: return null
        val similar = try {
            LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl).getSimilarArtists(seedName)
        } catch (_: Exception) {
            return null
        }

        val byName = artists.associateBy { it.title?.normaliseForMatch().orEmpty() }
        val matches = similar.mapNotNull { byName[it.normaliseForMatch()] }
            .filter { it.title != seed.title && it.songList.size >= 3 }
            .distinctBy { it.title }
            .take(ROW_SIZE)
        if (matches.isEmpty()) return null

        return HomeSection(
            id = "for_fans_of_v2",
            title = context.getString(R.string.home_for_fans_of, seedName),
            subtitle = "Playable artist mixes related to $seedName",
            cards = matches.map { artist ->
                val title = artist.title ?: context.getString(R.string.unknown_artist)
                val songs = artist.songList
                    .stableShuffle(weeklySeed("similar_artist_$title"))
                    .take(MIX_SIZE)
                HomeCard(
                    title = "$title Mix",
                    subtitle = context.resources.getQuantityString(
                        R.plurals.songs, artist.songList.size, artist.songList.size,
                    ),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.mapNotNull { it.mediaMetadata.artworkUri }.distinct().take(4),
                    songs = songs,
                )
            },
        )
    }

    private fun List<MediaItem>.toAlbumGroups(): List<Pair<String, List<MediaItem>>> {
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        forEach { item ->
            val key = item.albumKey() ?: return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.map { it.key to it.value }
    }

    private fun List<MediaItem>.toAlbumCard(): HomeCard = HomeCard(
        title = albumTitle(),
        subtitle = albumArtist(),
        cover = firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
        songs = this,
        target = HomeCardTarget.ALBUM,
    )

    private fun List<MediaItem>.albumTitle(): String =
        firstNotNullOfOrNull { it.mediaMetadata.albumTitle?.toString()?.takeIf(String::isNotBlank) }
            .orEmpty()

    private fun List<MediaItem>.albumArtist(): String =
        firstNotNullOfOrNull {
            it.mediaMetadata.albumArtist?.toString()?.takeIf(String::isNotBlank)
                ?: it.mediaMetadata.artist?.toString()?.takeIf(String::isNotBlank)
        }.orEmpty()

    private fun MediaItem.albumKey(): String? {
        val title = mediaMetadata.albumTitle?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val artist = mediaMetadata.albumArtist?.toString()?.trim()
            ?: mediaMetadata.artist?.toString()?.trim().orEmpty()
        return "${title.normaliseForMatch()}|${artist.normaliseForMatch()}"
    }

    private fun MediaItem.artistKey(): String =
        mediaMetadata.artist?.toString()?.normaliseForMatch().orEmpty()

    private fun MediaItem.genreKey(): String? =
        mediaMetadata.genre?.toString()?.trim()?.takeIf(String::isNotBlank)?.normaliseForMatch()

    private fun MediaItem.genreName(): String =
        mediaMetadata.genre?.toString()?.trim()?.takeIf(String::isNotBlank) ?: "Genre"

    private fun String.normaliseForMatch(): String = lowercase().filter { it.isLetterOrDigit() }

    private fun MediaItem.playCount(): Int =
        mediaMetadata.extras?.getInt(JellyfinLibraryLoader.EXTRA_PLAY_COUNT, 0) ?: 0

    private fun MediaItem.isFavourite(): Boolean =
        mediaMetadata.extras?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

    private fun MediaItem.lastPlayed(): Long =
        mediaMetadata.extras?.getLong(JellyfinLibraryLoader.EXTRA_LAST_PLAYED, 0L) ?: 0L

    private fun MediaItem.addDate(): Long = mediaMetadata.extras?.getLong("AddDate", 0L) ?: 0L

    /** Round-robin artists so one prolific artist cannot consume an entire personal mix. */
    private fun List<MediaItem>.balancedByArtist(): List<MediaItem> {
        val groups = linkedMapOf<String, ArrayDeque<MediaItem>>()
        forEach { item ->
            val key = item.artistKey().ifBlank { "unknown:${item.mediaId}" }
            groups.getOrPut(key) { ArrayDeque() }.add(item)
        }
        return buildList {
            while (size < MIX_SIZE && groups.isNotEmpty()) {
                val iterator = groups.iterator()
                while (iterator.hasNext() && size < MIX_SIZE) {
                    val entry = iterator.next()
                    entry.value.removeFirstOrNull()?.let(::add)
                    if (entry.value.isEmpty()) iterator.remove()
                }
            }
        }
    }

    private fun List<HomeCard>.withoutNearDuplicates(): List<HomeCard> = buildList {
        this@withoutNearDuplicates.forEach { candidate ->
            val candidateIds = candidate.mediaIds.toSet()
            val isNearDuplicate = any { accepted ->
                val acceptedIds = accepted.mediaIds.toSet()
                val smaller = minOf(candidateIds.size, acceptedIds.size)
                smaller > 0 && candidateIds.intersect(acceptedIds).size.toDouble() / smaller >= 0.90
            }
            if (!isNearDuplicate) add(candidate)
        }
    }

    private fun dailySeed(prefix: String): String {
        val calendar = Calendar.getInstance()
        return "$prefix:${calendar.get(Calendar.YEAR)}:${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun weeklySeed(prefix: String): String {
        val calendar = Calendar.getInstance()
        return "$prefix:${calendar.get(Calendar.YEAR)}:${calendar.get(Calendar.WEEK_OF_YEAR)}"
    }

    private fun List<MediaItem>.stableShuffle(seed: String): List<MediaItem> =
        sortedWith(compareBy<MediaItem> { stableHash(seed, it.mediaId) }.thenBy { it.mediaId })

    private fun stableHash(seed: String, value: String): Long {
        var hash = 0xcbf29ce484222325UL.toLong()
        "$seed\u0000$value".forEach { character ->
            hash = (hash xor character.code.toLong()) * 0x100000001b3L
        }
        return hash
    }
}
