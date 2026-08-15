package uk.akane.accord.ui.components.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.akane.accord.ui.components.FadingVerticalEdgeLayout
import uk.akane.accord.ui.components.scroll.ListenableNestedScrollView
import kotlin.math.roundToInt

/**
 * @param positionProvider current playback position in milliseconds. Upstream ships this view as a
 *   demo - it walks a hardcoded verse on a five second timer with no idea what is playing - so real
 *   lyrics need both a source ([setLyrics]) and a clock to follow.
 */
class LyricsViewModel(
    private val context: Context,
    private val positionProvider: () -> Long = { 0L },
    /** Where a tapped line should take playback. */
    private val onSeek: (Long) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var entranceAnimator: ValueAnimator? = null
    private var lyricsLayoutGeneration = 0

    private val lyrics = MutableStateFlow(Lyrics.Empty)

    private val sampleLyrics = Lyrics(
        listOf(
            LyricsLine(0, null, "For the puppets on TV", null),
            LyricsLine(5_000, null, "There is comfort in the strings", null),
            LyricsLine(10_000, null, "If you're gonna control me", null),
            LyricsLine(15_000, null, "At least make it interesting theatrically", null),
            LyricsLine(20_000, null, "How does it feel to be free?", null),
            LyricsLine(25_000, null, "Why don't you ask yourself?", null),
            LyricsLine(30_000, null, "The gate opened for me", null),
            LyricsLine(35_000, null, "So I leaped", null),
            LyricsLine(40_000, null, "Run and through this forest", null),
            LyricsLine(45_000, null, "With an arrow from the brother sun", null),
            LyricsLine(50_000, null, "Trees, weeds, leaves, and flowers", null),
            LyricsLine(55_000, null, "Feel we the breathe of the four season", null),
        )
    )

    fun onViewCreated(view: View, savedInstanceState: Bundle? = null) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle

        val fadingEdgeLayout = view as FadingVerticalEdgeLayout
        val scrollView = fadingEdgeLayout.getChildAt(0) as ListenableNestedScrollView
        val lyricsView = scrollView.getChildAt(0) as LyricsView
        lyricsView.onSeek = onSeek

        var isUserScrolling = false

        scope.launch {
            lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                scrollView.collectUserAction(
                    onActionStart = {
                        scrollView.isVerticalScrollBarEnabled = true
                        lyricsView.forEach { view ->
                            view as LyricsLineView
                            view.animations.cancelBlur()
                            view.visibility = View.VISIBLE
                        }
                        isUserScrolling = true
                    },
                    onActionEnd = {
                        isUserScrolling = false
                    }
                )
            }
        }

        fun updateCurrentIndex(index: Int) {
            Log.d("TAG", "ci: $index")
            if (!isUserScrolling) {
                val currentLineChild = lyricsView.getChildAt(index) as LyricsLineView? ?: return
                val currentOffset = scrollView.scrollY.toFloat()
                val maxScrollOffset =
                    (lyricsView.measuredHeight + lyricsView.contentPaddingTop - scrollView.measuredHeight).toFloat()
                val targetOffset =
                    currentLineChild.animations.getGlobalOffset().coerceAtMost(maxScrollOffset) -
                            lyricsView.contentPaddingTop
                Log.d("TAG", "co: $currentOffset, mso: $maxScrollOffset, to: $targetOffset")
                val deltaOffset = targetOffset - currentOffset

                scrollView.isVerticalScrollBarEnabled = false
                scrollView.scrollTo(0, targetOffset.roundToInt())

                val scrollOffset = scrollView.scrollY.toFloat()
                lyricsView.forEach { child: View ->
                    child as LyricsLineView
                    val targetTransitionY = child.textOffset + deltaOffset
                    if (child.animations.checkIsInScreen(scrollOffset, targetTransitionY)) {
                        child.textOffset = targetTransitionY
                        child.animations.update(index)
                        if (child.visibility != View.VISIBLE) {
                            child.visibility = View.VISIBLE
                        }
                    } else {
                        if (child.visibility != View.GONE) {
                            child.visibility = View.GONE
                        }
                        child.animations.updateImmediately(index)
                    }
                }
            } else {
                lyricsView.forEach { child: View ->
                    child as LyricsLineView
                    child.textOffset = 0f
                    child.animations.update(index, true)
                }
            }
        }

        var isLayoutFinished = false
        var lastIndex = -1
        fun updateOnLayout() {
            entranceAnimator?.removeAllListeners()
            entranceAnimator?.cancel()
            entranceAnimator = null
            val layoutGeneration = ++lyricsLayoutGeneration

            lyricsView.doOnLayout {
                isLayoutFinished = false

                // Build the first visible frame around the lyric that is playing now. Give it a
                // little context above, then gently settle it into the normal followed position.
                // The current line is highlighted throughout; this is viewport motion, not a
                // replay of every lyric between line zero and the current timestamp.
                val position = positionProvider()
                val index = getCurrentLyricsLineIndex(position)

                val currentLineChild = lyricsView.getChildAt(index) as? LyricsLineView ?: return@doOnLayout
                val contextIndex = (index - INITIAL_CONTEXT_LINES).coerceAtLeast(0)
                val contextLineChild = lyricsView.getChildAt(contextIndex) as? LyricsLineView ?: currentLineChild
                val maxScrollOffset =
                    (lyricsView.measuredHeight + lyricsView.contentPaddingTop - scrollView.measuredHeight)
                        .coerceAtLeast(0)
                        .toFloat()
                val startOffset =
                    (contextLineChild.animations.getGlobalOffset() - lyricsView.contentPaddingTop)
                        .coerceIn(0f, maxScrollOffset)
                val targetOffset =
                    (currentLineChild.animations.getGlobalOffset() - lyricsView.contentPaddingTop)
                        .coerceIn(0f, maxScrollOffset)

                scrollView.isVerticalScrollBarEnabled = false
                scrollView.scrollTo(0, startOffset.roundToInt())

                lyricsView.forEach { child: View ->
                    child as LyricsLineView
                    child.animations.updateImmediately(index)
                    child.visibility = View.VISIBLE
                }
                currentLineChild.updatePlaybackPosition(position)

                lastIndex = index

                // Waiting one frame ensures the contextual starting position is actually drawn.
                // A short eased settle is deliberate: opening lyrics should feel alive without
                // delaying the 200 ms playback follower below.
                scrollView.postOnAnimation {
                    if (layoutGeneration != lyricsLayoutGeneration || !scope.isActive) return@postOnAnimation
                    if (startOffset.roundToInt() == targetOffset.roundToInt()) {
                        isLayoutFinished = true
                        return@postOnAnimation
                    }

                    val animator = ValueAnimator.ofInt(
                        startOffset.roundToInt(),
                        targetOffset.roundToInt(),
                    ).apply {
                        duration = INITIAL_SETTLE_DURATION_MS
                        interpolator = INITIAL_SETTLE_INTERPOLATOR
                        addUpdateListener { valueAnimator ->
                            scrollView.scrollTo(0, valueAnimator.animatedValue as Int)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (entranceAnimator === animation &&
                                    layoutGeneration == lyricsLayoutGeneration
                                ) {
                                    entranceAnimator = null
                                    isLayoutFinished = true
                                }
                            }
                        })
                    }
                    entranceAnimator = animator
                    animator.start()
                }
            }
        }

        lyricsView.update(lyrics.value)
        updateOnLayout()

        // Follow the player rather than a timer. getCurrentLyricsLineIndex already maps a position
        // onto a line; upstream simply never called it with a real one.
        scope.launch {
            while (isActive) {
                var nextPollMs = POSITION_POLL_MS
                if (lyrics.value != Lyrics.Empty) {
                    val position = positionProvider()
                    val index = getCurrentLyricsLineIndex(position)
                    if (isLayoutFinished && index >= 0) {
                        if (index != lastIndex) {
                            lastIndex = index
                            updateCurrentIndex(index)
                        }
                        val currentLine = lyricsView.getChildAt(index) as? LyricsLineView
                        currentLine?.updatePlaybackPosition(position)
                        if (currentLine?.hasWordTimings == true) {
                            nextPollMs = WORD_POSITION_POLL_MS
                        }
                    }
                }
                delay(nextPollMs)
            }
        }

        this.applyPending = { newLyrics ->
            lyricsView.update(newLyrics)
            updateOnLayout()
        }
        pendingLyrics?.let { applyPending?.invoke(it); pendingLyrics = null }
    }

    /** Hooked up once the view exists; before that, lyrics arriving are held in [pendingLyrics]. */
    private var applyPending: ((Lyrics) -> Unit)? = null
    private var pendingLyrics: Lyrics? = null

    /** Replaces what is on screen. [Lyrics.Empty] hides the view's content. */
    fun setLyrics(newLyrics: Lyrics) {
        lyrics.value = newLyrics
        val apply = applyPending
        if (apply == null) pendingLyrics = newLyrics else apply(newLyrics)
    }

    /**
     * Stops following the player and drops every reference to the views.
     *
     * The position poll is an endless loop of delays on the main handler, each one holding the
     * lyrics view - and through it the activity - until it runs. This existed already and nothing
     * ever called it, so leaving the screen left the whole thing pinned in memory.
     */
    fun release() {
        lyricsLayoutGeneration++
        entranceAnimator?.removeAllListeners()
        entranceAnimator?.cancel()
        entranceAnimator = null
        scope.cancel()
        applyPending = null
        pendingLyrics = null
    }

    private fun getCurrentLyricsLineIndex(position: Long): Int {
        val currentLyrics = lyrics.value
        val line = currentLyrics.lyrics.indexOfLast { it.timestamp <= position }
        return if (line != -1) line else 0
    }

    companion object {
        private const val INITIAL_CONTEXT_LINES = 2
        private const val INITIAL_SETTLE_DURATION_MS = 320L
        private val INITIAL_SETTLE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** Fast enough that a line change is not visibly late, cheap enough to leave running. */
        private const val POSITION_POLL_MS = 200L

        /** A frame-rate clock is only used for the one Enhanced LRC line currently being sung. */
        private const val WORD_POSITION_POLL_MS = 16L
    }
}
