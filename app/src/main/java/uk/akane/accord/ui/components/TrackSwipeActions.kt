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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

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

        val swipeHaptics = ResistiveSwipeHaptics()
        var trackedHolder: RecyclerView.ViewHolder? = null
        var armedDirection = 0
        var actionDispatched = false
        var removeAfterRecoil: MediaItem? = null

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            /** How far the finger must travel, as a fraction of the row, to mean it. */
            // Actions never use ItemTouchHelper's "swipe away" completion. Its cancel path is the
            // one that keeps drawing the reveal while both it and the row recoil to zero.
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = NEVER_SWIPE_AWAY

            /**
             * A flick should not throw the row off the screen. Raising the escape velocity
             * well above the default means the distance decides, not the speed.
             */
            // A short, decisive flick carries enough momentum to complete the action even when the
            // finger leaves just before the distance threshold.
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

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

            override fun getAnimationDuration(
                recyclerView: RecyclerView,
                animationType: Int,
                animateDx: Float,
                animateDy: Float,
            ): Long = if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
                SETTLE_MS
            } else {
                super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Defensive only: the thresholds above make this path unreachable.
                recyclerView.adapter?.notifyItemChanged(viewHolder.absoluteAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                swipeHaptics.release(viewHolder.itemView)
                val pendingRemoval = removeAfterRecoil
                trackedHolder = null
                armedDirection = 0
                actionDispatched = false
                removeAfterRecoil = null
                pendingRemoval?.let { onRemove?.invoke(it) }
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
                val damped = resistedSwipeDistance(dX, limit, FOLLOW)

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && damped != 0F) {
                    if (isCurrentlyActive) {
                        if (trackedHolder !== viewHolder) {
                            trackedHolder = viewHolder
                            armedDirection = 0
                            actionDispatched = false
                            removeAfterRecoil = null
                        }
                        swipeHaptics.update(
                            view,
                            dX,
                            view.width * SWIPE_THRESHOLD,
                        )
                        armedDirection = when {
                            abs(dX) < view.width * SWIPE_THRESHOLD -> 0
                            dX > 0F -> ItemTouchHelper.RIGHT
                            else -> ItemTouchHelper.LEFT
                        }
                    } else if (!actionDispatched && armedDirection != 0) {
                        trackAt(viewHolder.absoluteAdapterPosition)?.let { item ->
                            swipeHaptics.commit(view)
                            when {
                                armedDirection == ItemTouchHelper.RIGHT -> addToQueue(activity, item)
                                onRemove != null -> removeAfterRecoil = item
                                else -> {
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        JellyfinDownloadManager.download(activity, listOf(item))
                                    }
                                    Toast.makeText(
                                        activity,
                                        R.string.download_started,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                        actionDispatched = true
                    }
                    backgroundPaint.color = if (damped > 0) accent else Color.argb(255, 90, 90, 96)
                    val cover = view.findViewById<android.view.View?>(R.id.cover)
                    val artworkStart = cover?.left ?: inset.toInt()
                    val bounds = if (damped > 0) {
                        RectF(
                            (view.left + artworkStart).toFloat(), view.top.toFloat(),
                            (view.left + damped).coerceAtLeast(view.left + artworkStart.toFloat()),
                            view.bottom.toFloat()
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
                        val reveal = (
                            abs(dX) / (view.width * SWIPE_THRESHOLD)
                        ).coerceIn(0F, 1F)
                        it.alpha = (255 * reveal).toInt()
                        val size = 22.dp.px.toInt()
                        val centerY = (view.top + view.bottom) / 2
                        val centerX = if (damped > 0) {
                            view.left + artworkStart + (cover?.width ?: size) / 2
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
                    canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        claimHorizontalGestures(recyclerView, trackAt)
    }

    /**
     * The same gesture on a list of things that are not library tracks - Lidarr's search
     * results - where both directions mean the one thing worth doing: request it.
     *
     * @param canSwipe whether the row at this position can be requested at all.
     */
    fun attachRequest(
        recyclerView: RecyclerView,
        canSwipe: (Int) -> Boolean,
        onRequest: (Int) -> Unit,
    ) {
        val resources = recyclerView.resources
        val icon = ResourcesCompat.getDrawable(resources, R.drawable.ic_download, null)
        val accent = resources.getColor(R.color.accentColor, null)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val corner = 12.dp.px
        val inset = 16.dp.px
        val swipeHaptics = ResistiveSwipeHaptics()
        var trackedHolder: RecyclerView.ViewHolder? = null
        var armedDirection = 0
        var actionDispatched = false

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = NEVER_SWIPE_AWAY
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) = if (canSwipe(viewHolder.absoluteAdapterPosition)) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun getAnimationDuration(
                recyclerView: RecyclerView,
                animationType: Int,
                animateDx: Float,
                animateDy: Float,
            ): Long = if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
                SETTLE_MS
            } else {
                super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                recyclerView.adapter?.notifyItemChanged(viewHolder.absoluteAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                swipeHaptics.release(viewHolder.itemView)
                trackedHolder = null
                armedDirection = 0
                actionDispatched = false
            }

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
                val limit = view.width * MAX_TRAVEL
                val damped = resistedSwipeDistance(dX, limit, FOLLOW)
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && damped != 0F) {
                    if (isCurrentlyActive) {
                        if (trackedHolder !== viewHolder) {
                            trackedHolder = viewHolder
                            armedDirection = 0
                            actionDispatched = false
                        }
                        swipeHaptics.update(
                            view,
                            dX,
                            view.width * SWIPE_THRESHOLD,
                        )
                        armedDirection = when {
                            abs(dX) < view.width * SWIPE_THRESHOLD -> 0
                            dX > 0F -> ItemTouchHelper.RIGHT
                            else -> ItemTouchHelper.LEFT
                        }
                    } else if (!actionDispatched && armedDirection != 0) {
                        val position = viewHolder.absoluteAdapterPosition
                        if (canSwipe(position)) {
                            swipeHaptics.commit(view)
                            onRequest(position)
                        }
                        actionDispatched = true
                    }
                    backgroundPaint.color = accent
                    val cover = view.findViewById<android.view.View?>(R.id.cover)
                    val artworkStart = cover?.left ?: inset.toInt()
                    val bounds = if (damped > 0) {
                        RectF(
                            (view.left + artworkStart).toFloat(), view.top.toFloat(),
                            (view.left + damped).coerceAtLeast(view.left + artworkStart.toFloat()),
                            view.bottom.toFloat()
                        )
                    } else {
                        RectF(
                            view.right + damped, view.top.toFloat(),
                            view.right.toFloat(), view.bottom.toFloat()
                        )
                    }
                    canvas.drawRoundRect(bounds, corner, corner, backgroundPaint)
                    icon?.let {
                        val reveal = (
                            abs(dX) / (view.width * SWIPE_THRESHOLD)
                        ).coerceIn(0F, 1F)
                        it.alpha = (255 * reveal).toInt()
                        val size = 22.dp.px.toInt()
                        val centerY = (view.top + view.bottom) / 2
                        val centerX = if (damped > 0) {
                            view.left + artworkStart + (cover?.width ?: size) / 2
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
                    canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        claimHorizontalGestures(recyclerView) { position ->
            // Reuses the track hook only as a yes/no; the value itself is never read.
            if (canSwipe(position)) PLACEHOLDER else null
        }
    }

    /** Stands in for "there is something swipeable here" on lists that hold no MediaItems. */
    private val PLACEHOLDER: MediaItem = MediaItem.EMPTY

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

    /** The hard stop is reached at the same instant the shorter swipe arms its action. */
    private const val SWIPE_THRESHOLD = 0.36F
    private const val FOLLOW = 0.56F
    private const val MAX_TRAVEL = SWIPE_THRESHOLD * FOLLOW
    private const val SETTLE_MS = 190L
    private const val NEVER_SWIPE_AWAY = 2F

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
