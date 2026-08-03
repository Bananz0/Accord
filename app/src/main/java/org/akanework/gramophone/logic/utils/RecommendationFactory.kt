package org.akanework.gramophone.logic.utils

import android.content.Context
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.ui.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecommendationFactory(
    context: Context,
    private val libraryViewModel: LibraryViewModel
) {

    enum class RecommendationType {
        HISTORY,
        FAVORITE,
        RECENTLY_ADDED,
        GENRE,
        ARTIST,
        NONE
    }

    private val sharedPreferences = context.getSharedPreferences("recommendation", Context.MODE_PRIVATE)

    private companion object {
        /** Songs per recommendation carousel, matching what the home screen lays out. */
        const val MIN_SONGS = 4
    }

    private fun getCurrentDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun isArtistTypeAvailable(): Boolean {
        return libraryViewModel.artistItemList.value?.any { it.songList.size >= 4 && it.title != null } == true
    }

    private fun isGenreTypeAvailable(): Boolean {
        return libraryViewModel.genreItemList.value?.any { it.songList.size >= 4 && it.title != null } == true
    }

    interface RecommendFetcher {
        fun getRecommendation(): Pair<Int, List<Long>>
    }

    inner class GenreFetcher: RecommendFetcher {
        override fun getRecommendation(): Pair<Int, List<Long>> {
            val genreList = libraryViewModel.genreItemList.value ?: return Pair(0, emptyList())
            val index = genreList.indices.random()
            val genre = genreList[index]
            val genreIndexList = genre.songList.shuffled().take(4).map { it.mediaId.toLong() }
            return Pair(index, genreIndexList)
        }
    }

    inner class ArtistFetcher: RecommendFetcher {
        override fun getRecommendation(): Pair<Int, List<Long>> {
            val artistList = libraryViewModel.artistItemList.value ?: return Pair(0, emptyList())
            val index = artistList.indices.random()
            val artist = artistList[index]
            val artistIndexList = artist.songList.shuffled().take(4).map { it.mediaId.toLong() }
            return Pair(index, artistIndexList)
        }
    }

    /**
     * Picks the artist the user actually listens to most, rather than one at random.
     *
     * Play counts come from Jellyfin, so this reflects listening across every client the user has
     * ever used - which is the whole point of a server-backed library, and something a local-only
     * player could never know on a freshly installed device.
     */
    inner class HistoryFetcher : RecommendFetcher {
        override fun getRecommendation(): Pair<Int, List<Long>> {
            val artistList = libraryViewModel.artistItemList.value ?: return Pair(0, emptyList())
            val index = artistList.indices
                .filter { artistList[it].songList.size >= MIN_SONGS && artistList[it].title != null }
                .maxByOrNull { i -> artistList[i].songList.sumOf { it.playCount().toLong() } }
                ?: return Pair(0, emptyList())
            // Favour the most played tracks, but keep it from being identical every single day.
            val songs = artistList[index].songList
                .sortedByDescending { it.playCount() }
                .take(MIN_SONGS * 3)
                .shuffled()
                .take(MIN_SONGS)
                .map { it.mediaId.toLong() }
            return Pair(index, songs)
        }
    }

    /** Songs starred on the server, which is a far stronger signal than a random pick. */
    inner class FavoriteFetcher : RecommendFetcher {
        override fun getRecommendation(): Pair<Int, List<Long>> {
            val artistList = libraryViewModel.artistItemList.value ?: return Pair(0, emptyList())
            val index = artistList.indices
                .filter { artistList[it].title != null }
                .maxByOrNull { i -> artistList[i].songList.count { it.isFavourite() } }
                ?: return Pair(0, emptyList())
            val favourites = artistList[index].songList.filter { it.isFavourite() }
            if (favourites.size < MIN_SONGS) return Pair(0, emptyList())
            return Pair(index, favourites.shuffled().take(MIN_SONGS).map { it.mediaId.toLong() })
        }
    }

    private fun MediaItem.playCount(): Int =
        mediaMetadata.extras?.getInt(JellyfinLibraryLoader.EXTRA_PLAY_COUNT, 0) ?: 0

    private fun MediaItem.isFavourite(): Boolean =
        mediaMetadata.extras?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

    private fun hasListeningHistory(): Boolean =
        libraryViewModel.mediaItemList.value?.any { it.playCount() > 0 } == true

    private fun hasFavourites(): Boolean =
        (libraryViewModel.mediaItemList.value?.count { it.isFavourite() } ?: 0) >= MIN_SONGS

    interface TitleFetcher {
        fun getTitle(): String
    }

    class GenreTitleFetcher(
        private val recommendList: RecommendList,
        private val libraryViewModel: LibraryViewModel
    ): TitleFetcher {
        override fun getTitle(): String =
            libraryViewModel.genreItemList.value!![recommendList.recommendationObjectId].title!!
    }

    class ArtistTitleFetcher(
        private val recommendList: RecommendList,
        private val libraryViewModel: LibraryViewModel
    ): TitleFetcher {
        override fun getTitle(): String =
            libraryViewModel.artistItemList.value!![recommendList.recommendationObjectId].title!!
    }

    data class RawRecommendList (
        val recommendationType: RecommendationType,
        val recommendationObjectId: Int,
        val recommendationList: List<Long>
    )

    data class RecommendList(
        val recommendationType: RecommendationType,
        val recommendationObjectId: Int,
        val recommendationList: List<MediaItem>
    ) {
        fun getTitle(libraryViewModel: LibraryViewModel) =
            when (recommendationType) {
                // HISTORY and FAVORITE are artist-scoped too, so they title the same way. Leaving
                // them out sent them to the else branch, which threw.
                RecommendationType.ARTIST,
                RecommendationType.HISTORY,
                RecommendationType.FAVORITE -> {
                    ArtistTitleFetcher(this, libraryViewModel)
                }
                RecommendationType.GENRE -> {
                    GenreTitleFetcher(this, libraryViewModel)
                }
                RecommendationType.NONE -> {
                    object : TitleFetcher {
                        override fun getTitle(): String = ""
                    }
                }
                else -> {
                    throw IllegalArgumentException("Invalid recommendation type")
                }
            }.getTitle()
    }

    private fun reInstanceRecommendList(rawRecommendList: RawRecommendList): RecommendList {
        val objectList = when (rawRecommendList.recommendationType) {
            RecommendationType.GENRE -> libraryViewModel.genreItemList.value
            // HISTORY and FAVORITE also index into the artist list.
            RecommendationType.ARTIST,
            RecommendationType.HISTORY,
            RecommendationType.FAVORITE -> libraryViewModel.artistItemList.value

            else -> null
        }?.getOrNull(rawRecommendList.recommendationObjectId)?.songList ?: emptyList()

        val finalMediaItemList = objectList.filter { it.mediaId.toLong() in rawRecommendList.recommendationList }

        return RecommendList(
            rawRecommendList.recommendationType,
            rawRecommendList.recommendationObjectId,
            finalMediaItemList
        )
    }

    fun fetchRecommendList(): RecommendList {
        val savedDate = sharedPreferences.getString("date", null)
        if (getCurrentDateString() == savedDate) {
            val recommendationList = sharedPreferences.getString("recommendation", null)?.split(",")?.map { it.toLong() } ?: emptyList()
            return reInstanceRecommendList(
                RawRecommendList(
                    RecommendationType.valueOf(sharedPreferences.getString("type", "NONE")!!),
                    sharedPreferences.getInt("id", 0),
                    recommendationList
                )
            )
        }

        val genreTypeAvailable = isGenreTypeAvailable()
        val artistTypeAvailable = isArtistTypeAvailable()
        if (!genreTypeAvailable && !artistTypeAvailable) {
            return RecommendList(
                RecommendationType.NONE,
                0,
                emptyList()
            )
        }

        val availableList = mutableListOf<RecommendationType>().apply {
            if (genreTypeAvailable) add(RecommendationType.GENRE)
            if (artistTypeAvailable) add(RecommendationType.ARTIST)
            // Weighted twice: a suggestion drawn from what the user actually plays or has starred
            // on the server beats a random genre, so bias the daily pick towards those once
            // there is enough server-side data to make them meaningful.
            if (hasListeningHistory()) {
                add(RecommendationType.HISTORY)
                add(RecommendationType.HISTORY)
            }
            if (hasFavourites()) {
                add(RecommendationType.FAVORITE)
                add(RecommendationType.FAVORITE)
            }
        }

        val recommendationType = availableList.random()
        val fetcher = when (recommendationType) {
            RecommendationType.GENRE -> GenreFetcher()
            RecommendationType.ARTIST -> ArtistFetcher()
            RecommendationType.HISTORY -> HistoryFetcher()
            RecommendationType.FAVORITE -> FavoriteFetcher()
            else -> null
        } ?: run {
            return RecommendList(
                RecommendationType.NONE,
                0,
                emptyList()
            )
        }
        val recommendationList = fetcher.getRecommendation()

        sharedPreferences.edit().apply {
            putString("date", getCurrentDateString())
            putString("type", recommendationType.name)
            putString("recommendation", recommendationList.second.joinToString(","))
            putInt("id", recommendationList.first)
            apply()
        }

        return reInstanceRecommendList(
            RawRecommendList(
                recommendationType,
                recommendationList.first,
                recommendationList.second
            )
        )
    }
}
