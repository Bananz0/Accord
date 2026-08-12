package org.akanework.gramophone.logic.utils.exoplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

@OptIn(UnstableApi::class)
class GramophoneRenderFactory(
	context: Context,
	private val configurationListener: (Format?) -> Unit = {},
	private val audioSinkListener: (DefaultAudioSink) -> Unit = {},
) : DefaultRenderersFactory(context) {

	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioTrackPlaybackParams: Boolean,
	): AudioSink {
		val root = super.buildAudioSink(
			context,
			enableFloatOutput,
			enableAudioTrackPlaybackParams,
		)!! as DefaultAudioSink
		audioSinkListener(root)
		return ObservableAudioSink(root, enableFloatOutput)
	}

	private inner class ObservableAudioSink(
		sink: AudioSink,
		private val floatOutput: Boolean,
	) : ForwardingAudioSink(sink) {
		override fun configure(
			inputFormat: Format,
			specifiedBufferSize: Int,
			outputChannels: IntArray?,
		) {
			// DefaultAudioSink turns high-resolution integer PCM into float when enabled, or 16-bit
			// otherwise. Negotiate the format AudioTrack will really use, not the decoder input.
			val outputFormat = if (Util.isEncodingHighResolutionPcm(inputFormat.pcmEncoding)) {
				inputFormat.buildUpon().setPcmEncoding(
					if (floatOutput) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
				).build()
			} else inputFormat
			// Preferred USB mixer attributes must exist before DefaultAudioSink creates AudioTrack.
			configurationListener(outputFormat)
			super.configure(inputFormat, specifiedBufferSize, outputChannels)
		}

		override fun reset() {
			configurationListener(null)
			super.reset()
		}

		override fun release() {
			configurationListener(null)
			super.release()
		}
	}
	override fun buildTextRenderers(
		context: Context,
		output: TextOutput,
		outputLooper: Looper,
		extensionRendererMode: Int,
		out: ArrayList<Renderer>
	) {
		// empty
	}

	override fun buildVideoRenderers(
		context: Context,
		extensionRendererMode: Int,
		mediaCodecSelector: MediaCodecSelector,
		enableDecoderFallback: Boolean,
		eventHandler: Handler,
		eventListener: VideoRendererEventListener,
		allowedVideoJoiningTimeMs: Long,
		out: java.util.ArrayList<Renderer>
	) {
		// empty
	}

	override fun buildImageRenderers(out: java.util.ArrayList<Renderer>) {
		// empty
	}

	override fun buildCameraMotionRenderers(
		context: Context,
		extensionRendererMode: Int,
		out: java.util.ArrayList<Renderer>
	) {
		// empty
	}
}
