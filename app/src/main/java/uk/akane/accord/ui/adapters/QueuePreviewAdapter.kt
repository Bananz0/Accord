package uk.akane.accord.ui.adapters

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

data class QueueItem(val uid: Any, val mediaItem: MediaItem)

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

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val cover: android.widget.ImageView = view.findViewById(R.id.cover)
        val reorderHandle: View = view.findViewById(R.id.reorder_handle)
        val blendView: QueueBlendView = view.findViewById(R.id.queue_blend_view)
    }
}

class QueueItemTouchHelperCallback(
    private val adapter: QueuePreviewAdapter,
    private val onRemove: (Int) -> Unit,
) : ItemTouchHelper.Callback() {
    private var currentDragViewHolder: RecyclerView.ViewHolder? = null
    private var trackedSwipeHolder: RecyclerView.ViewHolder? = null
    private var armedForRemoval = false
    private var removalDispatched = false
    private var pendingRemovalUid: Any? = null
    private val swipeHaptics = ResistiveSwipeHaptics()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 90, 96)
    }
    private var trashIcon: android.graphics.drawable.Drawable? = null

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
        val threshold = view.width * SWIPE_THRESHOLD
        val damped = resistedSwipeDistance(dX, view.width * MAX_TRAVEL, SWIPE_FOLLOW)

        if (damped != 0F) {
            if (isCurrentlyActive) {
                if (trackedSwipeHolder !== viewHolder) {
                    trackedSwipeHolder = viewHolder
                    armedForRemoval = false
                    removalDispatched = false
                    pendingRemovalUid = null
                    adapter.onDragStart()
                }
                swipeHaptics.update(view, dX, threshold)
                armedForRemoval = abs(dX) >= threshold
            } else if (armedForRemoval && !removalDispatched) {
                swipeHaptics.commit(view)
                pendingRemovalUid = adapter.itemAt(viewHolder.bindingAdapterPosition)?.uid
                removalDispatched = true
            }

            val cover = view.findViewById<View?>(R.id.cover)
            val artworkStart = cover?.left ?: ICON_INSET_DP.dp.px.toInt()
            val bounds = if (damped > 0F) {
                RectF(
                    (view.left + artworkStart).toFloat(), view.top.toFloat(),
                    (view.left + damped).coerceAtLeast(view.left + artworkStart.toFloat()),
                    view.bottom.toFloat(),
                )
            } else {
                RectF(
                    view.right + damped, view.top.toFloat(),
                    view.right.toFloat(), view.bottom.toFloat(),
                )
            }
            canvas.drawRoundRect(bounds, CORNER_RADIUS_DP.dp.px, CORNER_RADIUS_DP.dp.px, backgroundPaint)

            val icon = trashIcon ?: androidx.core.content.res.ResourcesCompat.getDrawable(
                recyclerView.resources, R.drawable.ic_trash, null
            )?.also { trashIcon = it }
            icon?.let {
                val reveal = (abs(dX) / threshold).coerceIn(0F, 1F)
                it.alpha = (255 * reveal).toInt()
                it.setTint(Color.WHITE)
                val size = ICON_SIZE_DP.dp.px.toInt()
                val inset = ICON_INSET_DP.dp.px.toInt()
                val centerY = (view.top + view.bottom) / 2
                val centerX = if (damped > 0F) {
                    view.left + artworkStart + (cover?.width ?: size) / 2
                } else {
                    view.right - inset - size / 2
                }
                it.setBounds(
                    centerX - size / 2, centerY - size / 2,
                    centerX + size / 2, centerY + size / 2,
                )
                it.draw(canvas)
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

        swipeHaptics.release(viewHolder.itemView)
        val removalUid = pendingRemovalUid
        trackedSwipeHolder = null
        armedForRemoval = false
        removalDispatched = false
        pendingRemovalUid = null
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
        private const val NEVER_SWIPE_AWAY = 10F
        private const val SWIPE_FOLLOW = 0.56F
        private const val SWIPE_THRESHOLD = 0.32F
        private const val MAX_TRAVEL = SWIPE_THRESHOLD * SWIPE_FOLLOW
        private const val SWIPE_SETTLE_MS = 190L
        private const val CORNER_RADIUS_DP = 12
        private const val ICON_SIZE_DP = 22
        private const val ICON_INSET_DP = 16
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
                oldMeta.artworkUri == newMeta.artworkUri
    }
}
