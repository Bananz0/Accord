package uk.akane.accord.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Flies a tapped card's artwork into the place the detail screen shows it, and back on the way out.
 *
 * Cupertino's switcher is not a `FragmentTransaction`: it slides two containers by translationX
 * itself, so `addSharedElement` has nothing to hook into. A copy of the artwork goes into an
 * overlay above both containers instead and moves between the two rectangles on the same curve and
 * duration as the slide underneath.
 *
 * Two things keep it inside a 120Hz frame budget, which is 8.3ms rather than the 16.7ms a 60Hz
 * display allows:
 *
 *  - The overlay is laid out once at its final size and moved with scale and translation. Animating
 *    width, height and margins means a layout pass every frame on a near-full-screen view.
 *  - It is promoted to a hardware layer for the duration. The station field is several full-size
 *    shader passes; without a layer that work is repeated on every frame of the flight, and with
 *    one it is rasterised once and the GPU only transforms the result.
 */
object HeroMorph {

    /** The switcher's Cupertino profile, so the artwork and the screen move as one thing. */
    private const val DURATION_MS = 500L
    private val INTERPOLATOR = PathInterpolator(0.2833f, 0.99f, 0.31833f, 0.99f)

    /** Long enough to bridge a late-arriving destination, short enough not to read as a lag. */
    private const val HANDOFF_FADE_MS = 160L

    /**
     * No rounded corner while in flight.
     *
     * Holding a constant on-screen radius on a scaled view means recomputing the outline every
     * frame, and invalidating an outline discards the hardware layer - which is the whole reason
     * the flight is cheap. Half a second of square corners is a better trade than a re-rasterised
     * near-full-screen view on every frame of it.
     */

    /**
     * Where a detail screen draws its header, in the coordinates of the activity's content view.
     *
     * Kept here rather than recomputed by callers so the morph and the screens it targets cannot
     * drift apart: `StationDetailFragment` and `AlbumDetailFragment` both size their header to this
     * same fraction of the display.
     */
    const val HEADER_HEIGHT_FRACTION = 0.7f

    fun headerBounds(activity: Activity, root: View): Rect = Rect(
        0,
        0,
        root.width,
        (activity.resources.displayMetrics.heightPixels * HEADER_HEIGHT_FRACTION).toInt(),
    )

    /**
     * The card the last forward morph came from, so leaving can retrace it.
     *
     * Held as bounds rather than as the view: the row it lives in is recycled while the detail
     * screen is open, so by the time back is pressed that view may be showing a different station.
     */
    private class Snapshot(
        val from: Rect,
        val stationSeed: String?,
        val albumArt: Drawable?,
    )

    private var lastForward: Snapshot? = null

    /** Forgets the way back, for a navigation that did not come from a card. */
    fun forget() {
        lastForward = null
    }

    /**
     * Brings a detail screen's chrome up alongside the artwork rather than behind it.
     *
     * The title, subtitle, track count and transport buttons used to appear once the flight had
     * landed, which put a beat of empty artwork between the tap and a usable screen and made the
     * whole thing feel slower than it is. Starting them now means they arrive as the artwork grows
     * and the screen reads as one movement.
     *
     * No start delay, and shorter than the flight. This runs when the incoming fragment reports
     * its content loaded, which is already some way into the morph - inflation is not free - so
     * any delay added here compounds with that gap and lands the text after the artwork has
     * settled, which is the thing that read as slow.
     */
    fun fadeInChrome(vararg views: View?) {
        views.filterNotNull().forEach { view ->
            view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(CHROME_FADE_MS)
                .setInterpolator(INTERPOLATOR)
                .start()
        }
    }

    private const val CHROME_FADE_MS = 200L

    /**
     * @param source the tapped view, used only for its position and size on screen.
     * @param stationSeed the station's title when the destination draws procedural art, else null.
     * @param albumArt the cover, when the destination is an album.
     */
    fun play(
        activity: Activity,
        source: View,
        stationSeed: String? = null,
        albumArt: Drawable? = null,
    ) {
        if (stationSeed == null && albumArt == null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val from = source.boundsIn(root) ?: return
        val header = headerBounds(activity, root)
        if (header.width() <= 0 || header.height() <= 0) return

        lastForward = Snapshot(from, stationSeed, albumArt)
        animate(activity, root, stationSeed, albumArt, full = header, from = from, to = header)
    }

    /**
     * Retraces the last forward morph, shrinking the header back onto the card it came from.
     *
     * Consumed once: a second back press has no card to return to, and replaying the same flight
     * would send artwork to wherever that card used to be.
     */
    fun playReverse(activity: Activity) {
        val snapshot = lastForward ?: return
        lastForward = null

        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val header = headerBounds(activity, root)
        if (header.width() <= 0 || header.height() <= 0) return

        animate(
            activity, root,
            snapshot.stationSeed, snapshot.albumArt,
            full = header,
            from = header,
            to = snapshot.from,
        )
    }

    /**
     * @param full the size the overlay is laid out at, once. Both directions use the header's size,
     *   so the artwork is rasterised at the resolution it is seen at largest.
     */
    private fun animate(
        activity: Activity,
        root: ViewGroup,
        stationSeed: String?,
        albumArt: Drawable?,
        full: Rect,
        from: Rect,
        to: Rect,
    ) {
        val holder = FrameLayout(activity).apply {
            addView(
                artView(activity, stationSeed, albumArt),
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
            layoutParams = FrameLayout.LayoutParams(full.width(), full.height())
            pivotX = 0f
            pivotY = 0f
            elevation = 100f
            // Rasterised once; the flight is then pure texture transform on the render thread.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        root.addView(holder)

        val startScaleX = from.width().toFloat() / full.width()
        val startScaleY = from.height().toFloat() / full.height()
        val endScaleX = to.width().toFloat() / full.width()
        val endScaleY = to.height().toFloat() / full.height()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = INTERPOLATOR
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                holder.scaleX = lerp(startScaleX, endScaleX, t)
                holder.scaleY = lerp(startScaleY, endScaleY, t)
                holder.translationX = lerp(from.left.toFloat(), to.left.toFloat(), t)
                holder.translationY = lerp(from.top.toFloat(), to.top.toFloat(), t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Faded rather than removed outright. The switcher does not begin its slide
                    // until the incoming fragment reports content loaded, which can land after this
                    // finishes; snapping the overlay away then flashes the other screen into view.
                    holder.animate()
                        .alpha(0f)
                        .setDuration(HANDOFF_FADE_MS)
                        .withEndAction {
                            holder.setLayerType(View.LAYER_TYPE_NONE, null)
                            root.removeView(holder)
                        }
                        .start()
                }
            })
            start()
        }
    }

    private fun artView(
        activity: Activity,
        stationSeed: String?,
        albumArt: Drawable?,
    ): View = if (stationSeed != null) {
        StationArtView(activity).apply {
            // Held still: this lives for half a second, and a drifting field would only disagree
            // with the header it is about to become. It also keeps the hardware layer valid, which
            // an invalidation per frame would throw away.
            animated = false
            bind(stationSeed)
        }
    } else {
        ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(albumArt)
        }
    }

    /** Where [this] sits inside [ancestor], or null when it is not on screen. */
    private fun View.boundsIn(ancestor: View): Rect? {
        if (width <= 0 || height <= 0) return null
        val mine = IntArray(2).also { getLocationInWindow(it) }
        val theirs = IntArray(2).also { ancestor.getLocationInWindow(it) }
        val left = mine[0] - theirs[0]
        val top = mine[1] - theirs[1]
        return Rect(left, top, left + width, top + height)
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
}
