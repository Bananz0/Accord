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

object PlayerPopupMenu {
    private val popupAnchorOffset = 12.dp.px.toInt()
    private val popupBelowGap = 8.dp.px.toInt()

    fun build(resources: Resources): PopupHelper.PopupEntries {
        return PopupHelper.PopupMenuBuilder()
            .addMenuEntry(resources, R.drawable.ic_info, R.string.popup_view_credits)
            .addSpacer()
            .addDestructiveMenuEntry(
                resources,
                R.drawable.ic_trash,
                R.string.popup_delete_from_library
            )
            // Upstream leaves every entry below on ic_square, a placeholder box, so the menu came up
            // with a column of empty squares. These are the closest real icons the app already has.
            .addMenuEntry(resources, R.drawable.ic_playlist, R.string.popup_add_to_a_playlist)
            .addSpacer()
            .addMenuEntry(resources, R.drawable.ic_note, R.string.popup_share_song)
            .addMenuEntry(resources, R.drawable.ic_quote, R.string.popup_share_lyrics)
            .addMenuEntry(resources, R.drawable.ic_album, R.string.popup_go_to_album)
            .addMenuEntry(resources, R.drawable.ic_airplay_radio, R.string.popup_create_station)
            .addSpacer()
            .addMenuEntry(resources, R.drawable.ic_favourite, R.string.popup_undo_favorite)
            .build()
    }

    fun show(
        host: PopupMenuHost,
        anchorView: View,
        showBelow: Boolean = false,
        backgroundView: View? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val entries = build(anchorView.resources)
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
            onDismiss = onDismiss
        )
    }

    fun show(
        host: PopupMenuHost,
        anchorView: View,
        anchorRect: RectF,
        showBelow: Boolean = false,
        backgroundView: View? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val entries = build(anchorView.resources)
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
            onDismiss = onDismiss
        )
    }
}
