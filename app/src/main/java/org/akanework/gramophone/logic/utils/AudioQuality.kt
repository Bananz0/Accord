package org.akanework.gramophone.logic.utils

import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import uk.akane.accord.R

/**
 * The quality badge shown while a track plays - Lossless, Hi-Res Lossless, Dolby Atmos.
 *
 * Read from the format the player actually selected rather than from library metadata, so it
 * describes what is coming out of the decoder: a server that transcodes to AAC does not get to
 * claim Lossless because the file on disk is FLAC.
 *
 * Upstream Accord answers this from AudioFormatDetector, which needs media3 APIs that only exist in
 * the fork it builds against (Format.getBitDepth, the 20-bit PCM encodings). This works off the
 * released API instead: the sample MIME type says whether the codec is lossy, and the PCM encoding
 * gives the bit depth once decoded.
 */
enum class AudioQuality(@param:StringRes val label: Int) {
    LOSSLESS(R.string.music_quality_lossless),
    HIRES_LOSSLESS(R.string.music_quality_hires_lossless),
    DOLBY_ATMOS(R.string.music_quality_dolby_atmos);

    companion object {

        /** Above CD sample rate, or deeper than CD bit depth. */
        private const val CD_SAMPLE_RATE = 48_000
        private const val CD_BIT_DEPTH = 16

        private val LOSSLESS_MIME_TYPES = setOf(
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_ALAC,
            MimeTypes.AUDIO_RAW,
            MimeTypes.AUDIO_WAV,
            MimeTypes.AUDIO_TRUEHD,
            "audio/x-ape",
            "audio/x-wavpack",
            "audio/aiff",
            "audio/x-aiff",
        )

        /**
         * @return the badge for the currently selected audio track, or null when the track is lossy
         *   or nothing is playing.
         */
        @OptIn(UnstableApi::class)
        fun of(tracks: Tracks): AudioQuality? = selectedAudioFormat(tracks)?.let { of(it) }

        @OptIn(UnstableApi::class)
        fun of(format: Format): AudioQuality? {
            val mimeType = format.sampleMimeType?.lowercase() ?: return null

            // Atmos rides on E-AC-3 with joint object coding, and outranks the others.
            if (mimeType == MimeTypes.AUDIO_E_AC3_JOC || mimeType == MimeTypes.AUDIO_AC4) {
                return DOLBY_ATMOS
            }
            if (mimeType !in LOSSLESS_MIME_TYPES) return null

            val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE }
            val bitDepth = bitDepthOf(format)
            val isHiRes = (sampleRate != null && sampleRate > CD_SAMPLE_RATE) ||
                    (bitDepth != null && bitDepth > CD_BIT_DEPTH)
            return if (isHiRes) HIRES_LOSSLESS else LOSSLESS
        }

        @OptIn(UnstableApi::class)
        private fun selectedAudioFormat(tracks: Tracks): Format? =
            tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group ->
                    (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                        ?.let { group.getTrackFormat(it) }
                }

        /**
         * Bit depth is only known once the stream is PCM. For a still-encoded format media3 leaves
         * pcmEncoding unset, and null here just means "no evidence of hi-res", which the sample rate
         * can still supply.
         */
        @OptIn(UnstableApi::class)
        private fun bitDepthOf(format: Format): Int? = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
    }
}
