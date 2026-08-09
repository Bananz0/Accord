package uk.akane.accord.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

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

    private val motion = ProceduralStationMotion()
    private var animator: ValueAnimator? = null

    /**
     * Whether the colour field drifts.
     *
     * Worth it on a full-screen header, wasteful on a list of thumbnails: every visible card was
     * running its own infinite animator invalidating each frame, so the main thread was never idle
     * on the home feed and a tap had to wait for the next gap to start a fragment transaction.
     * That was the lag on opening a station that albums, being plain images, never had. At card
     * size the drift is not visible anyway.
     */
    var animated: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                if (isAttachedToWindow) startAnimation()
            } else {
                stopAnimation()
            }
        }

    /**
     * @param seed anything stable for this station - its id. Drives both the palette and the style.
     */
    fun bind(seed: String) {
        if (motion.bind(seed) && animator != null) restartAnimation()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Slow enough to read as drifting light rather than motion, and cheap: one float per frame
        // driving a shader that is rebuilt only while the card is on screen.
        if (animated) startAnimation()
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun startAnimation() {
        if (animator != null || !animated) return
        val start = motion.phase
        animator = ValueAnimator.ofFloat(start, start + 1f).apply {
            duration = motion.durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                motion.phase = normalizePhase(it.animatedValue as Float)
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun restartAnimation() {
        animator?.cancel()
        animator = null
        if (isAttachedToWindow) startAnimation()
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

        motion.draw(canvas, w, h)
    }

    companion object {
        private fun normalizePhase(value: Float): Float = ((value % 1f) + 1f) % 1f
    }
}
