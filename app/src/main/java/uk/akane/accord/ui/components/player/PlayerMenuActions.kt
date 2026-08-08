package uk.akane.accord.ui.components.player

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter
import org.akanework.gramophone.logic.data.library.songListSnapshot
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.fragments.browse.AddToPlaylistFragment
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment
import uk.akane.accord.ui.fragments.browse.ViewCreditsFragment

/**
 * What the player's overflow menu actually does.
 *
 * Upstream builds the menu and shows it, but wires no entry to anything - every item was decoration.
 * The menu is shown from two places (the full player and the screen-level bar), both acting on the
 * track that is playing, so the behaviour lives here rather than being written twice.
 */
object PlayerMenuActions {

    /** Identifies an entry independently of its label, which changes with state and language. */
    enum class Action {
        VIEW_CREDITS,
        DOWNLOAD,
        REMOVE_DOWNLOAD,
        ADD_TO_PLAYLIST,
        SHARE_SONG,
        SHARE_LYRICS,
        GO_TO_ALBUM,
        CREATE_STATION,
        TOGGLE_FAVOURITE,
    }

    fun handle(activity: MainActivity, action: Action) {
        val item = activity.getPlayer()?.currentMediaItem
        if (item == null) {
            // Every entry is about the playing track, so with nothing playing there is nothing to
            // act on; silently doing nothing would read as the menu being broken.
            toast(activity, activity.getString(R.string.no_song_playing))
            return
        }
        when (action) {
            Action.VIEW_CREDITS -> openCredits(activity, item)
            Action.DOWNLOAD -> {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    JellyfinDownloadManager.download(activity, listOf(item))
                }
                toast(activity, activity.getString(R.string.download_started))
            }
            Action.REMOVE_DOWNLOAD -> {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    JellyfinDownloadManager.remove(activity, listOf(item))
                }
                toast(activity, activity.getString(R.string.download_removed))
            }
            Action.ADD_TO_PLAYLIST -> open(activity) {
                AddToPlaylistFragment.newInstance(listOf(item.mediaId))
            }
            Action.SHARE_SONG -> shareSong(activity, item)
            Action.SHARE_LYRICS -> shareLyrics(activity, item)
            Action.GO_TO_ALBUM -> goToAlbum(activity, item)
            Action.CREATE_STATION -> activity.openStationFor(item)
            Action.TOGGLE_FAVOURITE -> toggleFavourite(activity, item)
        }
    }

    /** Whether the server has this track starred, which decides the menu's last label. */
    fun isFavourite(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

    private fun openCredits(activity: MainActivity, item: MediaItem) {
        val metadata = item.mediaMetadata
        open(activity) {
            ViewCreditsFragment.newInstance(
                mediaId = item.mediaId,
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString(),
                album = metadata.albumTitle?.toString(),
                artworkUri = metadata.artworkUri?.toString(),
            )
        }
    }

    /**
     * Pushes a screen and gets the player out of its way.
     *
     * The full player is a panel drawn over the whole navigation stack, so a screen opened from its
     * menu lands behind it and looks as though the tap did nothing.
     */
    private fun open(activity: MainActivity, fragment: () -> Fragment) {
        activity.findViewById<FloatingPanelLayout>(R.id.floating)?.collapse()
        activity.fragmentSwitcherView.addFragmentToCurrentStack(fragment())
    }

    private fun shareSong(activity: MainActivity, item: MediaItem) {
        val metadata = item.mediaMetadata
        val text = activity.getString(
            R.string.share_song_text,
            metadata.title?.toString().orEmpty(),
            metadata.artist?.toString().orEmpty()
        )
        // Deliberately text, not the file or its URL: the stream URL carries this user's access
        // token, and sharing it would hand out their server.
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                activity.getString(R.string.popup_share_song)
            )
        )
    }

    private fun shareLyrics(activity: MainActivity, item: MediaItem) {
        val controller = activity.getPlayer() ?: return
        activity.lifecycleScope.launch {
            // Same split as the player's own lyrics fetch: the command must be sent from the thread
            // the controller was built on, and the reply has to be waited for off it.
            val lines = runCatching {
                val future = controller.sendCustomCommand(
                    SessionCommand(GramophonePlaybackService.SERVICE_GET_LYRICS, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                withContext(Dispatchers.IO) {
                    BundleCompat.getParcelableArray(
                        future.get().extras, "lyrics", MediaStoreUtils.Lyric::class.java
                    ) as Array<MediaStoreUtils.Lyric>?
                }?.mapNotNull { it.content?.takeIf(String::isNotBlank) }
            }.onFailure { Log.w(TAG, "Could not read lyrics to share", it) }.getOrNull()

            if (lines.isNullOrEmpty()) {
                toast(activity, activity.getString(R.string.share_lyrics_none))
                return@launch
            }
            val header = activity.getString(
                R.string.share_song_text,
                item.mediaMetadata.title?.toString().orEmpty(),
                item.mediaMetadata.artist?.toString().orEmpty()
            )
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, header + "\n\n" + lines.joinToString("\n"))
                    },
                    activity.getString(R.string.popup_share_lyrics)
                )
            )
        }
    }

    private fun goToAlbum(activity: MainActivity, item: MediaItem) {
        val albumTitle = item.mediaMetadata.albumTitle?.toString()
        if (albumTitle.isNullOrBlank()) {
            toast(activity, activity.getString(R.string.go_to_album_missing))
            return
        }
        activity.lifecycleScope.launch {
            // Matched by title rather than by album id: the id on a MediaItem is the interned one,
            // and matching on what the user can see keeps local and server copies together.
            val tracks = activity.reader.songListSnapshot()
                .filter { it.mediaMetadata.albumTitle?.toString() == albumTitle }
            if (tracks.isEmpty()) {
                toast(activity, activity.getString(R.string.go_to_album_missing))
                return@launch
            }
            open(activity) {
                AlbumDetailFragment.newInstance(
                    albumTitle,
                    tracks.first().mediaMetadata.albumArtist?.toString()
                        ?: tracks.first().mediaMetadata.artist?.toString().orEmpty(),
                )
            }
        }
    }

    private fun toggleFavourite(activity: MainActivity, item: MediaItem) {
        val next = !isFavourite(item)
        val context = activity.applicationContext
        activity.lifecycleScope.launch(Dispatchers.IO) {
            JellyfinReporter(context).setFavourite(item.mediaId, next)
        }
    }

    private fun toast(activity: MainActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private const val TAG = "PlayerMenuActions"
}
