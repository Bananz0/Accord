package uk.akane.accord.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.R
import uk.akane.accord.logic.dp

/**
 * Draws the queue's section headings - "Playing Next", "Playing Next From: ..." - above the first
 * row of each run.
 *
 * A decoration rather than header rows in the adapter. Every position in that list is a timeline
 * index: tapping seeks to it, dragging moves it, swiping removes it. Inserting headers would shift
 * all three by an amount that depends on what is above, and the drag-reorder in particular fails
 * quietly - it looks right until something lands in the wrong place. A decoration reserves space
 * above a row without becoming a row, so the mapping stays exactly as it was.
 *
 * It also keeps the swipe honest: the capsule is drawn against the item view's own bounds, and a
 * heading placed inside the row would have stretched it to cover both.
 */
class QueueSectionDecoration(
    recyclerView: RecyclerView,
    private val labelAt: (Int) -> String?,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = recyclerView.resources.getColor(R.color.onSurfaceColor, null)
        textSize = 13.dp.px
        typeface = ResourcesCompat.getFont(recyclerView.context, R.font.inter_semibold)
            ?: Typeface.DEFAULT_BOLD
    }

    private val headingHeight = 34.dp.px
    private val baselineLift = 10.dp.px
    private val startInset = 4.dp.px

    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: android.view.View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        if (labelAt(position) != null) outRect.top = headingHeight.toInt()
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val label = labelAt(position) ?: continue
            // Follows the row it belongs to rather than sticking: these are boundaries in a list
            // being scrolled past, not persistent chapter markers.
            canvas.drawText(
                label,
                child.left + startInset,
                child.top - baselineLift,
                paint,
            )
        }
    }
}
