package uk.akane.accord.ui.components.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.floatAnimator
import uk.akane.accord.logic.sp
import uk.akane.cupertino.utils.AnimationUtils
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@Suppress("ViewConstructor")
class LyricsLineView internal constructor(
    context: Context,
    private val line: LyricsLine,
    /** Where to jump to when this line is tapped; see [performClick]. */
    private val onSeek: ((Long) -> Unit)? = null,
) : View(context) {
    private val horizontalPadding = 32.dp.px
    private val verticalPadding = 14.dp.px

    lateinit var animations: Animations
        private set

    private lateinit var staticLayout: StaticLayout
    private lateinit var karaokeBaseLayout: StaticLayout
    private val paint = TextPaint().apply {
        textSize = 34.sp.px
        color = Color.WHITE
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
    }
    private val karaokeBasePaint = TextPaint(paint).apply {
        alpha = (UPCOMING_WORD_ALPHA * 255).roundToInt()
    }
    private val contentPaint = Paint().apply {
        xfermode = AnimationUtils.addXfermode
    }

    private val blurRenderNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        RenderNode(BLUR_NODE_NAME)
    } else {
        null
    }

    val hasWordTimings: Boolean
        get() = line.wordTimings.isNotEmpty()

    private var isActiveLine = false
    private var playbackPositionMs = Long.MIN_VALUE

    var textOffset: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            translationY = value
        }

    var textAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blurRenderNode?.alpha = textAlpha
            } else {
                alpha = value
            }
        }

    var textScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var blurRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val roundedValue = value.roundToInt()
            val renderEffect =
                if (roundedValue == 0) null
                else blurs?.get(roundedValue)
            blurRenderNode?.setRenderEffect(renderEffect)
        }

    init {
        isClickable = true
        isFocusable = true
        // No ripple. A lyric is text, not a button, and a grey box flashing over the words
        // reads as a control - the line coming into focus is the feedback.
        contentDescription = line.text
    }

    fun setAnimations(index: Int, globalOffset: Float, deviceHeight: Float) {
        animations = Animations(index, globalOffset, deviceHeight)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blurRenderNode?.discardDisplayList()
        }
    }

    /** Advances only the active Enhanced LRC line; unchanged positions do not redraw while paused. */
    fun updatePlaybackPosition(positionMs: Long) {
        if (!hasWordTimings || playbackPositionMs == positionMs) return
        playbackPositionMs = positionMs
        if (isActiveLine) postInvalidateOnAnimation()
    }

    private fun setActiveLine(active: Boolean) {
        if (isActiveLine == active) return
        isActiveLine = active
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (height < 0 || width < 0) return
        val hP = horizontalPadding.roundToInt()
        val vP = verticalPadding.roundToInt()

        val text = line.text
        val layoutWidth = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = layoutWidth - hP * 2

        Log.d("TAG", "tl: ${text.length}, $textWidth")

        staticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .build()
        karaokeBaseLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, karaokeBasePaint, textWidth)
            .build()

        val layoutHeight = staticLayout.height + vP * 2
        setMeasuredDimension(layoutWidth, layoutHeight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blurRenderNode?.apply {
                setPosition(hP, vP, layoutWidth - hP, layoutHeight - vP)
                // Relative to the node, not to the view. The node starts at the padding, so a
                // pivot measured in the view's coordinates scaled the current line about a
                // point below its own centre and left it sitting lower than its neighbours -
                // the line that looked misaligned while the others agreed with each other.
                pivotX = (layoutWidth - hP * 2) / 2f
                pivotY = (layoutHeight - vP * 2) / 2f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), contentPaint)
        val staticLayout = staticLayout
        val scale = textScale
        if (isActiveLine && hasWordTimings) {
            drawKaraokeLine(canvas, staticLayout, scale)
            canvas.restoreToCount(count)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (canvas.isHardwareAccelerated && blurRenderNode != null) {
                with(blurRenderNode) {
                    if (!hasDisplayList()) {
                        val recordingCanvas = beginRecording()
                        staticLayout.draw(recordingCanvas)
                        endRecording()
                    }
                    scaleX = scale
                    scaleY = scale
                    canvas.drawRenderNode(this)
                }
            } else {
                canvas.translate(horizontalPadding, verticalPadding)
                canvas.scale(scale, scale)
                paint.alpha = (textAlpha * 255).roundToInt()
                staticLayout.draw(canvas)
            }
        } else {
            canvas.translate(horizontalPadding, verticalPadding)
            canvas.scale(scale, scale)
            staticLayout.draw(canvas)
        }
        canvas.restoreToCount(count)
    }

    /**
     * Draws the quiet full line first, then reveals the sung copy through a character-aware clip.
     * Clipping each laid-out row separately makes long wrapped lyrics fill naturally instead of a
     * single vertical wipe cutting through every row at once.
     */
    private fun drawKaraokeLine(canvas: Canvas, layout: StaticLayout, scale: Float) {
        val alphaLayer = canvas.saveLayerAlpha(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            (textAlpha * 255).roundToInt(),
        )
        canvas.translate(horizontalPadding, verticalPadding)
        canvas.scale(scale, scale, layout.width / 2f, layout.height / 2f)
        karaokeBaseLayout.draw(canvas)

        val highlightOffset = line.highlightOffsetAt(playbackPositionMs).coerceIn(0f, line.text.length.toFloat())
        if (highlightOffset > 0f) {
            val highlighted = canvas.save()
            if (highlightOffset < line.text.length) {
                canvas.clipPath(highlightPath(layout, highlightOffset))
            }
            layout.draw(canvas)
            canvas.restoreToCount(highlighted)
        }
        canvas.restoreToCount(alphaLayer)
    }

    private fun highlightPath(layout: StaticLayout, offset: Float): Path {
        val textLength = line.text.length
        val wholeOffset = floor(offset).toInt().coerceIn(0, textLength)
        val drawableOffset = wholeOffset.coerceAtMost((textLength - 1).coerceAtLeast(0))
        val currentLine = layout.getLineForOffset(drawableOffset)
        val path = Path()

        for (layoutLine in 0 until currentLine) {
            path.addRect(
                layout.getLineLeft(layoutLine),
                layout.getLineTop(layoutLine).toFloat(),
                layout.getLineRight(layoutLine),
                layout.getLineBottom(layoutLine).toFloat(),
                Path.Direction.CW,
            )
        }

        val fraction = offset - wholeOffset
        val startX = layout.getPrimaryHorizontal(wholeOffset)
        val nextOffset = (wholeOffset + 1).coerceAtMost(textLength)
        val nextLine = layout.getLineForOffset(nextOffset.coerceAtMost((textLength - 1).coerceAtLeast(0)))
        val endX = if (nextLine == currentLine) layout.getPrimaryHorizontal(nextOffset) else {
            if (layout.getParagraphDirection(currentLine) > 0) layout.getLineRight(currentLine)
            else layout.getLineLeft(currentLine)
        }
        val edge = startX + (endX - startX) * fraction
        val left = layout.getLineLeft(currentLine)
        val right = layout.getLineRight(currentLine)
        val top = layout.getLineTop(currentLine).toFloat()
        val bottom = layout.getLineBottom(currentLine).toFloat()
        if (layout.getParagraphDirection(currentLine) > 0) {
            path.addRect(left, top, edge.coerceIn(left, right), bottom, Path.Direction.CW)
        } else {
            path.addRect(edge.coerceIn(left, right), top, right, bottom, Path.Direction.CW)
        }
        return path
    }

    /** Tapping a line jumps to it. This was left as a comment upstream and did nothing. */
    override fun performClick(): Boolean {
        super.performClick()
        onSeek?.invoke(line.timestamp)
        return true
    }

    inner class Animations(
        private val index: Int,
        private var globalOffset: Float,
        private val deviceHeight: Float
    ) {
        private var targetOffset = 0f

        private val offsetFractionAnimator = floatAnimator(700L, interpolator = offsetFractionInterpolator) {
            textOffset = targetOffset * (1f - it.currentValue)
        }

        private val alphaAnimator = floatAnimator(500L, interpolator = AnimationUtils.decelerateInterpolator) {
            textAlpha = it.currentValue
        }

        private val scaleAnimator = floatAnimator(500L, interpolator = AnimationUtils.decelerateInterpolator) {
            textScale = it.currentValue
        }

        private val blurRadiusAnimator = floatAnimator(100L) {
            blurRadius = it.currentValue
        }

        fun getGlobalOffset() = globalOffset

        fun setGlobalOffset(offset: Float) {
            globalOffset = offset
        }

        fun cancelBlur() {
            blurRadiusAnimator.snapTo(0f)
        }

        fun checkIsInScreen(scrollOffset: Float, targetOffset: Float): Boolean {
            val previousScrollOffset = scrollOffset - targetOffset
            val height = height
            return if (previousScrollOffset < scrollOffset) {
                globalOffset + height > previousScrollOffset && globalOffset < scrollOffset + deviceHeight
            } else {
                globalOffset + height > scrollOffset && globalOffset < previousScrollOffset + deviceHeight
            }
        }

        fun updateImmediately(targetIndex: Int) {
            val isActivated = index == targetIndex
            setActiveLine(isActivated)
            val targetAlpha = alphaFor(index, targetIndex)
            val targetScale = if (isActivated) ACTIVE_SCALE else INACTIVE_SCALE
            val targetBlurRadius = (abs(index - targetIndex) * blurRadiusStep).coerceAtMost(maxBlurRadius)

            offsetFractionAnimator.snapTo(1f)
            alphaAnimator.snapTo(targetAlpha)
            scaleAnimator.snapTo(targetScale)
            blurRadiusAnimator.snapTo(targetBlurRadius)
        }

        fun update(targetIndex: Int, preventBlurUpdate: Boolean = false) {
            val isActivated = index == targetIndex
            setActiveLine(isActivated)
            val targetAlpha = alphaFor(index, targetIndex)
            val targetScale = if (isActivated) ACTIVE_SCALE else INACTIVE_SCALE
            val targetBlurRadius = (abs(index - targetIndex) * blurRadiusStep).coerceAtMost(maxBlurRadius)

            val delay = if (index < targetIndex) {
                0L
            } else {
                ((index - targetIndex) * 20L + 10L).coerceAtMost(190L)
            }
            val secondaryDelay = delay + 250L

            targetOffset = textOffset
            offsetFractionAnimator.startDelay = delay
            offsetFractionAnimator.start()

            if (alphaAnimator.targetValue != targetAlpha) {
                alphaAnimator.startDelay = secondaryDelay
                alphaAnimator.animateTo(targetAlpha)
            }

            if (scaleAnimator.targetValue != targetScale) {
                scaleAnimator.startDelay = secondaryDelay
                scaleAnimator.animateTo(targetScale)
            }

            if (!preventBlurUpdate) {
                if (blurRadiusAnimator.targetValue != targetBlurRadius) {
                    blurRadiusAnimator.startDelay = secondaryDelay
                    blurRadiusAnimator.animateTo(targetBlurRadius)
                }
            }
        }
    }

    private companion object {
        const val BLUR_NODE_NAME = "LyricsLineViewBlurNode"

        /**
         * How visible a line is, by how far it is from the one being sung.
         *
         * Three lines carry the song - the one before, the one now, the one next - so those are
         * the ones lifted out. The rest stay legible rather than being erased: the whole lyric is
         * still there to read ahead in and scroll through, just quieter.
         */
        fun alphaFor(index: Int, targetIndex: Int): Float = when (abs(index - targetIndex)) {
            0 -> ACTIVE_ALPHA
            1 -> NEIGHBOUR_ALPHA
            else -> INACTIVE_ALPHA
        }

        const val NEIGHBOUR_ALPHA = 0.45f

        const val ACTIVE_ALPHA = 0.9f
        const val UPCOMING_WORD_ALPHA = 0.32f
        // Readable, not shouting. At 0.2 the rest of the song disappeared entirely against
        // a pale album backdrop, which is not the same as being de-emphasised.
        const val INACTIVE_ALPHA = 0.34f
        const val ACTIVE_SCALE = 1f
        const val INACTIVE_SCALE = 0.96f
        val maxBlurRadius = 8.dp.px
        val blurRadiusStep = 2.dp.px

        val offsetFractionInterpolator = PathInterpolator(0.6f, 0f, 0.2f, 1f)

        val blurs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (1..maxBlurRadius.roundToInt()).associateWith {
                val radius = it.toFloat()
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
            }
        } else {
            null
        }

    }
}
