package uk.akane.accord.ui.adapters

import android.content.Context
import uk.akane.accord.ui.components.frameNanos
import uk.akane.accord.ui.components.Haptics
import uk.akane.accord.ui.components.SwipeActionPanel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.components.QueueBlendView
import uk.akane.accord.ui.components.ResistiveSwipeHaptics
import uk.akane.accord.ui.components.resistedSwipeDistance
import uk.akane.cupertino.utils.AnimationUtils.FASTEST_DURATION
import java.util.Collections
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * @param sectionLabel the heading drawn above this row, on the first row of each run - the tracks
 *   the user queued by hand, then wherever the rest is coming from. Null everywhere else.
 */
data class QueueItem(
    val uid: Any,
    val mediaItem: MediaItem,
    val sectionLabel: String? = null,
)

class QueuePreviewAdapter(
    private val items: MutableList<QueueItem>,
    private val targetView: View,
    private val onMove: ((Int, Int) -> Unit)? = null,
    private val onItemClick: ((Int) -> Unit)? = null,
    private val dragStartListener: DragStartListener? = null
) : RecyclerView.Adapter<QueuePreviewAdapter.ViewHolder>() {

    var isDragging = false
        private set
    
    private var diffJob: Job? = null

    interface DragStartListener {
        fun onStartDrag(viewHolder: RecyclerView.ViewHolder)
    }

    fun updateItems(newItems: List<QueueItem>) {
        if (isDragging) return
        
        diffJob?.cancel()
        diffJob = CoroutineScope(Dispatchers.Default).launch {
            val oldList = items.toList()
            val diffCallback = QueueDiffCallback(oldList, newItems)
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            
            if (isActive) {
                withContext(Dispatchers.Main) {
                    items.clear()
                    items.addAll(newItems)
                    diffResult.dispatchUpdatesTo(this@QueuePreviewAdapter)
                }
            }
        }
    }

    fun onDragStart() {
        isDragging = true
    }

    fun onDragEnd() {
        isDragging = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_queue_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
        holder.subtitle.text = item.mediaItem.mediaMetadata.artist?.toString() ?: "Unknown Artist"

        holder.cover.load(item.mediaItem.mediaMetadata.artworkUri) {
            size(54.dp.px.toInt(), 54.dp.px.toInt())
        }

        holder.blendView.setup(targetView)

        if (holder.itemView.background == null) {
            holder.itemView.alpha = 1f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.translationZ = 0f
        }

        // Handle item click to play song
        holder.itemView.setOnClickListener {
            if (!isDragging && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(holder.bindingAdapterPosition)
            }
        }

        // Consume clicks on the drag handle to prevent triggering onItemClick on the parent
        holder.reorderHandle.setOnClickListener { }

        // Handle touch on handle to start dragging
        holder.reorderHandle.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dragStartListener?.onStartDrag(holder)
                v.performClick()
            }
            false
        }
    }

    override fun getItemCount(): Int = items.size

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
            return false
        }
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
        onMove?.invoke(fromPosition, toPosition)
        return true
    }

    fun itemAt(position: Int): QueueItem? = items.getOrNull(position)

    fun indexOf(uid: Any): Int = items.indexOfFirst { it.uid == uid }

    /** The heading above [position], for the section decoration. */
    fun sectionLabelAt(position: Int): String? = items.getOrNull(position)?.sectionLabel

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val cover: android.widget.ImageView = view.findViewById(R.id.cover)
        val reorderHandle: View = view.findViewById(R.id.reorder_handle)
        val blendView: QueueBlendView = view.findViewById(R.id.queue_blend_view)
    }
}

/**
 * Reorder by dragging the handle, remove by swiping.
 *
 * Draws through the shared [SwipeActionPanel], like every other swipe in the app. It used to have
 * geometry of its own - a flat grey panel that stopped part-way across the row while the song lists
 * had grown capsules that ran the full width. Two implementations of one gesture is how that
 * happens; there is now one.
 */
