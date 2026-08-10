package uk.akane.accord.ui.components

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Flies a tapped card's artwork into the place the detail screen will show it, and back again.
 *
 * Cupertino's switcher is not a `FragmentTransaction`: it slides two containers by translationX
 * itself, so `addSharedElement` has nothing to hook into. Instead a copy of the artwork is placed
 * in an overlay above both containers and moved from where the card is to where the header will
 * be, on the same curve and duration as the slide underneath.
 *
 * The overlay is laid out once at its final size and moved with scale and translation. Animating
 * width, height and margins instead means a layout pass on every frame of a half-second animation
 * over a near-full-screen view, which is what dropped frames on the way in. Transforms are handled
 * on the render thread and cost nothing per frame.
 *
 * Station art copies perfectly because [ProceduralStationMotion] is a pure function of the seed -
 * the same string draws the same field. Album art copies because the drawable is already decoded
 * in the card.
 */
object HeroMorph {

    /** The switcher's Cupertino profile, so the artwork and the screen move as one thing. */
    private const val DURATION_MS = 500L
    private val INTERPOLATOR = PathInterpolator(0.2833f, 0.99f, 0.31833f, 0.99f)

    /** Long enough to bridge a late-arriving destination, short enough not to read as a lag. */
    private const val HANDOFF_FADE_MS = 160L

    /** Cards are rounded; the header is not. */
    private const val START_CORNER_DP = 12f

    /**
     * Where a detail screen draws its header, in the coordinates of the activity's content view.
     *
     * Kept here rather than recomputed by callers so the morph and the screens it targets cannot
     * drift apart: `StationDetailFragment` and `AlbumDetailFragment` both size their header
     * container to this same fraction of the display.
     */
    const val HEADER_HEIGHT_FRACTION = 0.7f

    fun headerBounds(activity: Activity, root: View): Rect = Rect(
        0,
        0,
        root.width,
        (activity.resources.displayMetrics.heightPixels * HEADER_HEIGHT_FRACTION).toInt(),
    )

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
        if (source.width == 0 || source.height == 0) return

        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val from = source.boundsIn(root) ?: return
        val to = headerBounds(activity, root)
        if (to.width() <= 0 || to.height() <= 0) return

        val art = artView(activity, stationSeed, albumArt)
        val holder = FrameLayout(activity).apply {
            clipToOutline = true
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            // Laid out once, at the destination's size. Everything after this is a transform.
            layoutParams = FrameLayout.LayoutParams(to.width(), to.height())
            pivotX = 0f
            pivotY = 0f
            elevation = source.elevation + 100f
        }

        val startCornerPx = START_CORNER_DP * activity.resources.displayMetrics.density
        var corner = startCornerPx
        holder.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }

        root.addView(holder)

        val startScaleX = from.width().toFloat() / to.width()
        val startScaleY = from.height().toFloat() / to.height()
        holder.scaleX = startScaleX
        holder.scaleY = startScaleY
        holder.translationX = from.left.toFloat()
        holder.translationY = from.top.toFloat()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = INTERPOLATOR
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                holder.scaleX = lerp(startScaleX, 1f, t)
                holder.scaleY = lerp(startScaleY, 1f, t)
                holder.translationX = lerp(from.left.toFloat(), to.left.toFloat(), t)
                holder.translationY = lerp(from.top.toFloat(), to.top.toFloat(), t)
                // Divided by the scale so the corner keeps a constant radius on screen while the
                // view is still shrunk, rather than appearing to grow with it.
                val target = startCornerPx * (1f - t)
                val scale = holder.scaleX.coerceAtLeast(0.01f)
                if (target != corner) {
                    corner = target / scale
                    holder.invalidateOutline()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Faded rather than removed outright. The switcher does not begin its slide
                    // until the incoming fragment reports its content loaded, which can land after
                    // this finishes; snapping the overlay away then would flash the previous
                    // screen back into view.
                    holder.animate()
                        .alpha(0f)
                        .setDuration(HANDOFF_FADE_MS)
                        .withEndAction { root.removeView(holder) }
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
            // with the header it is about to become.
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
