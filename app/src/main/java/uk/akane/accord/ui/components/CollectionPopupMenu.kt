package uk.akane.accord.ui.components

import android.content.res.Resources
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.player.PlayerMenuActions
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.accord.ui.fragments.browse.AddToPlaylistFragment
import uk.akane.accord.ui.fragments.browse.StationDetailFragment
import kotlin.random.Random

/**
 * The three-dot menu on a screen that *is* a collection - an album, a playlist, an artist, a station.
 *
 * Those screens were falling through to the general screen menu, which offers to refresh the library
 * and open settings: true of every screen and useful on none of these. What a listener wants here is
 * to do something with the thing they are looking at.
 */
object CollectionPopupMenu {

    enum class Action {
        PLAY, SHUFFLE, ADD_TO_PLAYLIST, GO_TO_ARTIST, GO_TO_ALBUM,
        RENAME, CHANGE_PICTURE, DELETE,
    }

    /** The action an entry carries, for screens that handle some of them themselves. */
    fun actionOf(entry: PopupHelper.PopupEntry): Action? =
        (entry as? PopupHelper.MenuEntry)?.payload as? Action

    /**
     * @param withArtist whether "Go to Artist" is worth offering - it is not on the artist's own page.
     * @param withAlbum likewise for "Go to Album", which only means something on a mixed collection.
     */
    fun build(
        resources: Resources,
        withArtist: Boolean = true,
        withAlbum: Boolean = false,
        withPlaylistManagement: Boolean = false,
    ): PopupHelper.PopupEntries =
        PopupHelper.PopupMenuBuilder()
            .addMenuEntry(resources, R.drawable.ic_master_play, R.string.play, Action.PLAY)
            .addMenuEntry(resources, R.drawable.ic_shuffle, R.string.shuffle, Action.SHUFFLE)
            .addSpacer()
            .addMenuEntry(
                resources, R.drawable.ic_playlist, R.string.popup_add_to_a_playlist,
                Action.ADD_TO_PLAYLIST
            )
            .apply {
                if (withArtist) {
                    addMenuEntry(
                        resources, R.drawable.ic_person_small, R.string.go_to_artist,
                        Action.GO_TO_ARTIST
                    )
                }
                if (withAlbum) {
                    addMenuEntry(
                        resources, R.drawable.ic_album, R.string.go_to_album, Action.GO_TO_ALBUM
                    )
                }
                // Only a playlist the user made can be renamed, re-covered or deleted.
                if (withPlaylistManagement) {
                    addSpacer()
                    addMenuEntry(
                        resources, R.drawable.ic_edit, R.string.playlist_rename, Action.RENAME
                    )
                    addMenuEntry(
                        resources, R.drawable.ic_album, R.string.playlist_change_picture,
                        Action.CHANGE_PICTURE
                    )
                    addDestructiveMenuEntry(
                        resources, R.drawable.ic_trash, R.string.playlist_delete, Action.DELETE
                    )
                }
            }
            .build()

    /**
     * @param tracks what the screen is showing, in the order it shows them.
     * @param title the collection's name, for anything opened from here.
     */
    fun handle(
        activity: MainActivity,
        entry: PopupHelper.PopupEntry,
        tracks: List<MediaItem>,
        title: String,
    ) {
        if (tracks.isEmpty()) return
        when ((entry as? PopupHelper.MenuEntry)?.payload as? Action) {
            Action.PLAY -> play(activity, tracks)
            Action.SHUFFLE -> play(activity, tracks.shuffled(Random(title.hashCode())))
            Action.ADD_TO_PLAYLIST -> {
                activity.collapseNowPlaying()
                activity.fragmentSwitcherView.addFragmentToCurrentStack(
                    AddToPlaylistFragment.newInstance(tracks.map { it.mediaId })
                )
            }
            // Reuses the track-list screen rather than a dedicated artist page: what the user asked
            // for is "everything by them", and that is what this shows.
            Action.GO_TO_ARTIST -> openBy(activity, tracks) { it.mediaMetadata.artist?.toString() }
            Action.GO_TO_ALBUM -> openBy(activity, tracks) { it.mediaMetadata.albumTitle?.toString() }
            // Handled by the playlist screen itself, which owns the file being renamed or deleted.
            Action.RENAME, Action.CHANGE_PICTURE, Action.DELETE -> Unit
            null -> Unit
        }
    }

    private fun play(activity: MainActivity, tracks: List<MediaItem>) {
        activity.getPlayer()?.apply {
            setMediaItems(tracks, 0, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    /** Opens everything in the library sharing the first track's artist, or album. */
    private fun openBy(
        activity: MainActivity,
        tracks: List<MediaItem>,
        key: (MediaItem) -> String?,
    ) {
        val wanted = key(tracks.first()) ?: return
        activity.collapseNowPlaying()
        activity.openMatchingTracks(wanted, key)
    }
}
