package uk.akane.accord.ui.components.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsLineTest {

    private val line = LyricsLine(
        timestamp = 1_000L,
        agent = null,
        text = "Hello world",
        background = null,
        wordTimings = listOf(
            LyricsWordTiming(endOffset = 6, startTimestamp = 1_000L, endTimestamp = 2_000L),
            LyricsWordTiming(endOffset = 11, startTimestamp = 2_000L, endTimestamp = 3_000L),
        ),
    )

    @Test
    fun highlightMovesContinuouslyThroughEachTimedRange() {
        assertEquals(0f, line.highlightOffsetAt(1_000L), 0.001f)
        assertEquals(3f, line.highlightOffsetAt(1_500L), 0.001f)
        assertEquals(8.5f, line.highlightOffsetAt(2_500L), 0.001f)
        assertEquals(11f, line.highlightOffsetAt(3_000L), 0.001f)
    }

    @Test
    fun highlightClampsBeforeAndAfterTheLine() {
        assertEquals(0f, line.highlightOffsetAt(0L), 0.001f)
        assertEquals(11f, line.highlightOffsetAt(5_000L), 0.001f)
    }
}
