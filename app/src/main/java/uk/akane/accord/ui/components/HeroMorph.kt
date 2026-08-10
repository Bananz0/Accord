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
 * Flies a tapped card's artwork into the place the detail screen will show it.
 *
 * Cupertino's switcher is not a `FragmentTransaction`: it slides two containers by translationX
 * itself, so `addSharedElement` has nothing to hook into. Instead a copy of the artwork is placed
 * in an overlay above both containers and animated from where the card is to where the header will
 * be, on the same curve and duration as the slide underneath. When it lands it is sitting exactly
 * over the real header, so removing it is invisible.
 *
 * Station art copies perfectly because [ProceduralStationMotion] is a pure function of the seed -
 * the same string draws the same field at any size. Album art copies because the drawable is
 * already decoded in the card.
 */
object HeroMorph {

    /** The switcher's Cupertino profile, so the artwork and the screen move as one thing. */
    private const val DURATION_MS = 500L
    private val INTERPOLATOR = PathInterpolator(0.2833f, 0.99f, 0.31833f, 0.99f)

    /** Matches the detail screens' `headerHeight`: 70% of the display. */
    private const val HEADER_HEIGHT_FRACTION = 0.7f

    /** Long enough to bridge a late-arriving destination, short enough not to read as a lag. */
    private const val HANDOFF_FADE_MS = 160L

    /** Cards are rounded; the header is not. */
    private const val START_CORNER_DP = 12f

    /**
     * @param source the tapped view, used only for its position and size on screen.
     * @param stationSeed the station's title when the card draws procedural art, else null.
     * @param albumArt the card's already-decoded cover, when it has one.
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
        val to = Rect(
            0,
            0,
            root.width,
            (activity.resources.displayMetrics.heightPixels * HEADER_HEIGHT_FRACTION).toInt(),
        )

        val density = activity.resources.displayMetrics.density
        val startCornerPx = START_CORNER_DP * density

        val art: View = if (stationSeed != null) {
            StationArtView(activity).apply {
                // Held still: this lives for half a second and a drifting field would only
                // disagree with the header it is about to become.
                animated = false
                bind(stationSeed)
            }
        } else {
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(albumArt)
            }
        }

        val holder = FrameLayout(activity).apply {
            clipToOutline = true
            addView(art, FrameLayout.LayoutParams(MATCH, MATCH))
            layoutParams = FrameLayout.LayoutParams(from.width(), from.height()).also {
                it.leftMargin = from.left
                it.topMargin = from.top
            }
            // Above the sliding containers and the floating player alike.
            elevation = source.elevation + 100f
        }
        root.addView(holder)

        var corner = startCornerPx
        holder.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = INTERPOLATOR
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                corner = startCornerPx * (1f - t)
                holder.updateGeometry(
                    left = lerp(from.left, to.left, t),
                    top = lerp(from.top, to.top, t),
                    width = lerp(from.width(), to.width(), t),
                    height = lerp(from.height(), to.height(), t),
                )
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Faded rather than removed outright. The switcher does not begin its slide
                    // until the incoming fragment reports its content loaded, which can land after
                    // this finishes; snapping the overlay away at that moment would flash the home
                    // screen back into view. A short fade covers the gap, and it is dissolving
                    // into an identical header when the timing does line up.
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

    private fun FrameLayout.updateGeometry(left: Int, top: Int, width: Int, height: Int) {
        val params = layoutParams as FrameLayout.LayoutParams
        params.leftMargin = left
        params.topMargin = top
        params.width = width
        params.height = height
        layoutParams = params
        invalidateOutline()
    }

    /** Where [this] sits inside [ancestor], or null when it is not on screen. */
    private fun View.boundsIn(ancestor: View): Rect? {
        val mine = IntArray(2).also { getLocationInWindow(it) }
        val theirs = IntArray(2).also { ancestor.getLocationInWindow(it) }
        val left = mine[0] - theirs[0]
        val top = mine[1] - theirs[1]
        if (width <= 0 || height <= 0) return null
        return Rect(left, top, left + width, top + height)
    }

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt()

    private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
}
