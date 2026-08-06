package org.akanework.gramophone.ui.home


import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import org.akanework.gramophone.ui.LibraryViewModel
import java.util.Calendar
import kotlin.random.Random

/**
 * A titled row of cards on the home screen.
 *
 * The screen is a list of these rather than a fixed pair of rows, so what it shows can follow what
 * the library actually contains - somebody who has never favourited anything gets a different set
 * of rows to somebody with years of play counts, instead of an empty shelf.
 */
data class HomeSection(
    val id: String,
    val title: String,
    /** The line under the title, naming what is in the row. Spotify uses this heavily. */
    val subtitle: String? = null,
    val cards: List<HomeCard>,
)

data class HomeCard(
    val title: String,
    val subtitle: String?,
    val cover: Uri?,
    /** Tapping plays these, starting at [startIndex]. */
    val songs: List<MediaItem>,
    val startIndex: Int = 0,
)

/**
 * Builds the home feed from the library.
 *
 * Everything here is pure computation over the already-loaded library, so it is cheap enough to
 * rebuild whenever the library changes - except [similarArtistSection], which needs the network and
 * is fetched separately and appended when it arrives.
 */
object HomeFeed {

    private const val ROW_SIZE = 12
    private const val MIX_SIZE = 50

    /**
     * The only thing the feed needs from an artist. Stated here so the feed does not have to pick
     * between this app's library model and libPhonograph's - the two describe the same thing with
     * no common supertype, and the feed is now built for both the old screens and the Accord ones.
     */
    data class ArtistInput(val title: String?, val songList: List<MediaItem>)

    fun build(
        context: Context,
        library: List<MediaItem>,
        artists: List<ArtistInput>
    ): List<HomeSection> {
        if (library.isEmpty()) return emptyList()

        return buildList {
            jumpBackIn(context, library)?.let(::add)
            dailyShuffle(context, library)?.let(::add)
            mostPlayed(context, library)?.let(::add)
            topMixes(context, artists)?.let(::add)
            daylist(context, library)?.let(::add)
            recentlyAdded(context, library)?.let(::add)
            favourites(context, library)?.let(::add)
        }
    }

