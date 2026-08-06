package uk.akane.accord.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * The artwork behind a station card - the soft colour field the 1.0-stable build uses for its
 * "Made for you" cards, drawn rather than shipped as an image so every station gets its own.
 *
 * Colours and layout come from the station's id, so a station looks the same each time it appears
 * while no two look alike. Three styles rather than one, because a row of cards drawn the same way
 * reads as a repeated texture instead of as separate stations.
 */
class StationArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Style { MESH, AURORA, RINGS }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var style = Style.MESH
    private var palette: IntArray = DEFAULT_PALETTE
    private var phase = 0f

    private var animator: ValueAnimator? = null

    /**
     * @param seed anything stable for this station - its id. Drives both the palette and the style.
     */
    fun bind(seed: String) {
        val hash = seed.fold(7L) { acc, c -> acc * 31 + c.code }
        style = Style.entries[((hash ushr 8) and 0xFF).toInt() % Style.entries.size]
        palette = paletteFor(hash)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Slow enough to read as drifting light rather than motion, and cheap: one float per frame
        // driving a shader that is rebuilt only while the card is on screen.
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = null
        paint.color = palette[0]
        canvas.drawRect(0f, 0f, w, h, paint)

        when (style) {
            Style.MESH -> drawMesh(canvas, w, h)
            Style.AURORA -> drawAurora(canvas, w, h)
            Style.RINGS -> drawRings(canvas, w, h)
        }
    }

    /** Overlapping soft blobs, drifting on slightly different orbits. */
    private fun drawMesh(canvas: Canvas, w: Float, h: Float) {
        val radius = maxOf(w, h) * 0.85f
        palette.forEachIndexed { index, color ->
            if (index == 0) return@forEachIndexed
            val angle = (phase + index / palette.size.toFloat()) * TWO_PI
            val cx = w * (0.5f + 0.32f * cos(angle + index).toFloat())
            val cy = h * (0.5f + 0.32f * sin(angle * 0.8f + index).toFloat())
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(color, ColorUtils.setAlphaComponent(color, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    /** Diagonal bands sliding past each other. */
    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        val offset = phase * h
        palette.forEachIndexed { index, color ->
            if (index == 0) return@forEachIndexed
            val start = -h + offset + index * h * 0.35f
            paint.shader = LinearGradient(
                0f, start, w, start + h * 0.9f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(color, 0),
                    color,
                    ColorUtils.setAlphaComponent(color, 0)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    /** Concentric rings off one corner, turning slowly. */
    private fun drawRings(canvas: Canvas, w: Float, h: Float) {
        val cx = w * 0.05f
        val cy = h * 0.35f
        paint.shader = SweepGradient(cx, cy, palette, null)
        canvas.save()
        canvas.rotate(phase * 360f, cx, cy)
        canvas.drawRect(-w, -h, w * 2, h * 2, paint)
        canvas.restore()

        val step = maxOf(w, h) / (palette.size + 1)
        palette.forEachIndexed { index, color ->
            paint.shader = null
            paint.color = ColorUtils.setAlphaComponent(color, 90)
            canvas.drawCircle(cx, cy, step * (palette.size - index), paint)
        }
    }

    /**
     * A hue picked from the seed with its neighbours, so a card is always a harmonious set rather
     * than an arbitrary collection.
     */
    private fun paletteFor(hash: Long): IntArray {
        val baseHue = ((hash ushr 16) and 0x1FF).toInt() % 360
        val spread = 26f
        return IntArray(4) { index ->
            val hue = ((baseHue + index * spread) % 360f + 360f) % 360f
            val saturation = 0.62f + 0.08f * ((index % 2))
            val lightness = if (index == 0) 0.22f else 0.56f - 0.05f * index
            ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
        }
    }

    companion object {
        private const val TWO_PI = (Math.PI * 2).toFloat()
        private const val ANIMATION_DURATION_MS = 24_000L
        private val DEFAULT_PALETTE = intArrayOf(
            Color.BLACK, Color.DKGRAY, Color.GRAY, Color.LTGRAY
        )
    }
}
