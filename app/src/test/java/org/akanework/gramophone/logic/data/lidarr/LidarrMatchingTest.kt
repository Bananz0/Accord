package org.akanework.gramophone.logic.data.lidarr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LidarrMatchingTest {

    @Test
    fun combinedArtistAndAlbumQueryRanksExactAlbumFirst() {
        val results = listOf(
            album("Halsey X Magnum", "Halsey", "wrong"),
            album("BADLANDS", "Halsey", "right"),
            album("Bedlands: Lullaby Renditions of Halsey Songs", "Sparrow Sleeps", "other"),
        )

        val ranked = LidarrClient.rankAlbums("Halsey Badlands", results)

        assertEquals("right", ranked.first().foreignAlbumId)
    }

    @Test
    fun bareArtistQueryRanksThatArtistsAlbumsAboveTitleMatches() {
        val results = listOf(
            album("Halsey", "LibraH", "title-match"),
            album("Manic", "Halsey", "artist-match"),
        )

        val ranked = LidarrClient.rankAlbums("Halsey", results)

        assertEquals("artist-match", ranked.first().foreignAlbumId)
    }

    @Test
    fun deluxeEditionWinsWithinTheSameAlbumFamily() {
        val results = listOf(
            album("Moonlight", "Ariana Grande", "standard"),
            album("Moonlight (Deluxe Edition)", "Ariana Grande", "deluxe"),
        )

        val ranked = LidarrClient.rankAlbums("Ariana Grande Moonlight", results)

        assertEquals("deluxe", ranked.first().foreignAlbumId)
    }

    @Test
    fun spotifyRequestSelectsAlbumNameNotFirstResultForArtist() {
        val results = listOf(
            album("Halsey X Magnum", "Halsey", "wrong"),
            album("BADLANDS", "Halsey", "right"),
        )

        val match = LidarrRequester.selectMatch("Halsey", "BADLANDS", true, results)

        assertEquals("right", match?.foreignAlbumId)
    }

    @Test
    fun compilationCanMatchByAlbumWhenAlbumArtistDiffers() {
        val compilation = album("Now That's What I Call Music! 100", "Various Artists", "va")

        val match = LidarrRequester.selectMatch(
            "Ariana Grande",
            "Now That's What I Call Music! 100",
            true,
            listOf(compilation),
        )

        assertEquals("va", match?.foreignAlbumId)
    }

    @Test
    fun knownAlbumDoesNotFallBackToUnrelatedAlbumBySameArtist() {
        val unrelated = album("Manic", "Halsey", "wrong")

        val match = LidarrRequester.selectMatch(
            "Halsey",
            "The Great Impersonator",
            true,
            listOf(unrelated),
        )

        assertNull(match)
    }

    private fun album(title: String, artist: String, id: String) = LidarrClient.AlbumResult(
        foreignAlbumId = id,
        title = title,
        artistName = artist,
        foreignArtistId = "artist-$id",
        year = null,
        coverUrl = null,
        alreadyAdded = false,
        lidarrJson = "{}",
    )
}
