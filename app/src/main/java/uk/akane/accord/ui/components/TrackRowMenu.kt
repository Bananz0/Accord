package uk.akane.accord.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.player.FloatingPanelLayout
import uk.akane.accord.ui.fragments.browse.AddToPlaylistFragment
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment
import uk.akane.accord.ui.fragments.browse.ArtistDetailFragment
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.cupertino.popup.showPopupMenuFromAnchor
import uk.akane.accord.logic.dp

/**
 * The menu behind the three dots on a track row.
 *
 * Every list in the app draws that button - the songs list, an album, a playlist, search results -
 * and on all but one of them it did nothing at all. This is the one menu they all share, so a row
 * behaves the same wherever it is seen, with the couple of entries that only make sense somewhere
 * particular added by the screen showing it.
 *
 * Drawn through the floating panel's popup like every other menu in this UI, rather than a stock
 * PopupMenu, so it looks like the rest of the app.
 */
object TrackRowMenu {

    enum class Action {
        PLAY_NEXT,
        ADD_TO_QUEUE,
        ADD_TO_PLAYLIST,
        DOWNLOAD,
        REMOVE_DOWNLOAD,
        GO_TO_ARTIST,
        GO_TO_ALBUM,
        CREATE_STATION,
        TOGGLE_FAVOURITE,
        REMOVE_FROM_PLAYLIST,
    }

