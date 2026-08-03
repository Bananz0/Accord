package org.akanework.gramophone.ui.components.cupertino

import android.content.Context
import android.util.AttributeSet
import android.widget.EdgeEffect
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView

/**
 * A [RecyclerView] that rubber-bands at the ends instead of showing Android's stock edge glow.
 *
 * The upstream Accord layouts are built against `uk.akane.cupertino.scroll.CupertinoRecyclerView`,
 * which is not present in the public Cupertino snapshot, so this stands in for it. Layouts ported
 * from upstream have their references rewritten to this class.
 *
 * The pull is translated into a spring-backed offset of the whole list, which is what gives the
 * Apple-style "the content is attached to your finger" feel rather than a glow at the boundary.
 */
class CupertinoRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    init {
        // The glow and the rubber band together look like a bug; the band replaces it.
        overScrollMode = OVER_SCROLL_NEVER
        edgeEffectFactory = BounceEdgeEffectFactory()
    }

    private inner class BounceEdgeEffectFactory : EdgeEffectFactory() {

        override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
            return object : EdgeEffect(view.context) {

                private var translationAnim: SpringAnimation? = null

                /** Positive at the top edge, negative at the bottom, matching scroll direction. */
                private val sign = if (direction == DIRECTION_BOTTOM) -1f else 1f

                override fun onPull(deltaDistance: Float) = handlePull(deltaDistance)

                override fun onPull(deltaDistance: Float, displacement: Float) =
                    handlePull(deltaDistance)

                private fun handlePull(deltaDistance: Float) {
                    translationAnim?.cancel()
                    // Scale the drag by the view height so the resistance feels the same on any
                    // screen size, and damp it so the list never runs away from the finger.
                    val delta = sign * view.height * deltaDistance * PULL_DAMPING
                    view.translationY += delta
                }

                override fun onRelease() {
                    if (view.translationY != 0f) settle()
                }

                override fun onAbsorb(velocity: Int) {
                    translationAnim?.cancel()
                    settle(sign * velocity * FLING_SCALE)
                }

                private fun settle(startVelocity: Float = 0f) {
                    translationAnim = SpringAnimation(view, SpringAnimation.TRANSLATION_Y, 0f)
                        .apply {
                            if (startVelocity != 0f) setStartVelocity(startVelocity)
                            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                        }
                        .also { it.start() }
                }

                // Nothing is drawn: the displacement itself is the effect.
                override fun draw(canvas: android.graphics.Canvas) = false

                override fun isFinished() = translationAnim?.isRunning != true
            }
        }
    }

    private companion object {
        const val PULL_DAMPING = 0.3f
        const val FLING_SCALE = 0.3f
    }
}
