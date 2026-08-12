package uk.akane.accord.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    /**
     * What a right-to-left swipe does on this list, or null where it does nothing.
     *
     * Left is deliberately empty on ordinary song lists. It used to mean "download", which in a
     * client whose whole library already lives on a server it can reach is an answer to a question
     * nobody asked - and a panel that appears under every row teaches people not to swipe at all.
     * It survives only where the trailing edge means something specific: taking a track out of a
     * playlist or the queue, or asking Lidarr for music that is not here yet.
     */
    class Trailing(
        val iconRes: Int,
        val colorRes: Int,
        /** Whether this row can do it. A row that cannot simply will not swipe that way. */
        val enabledAt: (Int) -> Boolean = { true },
        val onAction: (Int) -> Unit,
    )

    /**
     * @param trackAt what row [position] is showing, or null for rows that are not tracks - a
     *   header, a footer, an "add music" button - which must not swipe at all.
     * @param trailing what a left swipe does here, if anything.
     */
    fun attach(
        recyclerView: RecyclerView,
        activity: MainActivity,
        trackAt: (Int) -> MediaItem?,
        trailing: Trailing? = null,
    ) {
        val resources = recyclerView.resources
        val playNextIcon = ResourcesCompat.getDrawable(resources, R.drawable.ic_play_next, null)
        val playLaterIcon = ResourcesCompat.getDrawable(resources, R.drawable.ic_play_later, null)
        val trailingIcon = trailing?.let {
            ResourcesCompat.getDrawable(resources, it.iconRes, null)
        }
        val playNextColor = resources.getColor(R.color.swipePlayNext, null)
        val playLaterColor = resources.getColor(R.color.swipePlayLater, null)
        val trailingColor = trailing?.let { resources.getColor(it.colorRes, null) }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val iconSize = 22.dp.px.toInt()
        // Separate capsules with air between them, not one panel divided by colour. Two actions
        // sharing a single background read as one striped thing; two buttons read as a choice.
        val buttonWidth = ACTION_WIDTH_DP.dp.px
        val gap = ACTION_GAP_DP.dp.px
        val verticalInset = ACTION_INSET_DP.dp.px

        var trackedHolder: RecyclerView.ViewHolder? = null
        var stage = Stage.NONE
        var actionDispatched = false
        var pendingAfterRecoil: (() -> Unit)? = null

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
            ): Int {
                // Absolute, not binding: inside a ConcatAdapter the binding position restarts at
                // zero for each child adapter, so every song looked like row zero.
                // Each edge decides for itself. A Lidarr result is not a library track and can
                // never be queued, but it is exactly the row worth asking the server to fetch, so
                // gating both directions on the same test would have left it inert.
                val position = viewHolder.absoluteAdapterPosition
                var dirs = 0
                if (trackAt(position) != null) dirs = dirs or ItemTouchHelper.RIGHT
                if (trailing?.enabledAt(position) == true) dirs = dirs or ItemTouchHelper.LEFT
                return dirs
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
                recyclerView.adapter?.notifyItemChanged(viewHolder.absoluteAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                val pending = pendingAfterRecoil
                trackedHolder = null
                stage = Stage.NONE
                actionDispatched = false
                pendingAfterRecoil = null
                // Run after the row has settled, so a list that reorders itself does not do so
                // underneath a view still animating.
                pending?.invoke()
            }

            /**
             * Draws what releasing would do.
             *
             * Two actions share the leading edge, as Apple Music does it: a partial drag reveals
             * Play Later and Play Next side by side, and carrying the drag further hands the whole
             * panel to Play Next. So a halfway release appends and a full release jumps the queue,
             * and the panel says which is armed before the finger lifts.
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
                val limit = view.width * MAX_TRAVEL
                val damped = resistedSwipeDistance(dX, limit, FOLLOW)

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && damped != 0F) {
                    // Play Later arms once its own capsule is fully out; Play Next once the pair
                    // is. Measured in capsules and the air between them rather than in screen
                    // fractions, so the gesture is the same shape on any width of phone.
                    val armAt = gap + buttonWidth
                    val fullAt = gap + buttonWidth + gap + buttonWidth
                    val reach = abs(damped)

                    if (isCurrentlyActive) {
                        if (trackedHolder !== viewHolder) {
                            trackedHolder = viewHolder
                            stage = Stage.NONE
                            actionDispatched = false
                            pendingAfterRecoil = null
                        }
                        val next = when {
                            damped > 0F && reach >= fullAt -> Stage.FULL
                            damped > 0F && reach >= armAt -> Stage.ARMED
                            // One action on this edge, so one stop, where a single capsule is out.
                            damped < 0F && reach >= gap + buttonWidth -> Stage.ARMED
                            else -> Stage.NONE
                        }
                        if (next != stage) {
                            // Distinct weights, so the two stops are told apart by feel alone -
                            // which is the entire point of having two.
                            when (next) {
                                Stage.ARMED -> Haptics.arm(view)
                                Stage.FULL -> Haptics.commit(view)
                                Stage.NONE -> Unit
                            }
                            stage = next
                        }
                    } else if (!actionDispatched && stage != Stage.NONE) {
                        val position = viewHolder.absoluteAdapterPosition
                        if (damped > 0F) {
                            trackAt(position)?.let { item ->
                                if (stage == Stage.FULL) playNext(activity, item)
                                else playLater(activity, item)
                            }
                        } else if (trailing != null) {
                            Haptics.commit(view)
                            pendingAfterRecoil = { trailing.onAction(position) }
                        }
                        actionDispatched = true
                    }

                    val top = view.top + verticalInset
                    val bottom = view.bottom - verticalInset

                    if (damped > 0F) {
                        // Three phases. Play Later grows out of the edge on its own; once it is
                        // full it holds still and Play Next grows beside it; once that one is full
                        // too the pair gives way to a single Play Next capsule that keeps growing.
                        // Each phase is a different answer to "what happens if I let go now".
                        if (stage == Stage.FULL) {
                            drawAction(
                                canvas, fillPaint, playNextColor, playNextIcon, iconSize,
                                view.left + gap, top, view.left + damped, bottom,
                            )
                        } else {
                            val laterWidth = (damped - gap).coerceIn(0F, buttonWidth)
                            if (laterWidth > 0F) {
                                val left = view.left + gap
                                drawAction(
                                    canvas, fillPaint, playLaterColor, playLaterIcon, iconSize,
                                    left, top, left + laterWidth, bottom,
                                )
                            }
                            val nextLeft = gap + buttonWidth + gap
                            val nextWidth = (damped - nextLeft).coerceIn(0F, buttonWidth)
                            if (nextWidth > 0F) {
                                val left = view.left + nextLeft
                                drawAction(
                                    canvas, fillPaint, playNextColor, playNextIcon, iconSize,
                                    left, top, left + nextWidth, bottom,
                                )
                            }
                        }
                    } else if (trailingColor != null) {
                        drawAction(
                            canvas, fillPaint, trailingColor, trailingIcon, iconSize,
                            view.right + damped, top, view.right - gap, bottom,
                        )
                    }
                }
                super.onChildDraw(
                    canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        claimHorizontalGestures(recyclerView) { position ->
            trackAt(position) != null || trailing?.enabledAt(position) == true
        }
    }

    private enum class Stage { NONE, ARMED, FULL }

    private val clipPath = Path()

    /**
     * One action capsule, with its glyph centred inside it.
     *
     * Fully rounded rather than a rounded rectangle: at this height the radius is half the capsule,
     * which is what makes two of them side by side read as buttons rather than as a split panel.
     *
     * The glyph is clipped to its own capsule. Centring it outright would put it outside a capsule
     * narrower than the glyph - and over the neighbouring one - so instead it holds a margin from
     * the growing edge and slides into view as the capsule widens, which is what the drag is
     * describing anyway.
     */
    private fun drawAction(
        canvas: Canvas,
        paint: Paint,
        color: Int,
        icon: android.graphics.drawable.Drawable?,
        iconSize: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        if (right <= left) return
        val capsule = RectF(left, top, right, bottom)
        val radius = (bottom - top) / 2F
        paint.color = color
        canvas.drawRoundRect(capsule, radius, radius, paint)

        icon ?: return
        val half = iconSize / 2F
        val margin = radius / 2F
        val centerY = ((top + bottom) / 2F).toInt()
        val raw = (left + right) / 2F
        // Held clear of whichever edge this capsule grows from.
        val growsFromLeft = true
        val centerX = if (growsFromLeft) {
            raw.coerceAtMost(right - half - margin)
        } else {
            raw.coerceAtLeast(left + half + margin)
        }.toInt()
        icon.alpha = 255
        icon.setTint(Color.WHITE)
        icon.setBounds(
            centerX - iconSize / 2, centerY - iconSize / 2,
            centerX + iconSize / 2, centerY + iconSize / 2,
        )
        canvas.save()
        canvas.clipRect(capsule)
        icon.draw(canvas)
        canvas.restore()
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
        val iconMargin = 14.dp.px
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
                        // Centred in the panel that is actually on screen, rather than pinned to
                        // where the panel starts. Anchored, the glyph sat against one edge and the
                        // gap grew as the reveal widened, so the two read as unrelated.
                        //
                        // Clamped so it is never half outside a panel narrower than itself: it
                        // keeps a margin from the leading edge until there is room, then centres.
                        val half = size / 2f
                        val rawCenter = (bounds.left + bounds.right) / 2f
                        val centerX = if (damped > 0) {
                            rawCenter.coerceAtMost(bounds.right - half - iconMargin)
                        } else {
                            rawCenter.coerceAtLeast(bounds.left + half + iconMargin)
                        }.toInt()
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
        // Now a plain predicate, so a list holding no MediaItems no longer needs a stand-in one.
        claimHorizontalGestures(recyclerView) { position -> canSwipe(position) }
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
        swipeableAt: (Int) -> Boolean,
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
                            swipeableAt(rv.getChildAdapterPosition(child))
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

    /**
     * Two stops on the leading edge, and room to reach both.
     *
     * The travel limit sits past the second stop, or the gesture would clamp before Play Next could
     * ever arm. The gap between them is deliberately wide: they are told apart by feel, and two
     * detents a few pixels apart is one mushy detent.
     */
    /**
     * How wide one action button is. The stops are measured in these, so the gesture is the same
     * shape on a small phone as on a large one.
     */
    private const val ACTION_WIDTH_DP = 76

    /** Air between the capsules, and between the last one and the row it belongs to. */
    private const val ACTION_GAP_DP = 8

    /** How far the capsules sit inside the row, top and bottom. Sets their corner radius too. */
    private const val ACTION_INSET_DP = 6

    private const val FOLLOW = 0.56F

    /**
     * The hard stop, past the switch to Play Next with room to spare - there has to be somewhere
     * left to drag after the panel changes hands, or the switch would land on the clamp and feel
     * like the gesture had jammed rather than escalated.
     */
    private const val MAX_TRAVEL = 0.46F

    /** The Lidarr request gesture below still has a single stop. */
    private const val SWIPE_THRESHOLD = 0.36F
    private const val SETTLE_MS = 190L
    private const val NEVER_SWIPE_AWAY = 2F

    /** Appends to the end of the queue. */
    private fun playLater(activity: MainActivity, item: MediaItem) {
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

    /**
     * Slots the track in directly after whatever is playing.
     *
     * Inserted after the current index rather than at the front of the timeline: the front is
     * where playback started, not where it is now, so putting it there would queue the track
     * behind everything already played.
     */
    private fun playNext(activity: MainActivity, item: MediaItem) {
        val player = activity.getPlayer() ?: return
        if (player.mediaItemCount == 0) {
            player.setMediaItems(listOf(item), 0, C.TIME_UNSET)
            player.prepare()
            player.play()
        } else {
            player.addMediaItem(player.currentMediaItemIndex + 1, item)
        }
        Toast.makeText(activity, R.string.queued_next, Toast.LENGTH_SHORT).show()
    }
}
