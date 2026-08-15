package uk.akane.accord.ui.components.lyrics

@JvmInline
value class Lyrics(val lyrics: List<LyricsLine>) {
    companion object {
        val Empty = Lyrics(emptyList())
    }
}

data class LyricsLine(
    val timestamp: Long,
    val agent: String?,
    val text: String,
    val background: String?,
    /**
     * Progressive ranges from Enhanced LRC. Each range owns the text after the preceding range
     * and through [LyricsWordTiming.endOffset], and fills between its two timestamps.
     */
    val wordTimings: List<LyricsWordTiming> = emptyList(),
) {
    val isMain: Boolean
        get() = agent == null ||
                agent == "v1" ||
                agent == "1" ||
                agent == "M"

    /** Character offset to which the sung colour should be painted at [positionMs]. */
    fun highlightOffsetAt(positionMs: Long): Float {
        if (wordTimings.isEmpty()) return if (positionMs >= timestamp) text.length.toFloat() else 0f

        var startOffset = 0
        wordTimings.forEach { timing ->
            val endOffset = timing.endOffset.coerceIn(startOffset, text.length)
            if (positionMs < timing.startTimestamp) return startOffset.toFloat()

            val duration = timing.endTimestamp - timing.startTimestamp
            if (positionMs <= timing.endTimestamp && duration > 0L) {
                val fraction = ((positionMs - timing.startTimestamp).toFloat() / duration)
                    .coerceIn(0f, 1f)
                return startOffset + (endOffset - startOffset) * fraction
            }
            startOffset = endOffset
        }
        return text.length.toFloat()
    }
}

data class LyricsWordTiming(
    val endOffset: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
)
