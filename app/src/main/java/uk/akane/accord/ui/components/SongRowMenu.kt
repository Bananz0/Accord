package uk.akane.accord.ui.components

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter
import uk.akane.accord.R

/**
 * The per-row overflow menu on a song.
 *
 * Every song row in the Accord UI draws a menu button that upstream never wires to anything, so
 * downloading a track for offline - which this fork has supported for a while - had no way in once
 * the old screens stopped being reachable.
 *
 * Deliberately a plain [PopupMenu] rather than the Cupertino popup: that one is drawn by the
 * floating panel and anchored against it, which works for the player's own menu but not for a row
 * inside an arbitrary list.
 */
object SongRowMenu {

    fun show(anchor: View, item: MediaItem) {
        val context = anchor.context
        PopupMenu(context, anchor).apply {
            menu.add(0, ID_DOWNLOAD, 0, R.string.song_menu_download)
            menu.add(0, ID_REMOVE_DOWNLOAD, 1, R.string.song_menu_remove_download)
            menu.add(0, ID_FAVOURITE, 2, R.string.song_menu_favourite)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    ID_DOWNLOAD -> {
                        JellyfinDownloadManager.download(context, listOf(item))
                        true
                    }
                    ID_REMOVE_DOWNLOAD -> {
                        JellyfinDownloadManager.remove(context, listOf(item))
                        true
                    }
                    ID_FAVOURITE -> {
                        setFavourite(context, item)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * Favourites are the server's, so this goes straight there rather than to a local list - the
     * same way the player's star does.
     */
    private fun setFavourite(context: Context, item: MediaItem) {
        CoroutineScope(Dispatchers.IO).launch {
            JellyfinReporter(context).setFavourite(item.mediaId, true)
        }
    }

    private const val ID_DOWNLOAD = 1
    private const val ID_REMOVE_DOWNLOAD = 2
    private const val ID_FAVOURITE = 3
}