    /**
     * A shuffle of the whole library that holds still for the day.
     *
     * Seeded by the date, so it is the same set all day and a different one tomorrow - a row that
     * reshuffled on every glance would not be worth returning to.
     */
    private fun dailyShuffle(context: Context, library: List<MediaItem>): HomeSection? {
        if (library.size < ROW_SIZE) return null
        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR)
        val shown = library.shuffled(Random(seed)).take(ROW_SIZE)
        return HomeSection(
            id = "daily_shuffle",
            title = context.getString(R.string.mix_daily_shuffle),
            subtitle = context.getString(R.string.mix_daily_shuffle_subtitle),
            cards = shown.mapIndexed { index, item ->
                HomeCard(
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = item.mediaMetadata.artist?.toString(),
                    cover = item.mediaMetadata.artworkUri,
                    songs = shown,
                    startIndex = index,
                )
            }
        )
    }

    /** Straight play counts, which on Jellyfin are counted across every client, not just this one. */
    private fun mostPlayed(context: Context, library: List<MediaItem>): HomeSection? {
        val played = library.filter { it.playCount() > 0 }
            .sortedByDescending { it.playCount() }
            .take(ROW_SIZE)
        if (played.isEmpty()) return null
        return HomeSection(
            id = "most_played",
            title = context.getString(R.string.mix_most_played),
            cards = played.mapIndexed { index, item ->
                HomeCard(
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = item.mediaMetadata.artist?.toString(),
                    cover = item.mediaMetadata.artworkUri,
                    songs = played,
                    startIndex = index,
                )
            }
        )
    }

    /**
     * What was played most recently, newest first.
     *
     * Uses Jellyfin's own last-played timestamps, so it reflects listening on every client rather
     * than only this phone.
     */
    private fun jumpBackIn(context: Context, library: List<MediaItem>): HomeSection? {
        val recent = library.filter { it.lastPlayed() > 0 }
            .sortedByDescending { it.lastPlayed() }
            .distinctBy { it.mediaMetadata.albumTitle?.toString() ?: it.mediaId }
            .take(ROW_SIZE)
        if (recent.isEmpty()) return null
        return HomeSection(
            id = "jump_back_in",
            title = context.getString(R.string.home_jump_back_in),
            cards = recent.mapIndexed { index, item ->
                HomeCard(
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = item.mediaMetadata.artist?.toString(),
                    cover = item.mediaMetadata.artworkUri,
                    songs = recent,
                    startIndex = index,
                )
            }
        )
    }

    /**
     * One mix per artist the user actually listens to, in the shape of Spotify's "Your top mixes".
     *
     * Ranked by total play count rather than track count, so a heavily played EP outranks an
     * untouched discography that happens to be large.
     */
    private fun topMixes(context: Context, allArtists: List<ArtistInput>): HomeSection? {
        val artists = allArtists
            .filter { artist -> artist.songList.any { it.playCount() > 0 } }
            .sortedByDescending { artist -> artist.songList.sumOf { it.playCount() } }
            .take(ROW_SIZE)
        if (artists.isEmpty()) return null
        return HomeSection(
            id = "top_mixes",
            title = context.getString(R.string.home_top_mixes),
            subtitle = context.getString(R.string.home_top_mixes_subtitle),
            cards = artists.map { artist ->
                val mix = artist.songList.shuffled().take(MIX_SIZE)
                HomeCard(
                    title = context.getString(
                        R.string.home_artist_mix,
                        artist.title ?: context.getString(R.string.unknown_artist)
                    ),
                    subtitle = artist.songList.firstOrNull()?.mediaMetadata?.albumTitle?.toString(),
                    cover = artist.songList.firstOrNull()?.mediaMetadata?.artworkUri,
                    songs = mix,
                )
            }
        )
    }

    /**
     * A time-of-day mix, in the spirit of Spotify's daylist.
     *
     * Seeded by the date and the part of the day, so it holds still for a few hours and then turns
     * over - a mix that reshuffled on every glance would not feel like a playlist at all.
     */
    private fun daylist(context: Context, library: List<MediaItem>): HomeSection? {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val slot = hour / 6
        val seed = calendar.get(Calendar.YEAR) * 10_000L +
                calendar.get(Calendar.DAY_OF_YEAR) * 10L + slot
        val random = Random(seed)

        // Anchored on one genre so the row has a character rather than being a plain shuffle.
        // Jellyfin joins a track's genres into one field, so they have to be split back apart -
        // otherwise the card is titled "Alternative Pop;Electronic;Electropop" and the mix only
        // contains tracks tagged with that exact combination.
        val genresBySong = library.associateWith { it.genres() }
        val genres = genresBySong.values.flatten().distinct()
        if (genres.isEmpty()) return null

        val cards = genres.shuffled(random).take(ROW_SIZE).mapNotNull { genre ->
            val songs = library.filter { genre in genresBySong.getValue(it) }
                .shuffled(random)
                .take(MIX_SIZE)
            if (songs.size < 5) return@mapNotNull null
            HomeCard(
                title = genre,
                subtitle = context.resources.getQuantityString(
                    R.plurals.songs, songs.size, songs.size
                ),
                cover = songs.firstOrNull()?.mediaMetadata?.artworkUri,
                songs = songs,
            )
        }
        if (cards.isEmpty()) return null
        return HomeSection(
            id = "daylist",
            title = context.getString(R.string.home_daylist, timeOfDayLabel(context, hour)),
            subtitle = context.getString(R.string.home_daylist_subtitle),
            cards = cards,
        )
    }

    private fun timeOfDayLabel(context: Context, hour: Int) = context.getString(
        when (hour) {
            in 5..11 -> R.string.time_of_day_morning
            in 12..16 -> R.string.time_of_day_afternoon
            in 17..21 -> R.string.time_of_day_evening
            else -> R.string.time_of_day_night
        }
    )

    private fun recentlyAdded(context: Context, library: List<MediaItem>): HomeSection? {
        val recent = library.sortedByDescending { it.addDate() }
            .distinctBy { it.mediaMetadata.albumTitle?.toString() ?: it.mediaId }
            .take(ROW_SIZE)
        if (recent.isEmpty()) return null
        return HomeSection(
            id = "recently_added",
            title = context.getString(R.string.mix_recently_added),
            cards = recent.mapIndexed { index, item ->
                HomeCard(
                    title = item.mediaMetadata.albumTitle?.toString()
                        ?: item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = item.mediaMetadata.artist?.toString(),
                    cover = item.mediaMetadata.artworkUri,
                    songs = recent,
                    startIndex = index,
                )
            }
        )
    }

    private fun favourites(context: Context, library: List<MediaItem>): HomeSection? {
        val favourites = library.filter { it.isFavourite() }
        if (favourites.size < 3) return null
        val shown = favourites.shuffled().take(ROW_SIZE)
        return HomeSection(
            id = "favourites",
            title = context.getString(R.string.mix_favourites),
            cards = shown.mapIndexed { index, item ->
                HomeCard(
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = item.mediaMetadata.artist?.toString(),
                    cover = item.mediaMetadata.artworkUri,
                    songs = shown,
                    startIndex = index,
                )
            }
        )
    }

    /**
     * "For fans of X" - artists Last.fm considers similar to a favourite of the user's, narrowed to
     * those actually present in the library.
     *
     * Recommendations from play counts alone can only ever surface what is already listened to.
     * Last.fm brings in outside knowledge of what sounds alike, which is the only way this can point
     * at a corner of the library the user has never opened.
     *
     * Suspends on a network call. Returns null when Last.fm is unconfigured, unreachable, or none of
     * the similar artists are in the library - all ordinary outcomes, not errors.
     */
    suspend fun similarArtistSection(
        context: Context,
        artists: List<ArtistInput>,
    ): HomeSection? {
        val store = LastFmCredentialStore(context)
        if (!store.hasApplicationCredentials()) return null

        if (artists.isEmpty()) return null

        val seed = artists
            .filter { artist -> artist.songList.any { it.playCount() > 0 } }
            .maxByOrNull { artist -> artist.songList.sumOf { it.playCount() } }
            ?: return null
        val seedName = seed.title ?: return null

        val similar = try {
            LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl)
                .getSimilarArtists(seedName)
        } catch (e: Exception) {
            return null
        }
        if (similar.isEmpty()) return null

        // Match on a normalised name: Last.fm's spelling and the tags on the user's files agree
        // often enough, but not on case or punctuation.
        val byName = artists.associateBy { it.title?.normaliseForMatch().orEmpty() }
        val matches = similar.mapNotNull { byName[it.normaliseForMatch()] }
            .filter { it.title != seed.title }
            .distinctBy { it.title }
            .take(ROW_SIZE)
        if (matches.isEmpty()) return null

        return HomeSection(
            id = "for_fans_of",
            title = context.getString(R.string.home_for_fans_of, seedName),
            subtitle = matches.mapNotNull { it.title }.take(4).joinToString(", "),
            cards = matches.map { artist ->
                val mix = artist.songList.shuffled().take(MIX_SIZE)
                HomeCard(
                    title = artist.title ?: context.getString(R.string.unknown_artist),
                    subtitle = context.resources.getQuantityString(
                        R.plurals.songs, artist.songList.size, artist.songList.size
                    ),
                    cover = artist.songList.firstOrNull()?.mediaMetadata?.artworkUri,
                    songs = mix,
                )
            }
        )
    }

    private fun String.normaliseForMatch(): String =
        lowercase().filter { it.isLetterOrDigit() }

    /** A track's genres as separate values. Jellyfin delivers them as one delimited string. */
    private fun MediaItem.genres(): List<String> =
        mediaMetadata.genre?.toString()
            ?.split(';', '/', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

    private fun MediaItem.playCount(): Int =
        mediaMetadata.extras?.getInt(JellyfinLibraryLoader.EXTRA_PLAY_COUNT, 0) ?: 0

    private fun MediaItem.isFavourite(): Boolean =
        mediaMetadata.extras?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

    private fun MediaItem.lastPlayed(): Long =
        mediaMetadata.extras?.getLong(JellyfinLibraryLoader.EXTRA_LAST_PLAYED, 0L) ?: 0L

    private fun MediaItem.addDate(): Long =
        mediaMetadata.extras?.getLong("AddDate", 0L) ?: 0L
}
