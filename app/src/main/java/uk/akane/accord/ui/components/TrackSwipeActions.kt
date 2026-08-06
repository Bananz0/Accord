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
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.view.HapticFeedbackConstants
import kotlin.math.min

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

        // Tracks which rows have already buzzed, so crossing the threshold reports once per
        // gesture rather than on every frame past it.
        val armed = mutableSetOf<Int>()

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            /** How far the finger must travel, as a fraction of the row, to mean it. */
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.30F

            /**
             * A flick should not throw the row off the screen. Raising the escape velocity
             * well above the default means the distance decides, not the speed.
             */
            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 8F

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Headers and footers share the list; swiping one would act on a track it is not.
                // Absolute, not binding: inside a ConcatAdapter the binding position restarts at
                // zero for each child adapter, so every song looked like row zero and the header
                // offset took it negative - nothing was ever swipeable.
                return if (trackAt(viewHolder.absoluteAdapterPosition) == null) 0
                else super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.absoluteAdapterPosition
                armed.remove(viewHolder.hashCode())
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
                val view = viewHolder.itemView
                // The row follows at a fraction of the finger and stops at a limit well short
                // of the edge: it never leaves, so it always reads as something that will come
                // back, and the extra travel of the finger is the resistance.
                val limit = view.width * MAX_TRAVEL
                val damped = if (dX == 0F) 0F else {
                    val magnitude = min(abs(dX) * FOLLOW, limit)
                    if (dX > 0) magnitude else -magnitude
                }

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && damped != 0F) {
                    backgroundPaint.color = if (damped > 0) accent else Color.argb(255, 90, 90, 96)
                    val bounds = if (damped > 0) {
                        RectF(
                            view.left.toFloat(), view.top.toFloat(),
                            view.left + damped, view.bottom.toFloat()
                        )
                    } else {
                        RectF(
                            view.right + damped, view.top.toFloat(),
                            view.right.toFloat(), view.bottom.toFloat()
                        )
                    }
                    canvas.drawRoundRect(bounds, corner, corner, backgroundPaint)

                    val icon = if (damped > 0) queueIcon else leftIcon
                    icon?.let {
                        val size = 22.dp.px.toInt()
                        val centerY = (view.top + view.bottom) / 2
                        val centerX = if (damped > 0) {
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

                    // A tick the moment the swipe is far enough to do something, so the user
                    // knows they can let go without watching the row.
                    val key = viewHolder.hashCode()
                    val past = abs(dX) > view.width * getSwipeThreshold(viewHolder)
                    if (isCurrentlyActive && past && armed.add(key)) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    } else if (!past) {
                        armed.remove(key)
                    }
                }
                super.onChildDraw(
                    canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        claimHorizontalGestures(recyclerView, trackAt)
    }

    /**
     * Stops the page stealing a sideways drag that started on a row.
     *
     * FragmentSwitcherView treats any horizontal movement past the touch slop as its back-swipe and
     * intercepts it, unless a child reports it can scroll horizontally - which a vertical list never
     * does. The row swipe therefore never happened: the whole page slid instead.
     *
     * A parent decides whether to intercept before the child sees the move, so the decision has to
     * be made on the way down and reversed once the gesture turns out to be vertical, which is the
     * list's own scrolling and none of our business.
     */
    private fun claimHorizontalGestures(
        recyclerView: RecyclerView,
        trackAt: (Int) -> MediaItem?,
    ) {
        val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
        var downX = 0F
        var downY = 0F
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        // Only where a track is. Anywhere else - the header, the footer, the
                        // empty space below the list - the sideways drag still belongs to the
                        // page, and should take the user back the way they came.
                        val child = rv.findChildViewUnder(e.x, e.y)
                        val onTrack = child != null &&
                            trackAt(rv.getChildAdapterPosition(child)) != null
                        rv.parent?.requestDisallowInterceptTouchEvent(onTrack)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                            // Scrolling the list, not swiping a row - hand the gesture back.
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                // Never consumed here; this only decides who is allowed to intercept.
                return false
            }
        })
    }

    /** How far the row follows the finger, and the furthest it will go. */
    private const val FOLLOW = 0.45F
    private const val MAX_TRAVEL = 0.24F

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