class QueueItemTouchHelperCallback(
    private val adapter: QueuePreviewAdapter,
    context: Context,
    private val onRemove: (Int) -> Unit,
) : ItemTouchHelper.Callback() {
    private var currentDragViewHolder: RecyclerView.ViewHolder? = null
    private var trackedSwipeHolder: RecyclerView.ViewHolder? = null
    private var armed = -1
    private var removalDispatched = false
    private var pendingRemovalUid: Any? = null

    /**
     * Removal on either edge.
     *
     * The trailing edge is where it belongs, but the leading one is the drag handle's neighbour and
     * a queue row has nothing else a sideways drag could mean - so the same thing happens whichever
     * way it goes, rather than one direction quietly doing nothing.
     */
    private val panel = SwipeActionPanel(
        context = context,
        leading = listOf(
            SwipeActionPanel.Action(R.color.swipeDestructive, R.drawable.ic_trash)
        ),
        trailing = listOf(
            SwipeActionPanel.Action(R.color.swipeDestructive, R.drawable.ic_trash)
        ),
    )

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = true

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
    }

    // The row always recoils before it is removed. ItemTouchHelper's completed-swipe path would
    // throw it off-screen and make the reveal vanish separately from the row.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = NEVER_SWIPE_AWAY

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

    override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE

    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float,
    ): Long = if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
        SWIPE_SETTLE_MS
    } else {
        super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(
                canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive
            )
            return
        }

        val view = viewHolder.itemView
        val damped = panel.distance(dX, view)

        if (damped != 0F) {
            if (isCurrentlyActive) {
                if (trackedSwipeHolder !== viewHolder) {
                    trackedSwipeHolder = viewHolder
                    armed = -1
                    removalDispatched = false
                    pendingRemovalUid = null
                    panel.reset()
                    adapter.onDragStart()
                }
                val next = panel.armedIndex(damped)
                if (next != armed) {
                    if (next >= 0) Haptics.commit(view)
                    armed = next
                }
            } else if (armed >= 0 && !removalDispatched) {
                pendingRemovalUid = adapter.itemAt(viewHolder.bindingAdapterPosition)?.uid
                removalDispatched = true
            }

            if (panel.draw(
                    canvas, view, damped, recyclerView.frameNanos(), isCurrentlyActive
                )
            ) {
                recyclerView.invalidate()
            }
        }

        super.onChildDraw(
            canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
        )
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)

        when (actionState) {
            ItemTouchHelper.ACTION_STATE_DRAG -> {
                adapter.onDragStart()
                currentDragViewHolder = viewHolder
                viewHolder?.itemView?.let { view ->
                    view.animate().cancel()
                    view.outlineProvider = ViewOutlineProvider.BOUNDS
                    view.clipToOutline = true
                    view.animate()
                        .translationZ(10f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(FASTEST_DURATION)
                        .start()
                }
            }
            ItemTouchHelper.ACTION_STATE_IDLE -> {
                currentDragViewHolder?.itemView?.background = null
                currentDragViewHolder = null
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.onDragEnd()

        val removalUid = pendingRemovalUid
        trackedSwipeHolder = null
        armed = -1
        removalDispatched = false
        pendingRemovalUid = null
        panel.reset()
        if (removalUid != null) {
            val position = adapter.indexOf(removalUid)
            if (position >= 0) onRemove(position)
        }

        viewHolder.itemView.animate().cancel()
        viewHolder.itemView.background = null
        viewHolder.itemView.outlineProvider = ViewOutlineProvider.BACKGROUND
        viewHolder.itemView.clipToOutline = false
        viewHolder.itemView.animate()
            .translationZ(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(FASTEST_DURATION)
            .start()
    }

    companion object {
        // Geometry now lives in SwipeActionPanel; only the two ItemTouchHelper knobs remain.
        private const val NEVER_SWIPE_AWAY = 10F
        private const val SWIPE_SETTLE_MS = 190L
    }
}

class QueueDiffCallback(
    private val oldList: List<QueueItem>,
    private val newList: List<QueueItem>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].uid == newList[newItemPosition].uid
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldMeta = oldList[oldItemPosition].mediaItem.mediaMetadata
        val newMeta = newList[newItemPosition].mediaItem.mediaMetadata
        return oldMeta.title == newMeta.title &&
                oldMeta.artist == newMeta.artist &&
                oldMeta.artworkUri == newMeta.artworkUri &&
                // Included, or a row keeps a heading that has moved on to another track.
                oldList[oldItemPosition].sectionLabel == newList[newItemPosition].sectionLabel
    }
}
