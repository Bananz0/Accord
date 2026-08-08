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
 * Custom view that renders an asymmetric geometric mosaic collage of recent album covers.
 * - Top-Left (50% x 50%): 1st most recent album
 * - Top-Right Upper (50% x 25%): 2nd most recent album
 * - Top-Right Lower (50% x 25%): 3rd most recent album
 * - Bottom-Left (33.3% x 50%): 4th most recent album
 * - Bottom-Middle (33.3% x 50%): 5th most recent album
 * - Bottom-Right (33.3% x 50%): 6th most recent album
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

    private var currentUris: List<Uri> = emptyList()
    private var loadedBitmaps: Array<Bitmap?> = arrayOfNulls(6)
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private val cache = android.util.LruCache<Uri, Bitmap>(30)
    }

    fun setCovers(uris: List<Uri>) {
        if (currentUris == uris) return
        currentUris = uris
        loadJob?.cancel()

        if (uris.isEmpty()) {
            loadedBitmaps.fill(null)
            invalidate()
            return
        }

        val targets = uris.take(6)
        var missingAny = false
        targets.forEachIndexed { i, uri ->
            val cached = cache.get(uri)
            if (cached != null) {
                loadedBitmaps[i] = cached
            } else {
                loadedBitmaps[i] = null
                missingAny = true
            }
        }
        invalidate()

        if (!missingAny) return

        loadJob = scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                targets.map { uri ->
                    val existing = cache.get(uri)
                    if (existing != null) return@map existing
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .size(256, 256)
                        .build()
                    val result = context.imageLoader.execute(request)
                    if (result is SuccessResult) {
                        val bmp = result.image.toBitmap()
                        cache.put(uri, bmp)
                        bmp
                    } else null
                }
            }
            fetched.forEachIndexed { i, bitmap ->
                if (i < 6) loadedBitmaps[i] = bitmap
            }
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

        val tiles = calculateTileRects(w, h)

        paint.shader = null
        paint.color = 0xFF1A1A1C.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        var drawnAny = false
        tiles.forEachIndexed { i, dst ->
            val bitmap = loadedBitmaps.getOrNull(i)
                ?: loadedBitmaps.firstOrNull { it != null }
            if (bitmap != null) {
                drawnAny = true
                drawCenterCropped(canvas, bitmap, dst)
            }
        }

        if (drawnAny) {
            canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h * 0.5f, linePaint)
            canvas.drawLine(w * 0.5f, h * 0.25f, w, h * 0.25f, linePaint)
            canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, linePaint)
            canvas.drawLine(w * (1f / 3f), h * 0.5f, w * (1f / 3f), h, linePaint)
            canvas.drawLine(w * (2f / 3f), h * 0.5f, w * (2f / 3f), h, linePaint)
        }
    }

    private fun calculateTileRects(w: Float, h: Float): Array<RectF> {
        return arrayOf(
            RectF(0f, 0f, w * 0.5f, h * 0.5f),
            RectF(w * 0.5f, 0f, w, h * 0.25f),
            RectF(w * 0.5f, h * 0.25f, w, h * 0.5f),
            RectF(0f, h * 0.5f, w * (1f / 3f), h),
            RectF(w * (1f / 3f), h * 0.5f, w * (2f / 3f), h),
            RectF(w * (2f / 3f), h * 0.5f, w, h)
        )
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