    /**
     * @param onRemoveFromPlaylist supplied only by a playlist, which is the one place removing the
     *   row from what is being looked at means anything.
     */
    fun show(
        anchor: View,
        item: MediaItem,
        onRemoveFromPlaylist: (() -> Unit)? = null,
    ) {
        val activity = anchor.context.findMainActivity() ?: return
        val host = activity.findViewById<FloatingPanelLayout>(R.id.floating) ?: return
        val resources = anchor.resources
        anchor.performPressHaptic()

        activity.lifecycleScope.launch {
            val isDownloaded = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.isDownloaded(activity, item)
            }
            if (!anchor.isAttachedToWindow) return@launch
            val isFavourite = item.mediaMetadata.extras
                ?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

            val entries = PopupHelper.PopupMenuBuilder()
            .addMenuEntry(resources, R.drawable.ic_master_play, R.string.play_next, Action.PLAY_NEXT)
            .addMenuEntry(
                resources, R.drawable.ic_bulletin_select, R.string.add_to_queue, Action.ADD_TO_QUEUE
            )
            .addSpacer()
            .addMenuEntry(
                resources, R.drawable.ic_playlist, R.string.popup_add_to_a_playlist,
                Action.ADD_TO_PLAYLIST
            )
            .apply {
                if (isDownloaded) {
                    addDestructiveMenuEntry(
                        resources, R.drawable.ic_trash, R.string.collection_remove_from_device,
                        Action.REMOVE_DOWNLOAD
                    )
                } else {
                    addMenuEntry(
                        resources, R.drawable.ic_download, R.string.song_menu_download,
                        Action.DOWNLOAD
                    )
                }
            }
            .addSpacer()
            .addMenuEntry(
                resources, R.drawable.ic_person_small, R.string.go_to_artist, Action.GO_TO_ARTIST
            )
            .addMenuEntry(resources, R.drawable.ic_album, R.string.go_to_album, Action.GO_TO_ALBUM)
            .addMenuEntry(
                resources, R.drawable.ic_airplay_radio, R.string.popup_create_station,
                Action.CREATE_STATION
            )
            .addSpacer()
            .addMenuEntry(
                resources,
                R.drawable.ic_favourite,
                if (isFavourite) R.string.popup_undo_favorite else R.string.popup_favorite,
                Action.TOGGLE_FAVOURITE
            )
            .apply {
                if (onRemoveFromPlaylist != null) {
                    addDestructiveMenuEntry(
                        resources, R.drawable.ic_trash, R.string.remove_from_playlist,
                        Action.REMOVE_FROM_PLAYLIST
                    )
                }
            }
            .build()

            host.showPopupMenuFromAnchor(
                entries = entries,
                anchorView = anchor,
                showBelow = true,
                alignToRight = true,
                anchorOffsetY = 12.dp.px.toInt(),
                belowGapPx = 8.dp.px.toInt(),
                backgroundView = activity.findViewById(R.id.shrink_container),
                onEntryClick = { entry ->
                    handle(activity, entry, item, isFavourite, onRemoveFromPlaylist)
                },
            )
        }
    }

    private fun handle(
        activity: MainActivity,
        entry: PopupHelper.PopupEntry,
        item: MediaItem,
        isFavourite: Boolean,
        onRemoveFromPlaylist: (() -> Unit)?,
    ) {
        when ((entry as? PopupHelper.MenuEntry)?.payload as? Action) {
            Action.PLAY_NEXT -> {
                val player = activity.getPlayer() ?: return
                if (player.mediaItemCount == 0) {
                    player.setMediaItems(listOf(item), 0, C.TIME_UNSET)
                    player.prepare()
                    player.play()
                } else {
                    player.addMediaItem(player.currentMediaItemIndex + 1, item)
                }
                toast(activity, R.string.queued_next)
            }

            Action.ADD_TO_QUEUE -> {
                val player = activity.getPlayer() ?: return
                if (player.mediaItemCount == 0) {
                    player.setMediaItems(listOf(item), 0, C.TIME_UNSET)
                    player.prepare()
                    player.play()
                } else {
                    player.addMediaItem(item)
                }
                toast(activity, R.string.queued)
            }

            Action.ADD_TO_PLAYLIST -> {
                activity.collapseNowPlaying()
                activity.fragmentSwitcherView.addFragmentToCurrentStack(
                    AddToPlaylistFragment.newInstance(listOf(item.mediaId))
                )
            }

            Action.DOWNLOAD -> {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    JellyfinDownloadManager.download(activity, listOf(item))
                }
                toast(activity, R.string.download_started)
            }

            Action.REMOVE_DOWNLOAD -> {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    JellyfinDownloadManager.remove(activity, listOf(item))
                }
                toast(activity, R.string.download_removed)
            }

            Action.GO_TO_ARTIST -> {
                val artist = item.mediaMetadata.artist?.toString()
                if (artist.isNullOrBlank()) {
                    toast(activity, R.string.go_to_artist_missing)
                } else {
                    activity.collapseNowPlaying()
                    activity.fragmentSwitcherView.addFragmentToCurrentStack(
                        ArtistDetailFragment.newInstance(artist)
                    )
                }
            }

            Action.GO_TO_ALBUM -> {
                val album = item.mediaMetadata.albumTitle?.toString()
                if (album.isNullOrBlank()) {
                    toast(activity, R.string.go_to_album_missing)
                } else {
                    activity.collapseNowPlaying()
                    val artist = item.mediaMetadata.albumArtist?.toString()
                        ?: item.mediaMetadata.artist?.toString().orEmpty()
                    activity.fragmentSwitcherView.addFragmentToCurrentStack(
                        AlbumDetailFragment.newInstance(album, artist)
                    )
                }
            }

            Action.CREATE_STATION -> activity.openStationFor(item)

            Action.TOGGLE_FAVOURITE -> activity.lifecycleScope.launch(Dispatchers.IO) {
                JellyfinReporter(activity.applicationContext)
                    .setFavourite(item.mediaId, !isFavourite)
            }

            Action.REMOVE_FROM_PLAYLIST -> onRemoveFromPlaylist?.invoke()

            null -> Unit
        }
    }

    private fun toast(activity: MainActivity, message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /** Rows live in themed wrappers, so the activity has to be unwrapped rather than cast to. */
    private fun Context.findMainActivity(): MainActivity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is MainActivity) return context
            context = context.baseContext
        }
        return null
    }

}
