package uk.akane.accord.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.graphics.Canvas

/**
 * The artwork behind a station card - the soft colour field the 1.0-stable build uses for its
 * "Made for you" cards, drawn rather than shipped as an image so every station gets its own.
 *
 * Colours and layout come from the station's id, so a station looks the same each time it appears
 * while no two look alike. Three styles rather than one, because a row of cards drawn the same way
 * reads as a repeated texture instead of as separate stations.
 *
 * Every instance shares one frame callback. A screenful of these each running its own
 * [android.animation.ValueAnimator] kept the main thread busy enough that a tap had to wait for a
 * gap before it could start a fragment transaction, which is why opening a station used to feel
 * slower and less smooth than opening an album.
 */
class StationArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val motion = ProceduralStationMotion()

    /** Wall-clock at which this view's drift began, so its phase can be derived per frame. */
    private var startedAtNanos = 0L
    private var lastFrameNanos = 0L

    /**
     * How often this view redraws, in nanoseconds.
     *
     * A card is a thumbnail and the drift is slow, so redrawing it every frame buys nothing
     * visible. The detail header fills the screen and gets every frame.
     */
    private var frameIntervalNanos = CARD_FRAME_INTERVAL_NANOS

    var animated: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value && isAttachedToWindow) register() else unregister()
        }

    /** Full frame rate, for the near-full-screen header where the drift is actually visible. */
    fun setFullFrameRate() {
        frameIntervalNanos = 0L
    }

    /**
     * @param seed anything stable for this station - its id. Drives both the palette and the style.
     */
    fun bind(seed: String) {
        if (motion.bind(seed)) startedAtNanos = 0L
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) register()
    }

    override fun onDetachedFromWindow() {
        unregister()
        super.onDetachedFromWindow()
    }

    private fun register() {
        if (active.add(this)) ensureTicking()
    }

    private fun unregister() {
        active.remove(this)
    }

    /** Advances the drift from elapsed time. Returns true if the view needs redrawing. */
    private fun onFrame(frameTimeNanos: Long): Boolean {
        if (startedAtNanos == 0L) {
            startedAtNanos = frameTimeNanos - (motion.phase * motion.durationMs * 1_000_000L).toLong()
        }
        if (frameTimeNanos - lastFrameNanos < frameIntervalNanos) return false
        lastFrameNanos = frameTimeNanos

        val periodNanos = motion.durationMs * 1_000_000.0
        val elapsed = (frameTimeNanos - startedAtNanos) / periodNanos
        motion.phase = ((elapsed % 1.0) + 1.0).toFloat() % 1f
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        motion.draw(canvas, w, h)
    }

    companion object {
        /** ~20fps. The field drifts over tens of seconds; nobody can see the difference. */
        private const val CARD_FRAME_INTERVAL_NANOS = 50_000_000L

        private val active = mutableSetOf<StationArtView>()
        private var ticking = false

        private val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (active.isEmpty()) {
                    ticking = false
                    return
                }
                // Copied because a view detaching mid-iteration would otherwise mutate the set.
                active.toList().forEach { view ->
                    if (!view.isAttachedToWindow) {
                        active.remove(view)
                    } else if (view.onFrame(frameTimeNanos)) {
                        view.invalidate()
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun ensureTicking() {
            if (ticking) return
            ticking = true
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }
}
