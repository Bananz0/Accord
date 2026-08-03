package org.akanework.gramophone.ui.components.cupertino

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * A [NestedScrollView] that rubber-bands past its ends instead of showing the stock edge glow.
 *
 * Stands in for `uk.akane.cupertino.scroll.CupertinoNestedScrollView`, which the upstream Accord
 * layouts use but the public Cupertino snapshot does not ship. See [CupertinoRecyclerView].
 */
class CupertinoNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private var settleAnim: SpringAnimation? = null

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean {
        if (isTouchEvent && deltaY != 0) {
            val atTop = scrollY + deltaY < 0
            val atBottom = scrollY + deltaY > scrollRangeY
            if (atTop || atBottom) {
                settleAnim?.cancel()
                // Damped so the content trails the finger rather than matching it exactly.
                translationY -= deltaY * PULL_DAMPING
                return true
            }
        }
        return super.overScrollBy(
            deltaX, deltaY, scrollX, scrollY,
            scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent
        )
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> if (translationY != 0f) settle()
        }
        return handled
    }

    private fun settle() {
        settleAnim = SpringAnimation(this, SpringAnimation.TRANSLATION_Y, 0f)
            .apply {
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
            .also { it.start() }
    }

    private companion object {
        const val PULL_DAMPING = 0.3f
    }
}
