package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A mosaic of the covers in a mix, where each cover's tile is sized by how much of the mix it is.
 *
 * The grid used to be six fixed slots filled in order, and any slot without its own cover repeated
 * the first one - so a mix drawn mostly from one album showed that sleeve five or six times as
 * separate tiles, which reads as a bug rather than as a collage.
 *
 * Now a cover appears once, and its share of the artwork matches its share of the mix: three
 * appearances out of six is half the area, not six identical squares. A mix from a single album is
 * simply that sleeve, full size.
 */
class CollageArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    /** One entry per distinct cover, carrying how many of the supplied covers it accounted for. */
    private class Slice(val uri: Uri, val weight: Int, var bitmap: Bitmap?)

    private var currentUris: List<Uri> = emptyList()
    private var slices: List<Slice> = emptyList()
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val MAX_SLICES = 6
        private val cache = android.util.LruCache<Uri, Bitmap>(30)
    }

    /**
     * @param uris the covers of the mix, duplicates included - the repeats are the weighting, so
     *   passing a de-duplicated list gives every cover an equal share.
     */
    fun setCovers(uris: List<Uri>) {
        if (currentUris == uris) return
        currentUris = uris
        loadJob?.cancel()

        if (uris.isEmpty()) {
            slices = emptyList()
            invalidate()
            return
        }

        // Heaviest first so the biggest tile is the cover the mix actually leans on.
        slices = uris.groupingBy { it }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_SLICES)
            .map { (uri, count) -> Slice(uri, count, cache.get(uri)) }
        invalidate()

        if (slices.all { it.bitmap != null }) return

        loadJob = scope.launch {
            val pending = slices
            val fetched = withContext(Dispatchers.IO) {
                pending.map { slice ->
                    slice.bitmap ?: run {
                        val request = ImageRequest.Builder(context)
                            .data(slice.uri)
                            .size(256, 256)
                            .build()
                        val result = context.imageLoader.execute(request)
                        if (result is SuccessResult) {
                            result.image.toBitmap().also { cache.put(slice.uri, it) }
                        } else null
                    }
                }
            }
            // Discard if setCovers ran again while this was in flight.
            if (slices !== pending) return@launch
            pending.forEachIndexed { index, slice -> slice.bitmap = fetched[index] }
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        loadJob?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = null
        paint.color = 0xFF1A1A1C.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        val drawable = slices.filter { it.bitmap != null }
        if (drawable.isEmpty()) return

        val tiles = tileRects(RectF(0f, 0f, w, h), drawable.map { it.weight.toFloat() })
        tiles.forEachIndexed { index, rect ->
            drawable[index].bitmap?.let { drawCenterCropped(canvas, it, rect) }
        }
        // Only interior edges need a seam; the outer border is the card's own boundary.
        if (tiles.size > 1) tiles.forEach { canvas.drawRect(it, linePaint) }
    }

    /**
     * Splits [bounds] into one rect per weight, each with an area proportional to its weight.
     *
     * Slice-and-dice: halve the weights into two groups of roughly equal total, cut the rectangle
     * across its longer side in that ratio, and recurse into each half. Cutting the longer side
     * keeps tiles near-square rather than letting a heavy cover become a thin band, which matters
     * because these are album sleeves.
     */
    private fun tileRects(bounds: RectF, weights: List<Float>): List<RectF> {
        if (weights.size <= 1) return listOf(bounds)

        val total = weights.sum()
        if (total <= 0f) return listOf(bounds)

        // Split point closest to half the total weight, keeping at least one on each side.
        var running = 0f
        var splitAt = 0
        var bestDelta = Float.MAX_VALUE
        for (index in 0 until weights.size - 1) {
            running += weights[index]
            val delta = kotlin.math.abs(running - total / 2f)
            if (delta < bestDelta) {
                bestDelta = delta
                splitAt = index + 1
            }
        }

        val firstWeights = weights.subList(0, splitAt)
        val secondWeights = weights.subList(splitAt, weights.size)
        val firstFraction = firstWeights.sum() / total

        val (firstBounds, secondBounds) = if (bounds.width() >= bounds.height()) {
            val cut = bounds.left + bounds.width() * firstFraction
            RectF(bounds.left, bounds.top, cut, bounds.bottom) to
                RectF(cut, bounds.top, bounds.right, bounds.bottom)
        } else {
            val cut = bounds.top + bounds.height() * firstFraction
            RectF(bounds.left, bounds.top, bounds.right, cut) to
                RectF(bounds.left, cut, bounds.right, bounds.bottom)
        }

        return tileRects(firstBounds, firstWeights) + tileRects(secondBounds, secondWeights)
    }

    private fun drawCenterCropped(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val targetRatio = dst.width() / dst.height()
        val bitmapRatio = bw / bh

        val src: Rect = if (bitmapRatio > targetRatio) {
            val srcW = bh * targetRatio
            val left = (bw - srcW) / 2f
            Rect(left.toInt(), 0, (left + srcW).toInt(), bh.toInt())
        } else {
            val srcH = bw / targetRatio
            val top = (bh - srcH) / 2f
            Rect(0, top.toInt(), bw.toInt(), (top + srcH).toInt())
        }

        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}
