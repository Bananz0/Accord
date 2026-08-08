package uk.akane.accord.logic

import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader

/** A consistent interpretation of album ownership and per-track guest credits. */
object ArtistCredits {

    fun primaryArtist(item: MediaItem): String =
        split(item.mediaMetadata.albumArtist?.toString()).firstOrNull()
            ?: trackArtists(item).firstOrNull()
            ?: "(Unknown Artist)"

    fun trackArtists(item: MediaItem): List<String> {
        val explicit = item.mediaMetadata.extras
            ?.getStringArrayList(JellyfinLibraryLoader.EXTRA_TRACK_ARTISTS)
            .orEmpty()
            // Some Jellyfin libraries return one artist-array entry containing a full
            // semicolon-delimited credit ("YG; Buddy"). Split every entry again instead of
            // assuming the server already normalised it.
            .flatMap(::split)
        return (explicit.ifEmpty { split(item.mediaMetadata.artist?.toString()) })
            .distinctBy { it.normalised() }
    }

    fun featuredArtists(item: MediaItem): List<String> {
        val primary = primaryArtist(item).normalised()
        return trackArtists(item).filter { credit ->
            val key = credit.normalised()
            // The first album artist owns the release. Additional names on either the album or
            // track credit are appearances, not separate composite discography owners.
            key != primary
        }
    }

    fun isPrimaryArtist(item: MediaItem, artist: String): Boolean =
        primaryArtist(item).equals(artist, ignoreCase = true)

    fun isFeaturedArtist(item: MediaItem, artist: String): Boolean =
        featuredArtists(item).any { it.equals(artist, ignoreCase = true) }

    private fun split(value: String?): List<String> {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return emptyList()
        return text
            .replace(Regex("\\s+(?:feat\\.?|featuring|ft\\.?)\\s+", RegexOption.IGNORE_CASE), ";")
            .split(';')
            .map { it.trim().trim(',', '·') }
            .filter(String::isNotBlank)
            .distinctBy { it.normalised() }
    }

    private fun String.normalised(): String =
        lowercase().filter { it.isLetterOrDigit() }
}
