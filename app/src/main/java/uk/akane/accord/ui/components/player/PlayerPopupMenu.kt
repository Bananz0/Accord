package uk.akane.accord.ui.components.player

import android.content.res.Resources
import android.graphics.RectF
import android.view.View
import uk.akane.accord.R
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.cupertino.popup.PopupMenuHost
import uk.akane.cupertino.popup.showPopupMenuFromAnchor
import uk.akane.cupertino.popup.showPopupMenuFromAnchorRect
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager

object PlayerPopupMenu {
    private val popupAnchorOffset = 12.dp.px.toInt()
    private val popupBelowGap = 8.dp.px.toInt()

    /**
     * Every entry carries the action it performs as its payload. The label alone cannot identify an
     * entry - two of them change with state and all of them change with language - so the click
     * handler matches on this instead.
     */
    fun build(
        resources: Resources,
        isFavourite: Boolean,
        isDownloaded: Boolean,
    ): PopupHelper.PopupEntries {
        return PopupHelper.PopupMenuBuilder()
            .addMenuEntry(
                resources, R.drawable.ic_info, R.string.popup_view_credits,
                PlayerMenuActions.Action.VIEW_CREDITS
            )
            .addSpacer()
            .apply {
                if (isDownloaded) {
                    addDestructiveMenuEntry(
                        resources, R.drawable.ic_trash, R.string.collection_remove_from_device,
                        PlayerMenuActions.Action.REMOVE_DOWNLOAD
                    )
                } else {
                    addMenuEntry(
                        resources, R.drawable.ic_download, R.string.download,
                        PlayerMenuActions.Action.DOWNLOAD
                    )
                }
            }
            // Upstream leaves every entry below on ic_square, a placeholder box, so the menu came up
            // with a column of empty squares. These are the closest real icons the app already has.
            .addMenuEntry(
                resources, R.drawable.ic_playlist, R.string.popup_add_to_a_playlist,
                PlayerMenuActions.Action.ADD_TO_PLAYLIST
            )
            .addSpacer()
            .addMenuEntry(
                resources, R.drawable.ic_note, R.string.popup_share_song,
                PlayerMenuActions.Action.SHARE_SONG
            )
            .addMenuEntry(
                resources, R.drawable.ic_quote, R.string.popup_share_lyrics,
                PlayerMenuActions.Action.SHARE_LYRICS
            )
            .addMenuEntry(
                resources, R.drawable.ic_album, R.string.popup_go_to_album,
                PlayerMenuActions.Action.GO_TO_ALBUM
            )
            .addMenuEntry(
                resources, R.drawable.ic_airplay_radio, R.string.popup_create_station,
                PlayerMenuActions.Action.CREATE_STATION
            )
            .addSpacer()
            .addMenuEntry(
                resources,
                R.drawable.ic_favourite,
                // Offering "Undo Favorite" on a song that is not one is an action with no meaning;
                // the entry names whichever way the tap will actually go.
                if (isFavourite) R.string.popup_undo_favorite else R.string.popup_favorite,
                PlayerMenuActions.Action.TOGGLE_FAVOURITE
            )
            .build()
    }

    fun show(
        host: PopupMenuHost,
        anchorView: View,
        showBelow: Boolean = false,
        backgroundView: View? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val activity = anchorView.context.findMainActivity()
        val item = activity?.getPlayer()?.currentMediaItem
        if (activity == null || item == null) return
        activity.lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.isDownloaded(activity, item)
            }
            if (!anchorView.isAttachedToWindow) return@launch
            val entries = build(
                anchorView.resources,
                PlayerMenuActions.isFavourite(item),
                downloaded,
            )
            val anchorOffsetY = if (showBelow) popupAnchorOffset else 0
            val belowGap = if (showBelow) popupBelowGap else 0
            host.showPopupMenuFromAnchor(
            entries = entries,
            anchorView = anchorView,
            showBelow = showBelow,
            alignToRight = true,
            anchorOffsetY = anchorOffsetY,
            belowGapPx = belowGap,
            backgroundView = backgroundView,
            onDismiss = onDismiss,
            onEntryClick = { entry -> dispatch(activity, entry) }
            )
        }
    }

    fun show(
        host: PopupMenuHost,
        anchorView: View,
        anchorRect: RectF,
        showBelow: Boolean = false,
        backgroundView: View? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val activity = anchorView.context.findMainActivity()
        val item = activity?.getPlayer()?.currentMediaItem
        if (activity == null || item == null) return
        activity.lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.isDownloaded(activity, item)
            }
            if (!anchorView.isAttachedToWindow) return@launch
            val entries = build(
                anchorView.resources,
                PlayerMenuActions.isFavourite(item),
                downloaded,
            )
            val anchorOffsetY = if (showBelow) popupAnchorOffset else 0
            val belowGap = if (showBelow) popupBelowGap else 0
            host.showPopupMenuFromAnchorRect(
            entries = entries,
            anchorView = anchorView,
            anchorRect = anchorRect,
            showBelow = showBelow,
            alignToRight = true,
            anchorOffsetY = anchorOffsetY,
            belowGapPx = belowGap,
            backgroundView = backgroundView,
            onDismiss = onDismiss,
            onEntryClick = { entry -> dispatch(activity, entry) }
            )
        }
    }

    private fun dispatch(activity: MainActivity?, entry: PopupHelper.PopupEntry) {
        val action = (entry as? PopupHelper.MenuEntry)?.payload as? PlayerMenuActions.Action ?: return
        activity?.let { PlayerMenuActions.handle(it, action) }
    }

    /**
     * The menu is shown from views whose context is a themed wrapper rather than the activity, so
     * the activity has to be unwrapped rather than cast to.
     */
    private fun android.content.Context.findMainActivity(): MainActivity? {
        var context: android.content.Context? = this
        while (context is android.content.ContextWrapper) {
            if (context is MainActivity) return context
            context = context.baseContext
        }
        return null
    }
}
