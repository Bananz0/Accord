package uk.akane.accord.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity

/**
 * Swipe a track row to do the two things worth doing without opening a menu.
 *
 * Right adds it to the queue; left downloads it, or in a playlist takes it out. Nothing is dragged
 * away permanently - the row springs back and the action happens, because none of these remove the
 * row from the list it is in (except the playlist case, which redraws itself anyway).
 */
object TrackSwipeActions {

    /**
     * @param trackAt what row [position] is showing, or null for rows that are not tracks - a
     *   header, a footer, an "add music" button - which must not swipe at all.
     * @param onRemove supplied by a playlist, where a left swipe means "take it out" rather than
     *   "download it".
     */
    fun attach(
        recyclerView: RecyclerView,
        activity: MainActivity,
        trackAt: (Int) -> MediaItem?,
        onRemove: ((MediaItem) -> Unit)? = null,
    ) {
        val resources = recyclerView.resources
        val queueIcon = ResourcesCompat.getDrawable(resources, R.drawable.ic_bulletin_select, null)
        val leftIcon = ResourcesCompat.getDrawable(
            resources,
            if (onRemove != null) R.drawable.ic_trash else R.drawable.ic_download,
            null
        )
        val accent = resources.getColor(R.color.accentColor, null)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val corner = 12.dp.px
        val inset = 16.dp.px

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Headers and footers share the list; swiping one would act on a track it is not.
                return if (trackAt(viewHolder.bindingAdapterPosition) == null) 0
                else super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val item = trackAt(position)
                if (item == null) {
                    recyclerView.adapter?.notifyItemChanged(position)
                    return
                }
                if (direction == ItemTouchHelper.RIGHT) {
                    addToQueue(activity, item)
                } else if (onRemove != null) {
                    onRemove(item)
                    return
                } else {
                    JellyfinDownloadManager.download(activity, listOf(item))
                    Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
                }
                // The row is still in the list, so it has to be put back where it was.
                recyclerView.adapter?.notifyItemChanged(position)
            }

            /**
             * Draws what the swipe will do behind the row: the queue on the right, downloading -
             * or removing - on the left.
             */
            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0F) {
                    val view = viewHolder.itemView
                    backgroundPaint.color = if (dX > 0) accent else Color.argb(255, 90, 90, 96)
                    val bounds = if (dX > 0) {
                        RectF(
                            view.left.toFloat(), view.top.toFloat(),
                            view.left + dX, view.bottom.toFloat()
                        )
                    } else {
                        RectF(
                            view.right + dX, view.top.toFloat(),
                            view.right.toFloat(), view.bottom.toFloat()
                        )
                    }
                    canvas.drawRoundRect(bounds, corner, corner, backgroundPaint)

                    val icon = if (dX > 0) queueIcon else leftIcon
                    icon?.let {
                        val size = 22.dp.px.toInt()
                        val centerY = (view.top + view.bottom) / 2
                        val centerX = if (dX > 0) {
                            (view.left + inset + size / 2).toInt()
                        } else {
                            (view.right - inset - size / 2).toInt()
                        }
                        it.setTint(Color.WHITE)
                        it.setBounds(
                            centerX - size / 2, centerY - size / 2,
                            centerX + size / 2, centerY + size / 2
                        )
                        it.draw(canvas)
                    }
                }
                super.onChildDraw(
                    canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    private fun addToQueue(activity: MainActivity, item: MediaItem) {
        val player = activity.getPlayer() ?: return
        if (player.mediaItemCount == 0) {
            player.setMediaItems(listOf(item), 0, C.TIME_UNSET)
            player.prepare()
            player.play()
        } else {
            player.addMediaItem(item)
        }
        Toast.makeText(activity, R.string.queued, Toast.LENGTH_SHORT).show()
    }
}
