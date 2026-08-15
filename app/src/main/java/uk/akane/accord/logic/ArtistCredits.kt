package uk.akane.accord.logic

import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader

/** A consistent interpretation of album ownership and per-track guest credits. */
object ArtistCredits {

    fun primaryArtist(item: MediaItem): String =
        albumArtists(item).firstOrNull()
            ?: trackArtists(item).firstOrNull()
            ?: "(Unknown Artist)"

    fun trackArtists(item: MediaItem): List<String> {
        val extras = item.mediaMetadata.extras
        val explicit = extras
            ?.getStringArrayList(JellyfinLibraryLoader.EXTRA_TRACK_ARTISTS)
            .orEmpty()
        // A credit attached to a Jellyfin artist ID is an atomic server entity, even when its
        // display name contains punctuation. Only parse legacy/fallback display text.
        val structured = extras?.getLongArray(JellyfinLibraryLoader.EXTRA_TRACK_ARTIST_IDS)
            ?.isNotEmpty() == true
        val credits = if (structured) explicit else explicit.flatMap(::split)
        return (credits.ifEmpty { split(item.mediaMetadata.artist?.toString()) })
            .distinctBy { it.normalised() }
    }

    fun albumArtists(item: MediaItem): List<String> {
        val extras = item.mediaMetadata.extras
        val explicit = extras
            ?.getStringArrayList(JellyfinLibraryLoader.EXTRA_ALBUM_ARTISTS)
            .orEmpty()
        val structured = extras?.getLongArray(JellyfinLibraryLoader.EXTRA_ALBUM_ARTIST_IDS)
            ?.isNotEmpty() == true
        val credits = if (structured) explicit else explicit.flatMap(::split)
        return (credits.ifEmpty { split(item.mediaMetadata.albumArtist?.toString()) })
            .distinctBy { it.normalised() }
    }

    fun featuredArtists(item: MediaItem): List<String> {
        val primary = primaryArtist(item).normalised()
        return (albumArtists(item).drop(1) + trackArtists(item)).filter { credit ->
            val key = credit.normalised()
            // The first album artist owns the release. Additional names on either the album or
            // track credit are appearances, not separate composite discography owners.
            key != primary
        }.distinctBy { it.normalised() }
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
