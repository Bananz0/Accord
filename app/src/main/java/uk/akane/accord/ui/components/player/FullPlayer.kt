package uk.akane.accord.ui.components.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.RectF
import android.graphics.Rect
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.marginBottom
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import android.widget.TextView
import androidx.media3.common.Tracks
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader.Companion.EXTRA_SOURCE_CONTAINER
import org.akanework.gramophone.logic.data.jellyfin.StreamQuality
import org.akanework.gramophone.logic.utils.AudioQuality
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter
import uk.akane.accord.ui.components.lyrics.Lyrics
import uk.akane.accord.ui.components.lyrics.LyricsLine
import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.media3.session.SessionCommand
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import android.media.AudioDeviceCallback
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import org.akanework.gramophone.logic.utils.AudioOutput
import kotlin.math.abs
import uk.akane.accord.R
import uk.akane.accord.logic.ArtistCredits
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.inverseLerp
import uk.akane.accord.logic.playOrPause
import uk.akane.accord.logic.setTextAnimation
import uk.akane.accord.logic.utils.CalculationUtils.convertDurationToTimeStamp
import uk.akane.accord.logic.utils.CalculationUtils.lerp
import uk.akane.accord.ui.adapters.QueueItemTouchHelperCallback
import uk.akane.accord.ui.adapters.QueuePreviewAdapter
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.QueueItem
import uk.akane.accord.ui.adapters.browse.PlaylistAdapter
import uk.akane.accord.ui.components.FadingVerticalEdgeLayout
import uk.akane.accord.ui.components.ResistiveSwipeHaptics
import uk.akane.accord.ui.components.performPressHaptic
import uk.akane.accord.ui.components.resistedSwipeDistance
import uk.akane.accord.ui.components.lyrics.LyricsViewModel
import uk.akane.accord.ui.fragments.browse.ArtistDetailFragment
import uk.akane.cupertino.widget.text.OverlayTextView
import uk.akane.cupertino.widget.button.AnimatedVectorButton
import uk.akane.cupertino.widget.button.OverlayBackgroundButton
import uk.akane.cupertino.widget.button.OverlayButton
import uk.akane.cupertino.widget.button.OverlayPillButton
import uk.akane.cupertino.widget.button.StarTransformButton
import uk.akane.cupertino.widget.button.StateAnimatedVectorButton
import uk.akane.cupertino.widget.divider.OverlayDivider
import uk.akane.cupertino.widget.image.OverlayHintView
import uk.akane.cupertino.widget.image.SimpleImageView
import uk.akane.cupertino.widget.slider.OverlaySlider
import uk.akane.cupertino.widget.special.BlendView
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.cupertino.utils.AnimationUtils.LONG_DURATION
import uk.akane.cupertino.utils.AnimationUtils.MID_DURATION
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.view.animation.OvershootInterpolator
import androidx.preference.PreferenceManager
import org.akanework.gramophone.logic.data.library.songListSnapshot
import org.akanework.gramophone.logic.data.AutoplayQueue

class FullPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes),
    FloatingPanelLayout.OnSlideListener, FloatingPanelLayout.CoverSwipeHandler, Player.Listener {

    private val activity
        get() = context as MainActivity
    private val instance: MediaController?
        get() = activity.getPlayer()

    private var initialMargin = IntArray(4)

    private var blendView: BlendView
    private var overlayDivider: OverlayDivider
    private var fadingEdgeLayout: FadingVerticalEdgeLayout
    private var lyricsBtn: Button
    private var volumeOverlaySlider: OverlaySlider
    private var progressOverlaySlider: OverlaySlider
    private var speakerHintView: OverlayHintView
    private var speakerFullHintView: OverlayHintView
    private var currentTimestampTextView: OverlayTextView
    private var leftTimestampTextView: OverlayTextView
    private var coverSimpleImageView: SimpleImageView
    private var titleTextView: OverlayTextView
    private var subtitleTextView: OverlayTextView
    private var listOverlayButton: OverlayButton
    private var airplayOverlayButton: OverlayButton
    private var captionOverlayButton: OverlayButton
    private var starTransformButton: StarTransformButton
    private var controllerButton: StateAnimatedVectorButton
    private var previousButton: AnimatedVectorButton
    private var nextButton: AnimatedVectorButton
    private var ellipsisButton: OverlayBackgroundButton
    private var qualityBadge: TextView
    private var qualityAvailableHint: TextView
    private var currentQualityDetails: AudioQuality.Details? = null
    private var outputDeviceIcon: ImageView
    private var outputDeviceName: TextView
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var fullPlayerToolbar: FullPlayerToolbar
    private var queueContainer: View
    private var queueShuffleButton: OverlayPillButton
    private var queueRepeatButton: OverlayPillButton
    private var queueAutoplayButton: OverlayPillButton
    private var queueTextView: OverlayTextView
    private var queueRecyclerView: RecyclerView
    private var queueItemTouchHelper: ItemTouchHelper? = null

    private var lyricsViewModel: LyricsViewModel? = null

    /** onViewCreated builds the line views, so it must run once and not on every toggle. */
    private var lyricsViewAttached = false
    private val floatingPanelLayout: FloatingPanelLayout
        get() = parent as FloatingPanelLayout

    private var firstTime = false
    private var positionUpdateRunning = false
    private var isUserScrubbing = false
    private var isUserVolumeScrubbing = false
    private var coverBaseScale = 1F
    private var coverBaseTranslationX = 0F
    /**
     * How far the artwork is displaced from where it belongs, by a drag or a track change.
     *
     * Kept as an offset rather than by writing translationX directly, so it composes with the
     * paused-state shrink and the panel's slide transform instead of racing them. Suppressing
     * those during a slide and snapping at the end is what made the movement finish with a jolt.
     */
    private var coverSlideOffsetX = 0F
    private var coverSlideAnimator: ValueAnimator? = null
    private val coverSwipeHaptics = ResistiveSwipeHaptics()
    private var pendingCoverReleaseVelocity = 0F

    /** Brings the cover back even if the artwork has not arrived; see [startCoverSlideOut]. */
    private val coverSlideInDeadline = Runnable {
        if (coverSlideInFlight && !coverSlideArtReady) {
            coverSlideArtReady = true
            slideCoverInIfReady()
        }
    }

    /** Puts the cover back if a requested skip turned out not to happen. */
    private val coverSlideBackstop = Runnable {
        if (!coverSlideInFlight && pendingCoverSlide != SLIDE_NONE) {
            pendingCoverSlide = SLIDE_NONE
            pendingCoverReleaseVelocity = 0F
            animateCoverOffset(0F, COVER_SWIPE_SETTLE_MS, settleInterpolator)
        }
    }
    /** Where the artwork sits when nothing is moving it; see [updateCoverTransform]. */
    private var coverRestingTranslationX = 0F
    /** Which way the artwork should travel on the next track change, or [SLIDE_NONE]. */
    private var pendingCoverSlide = SLIDE_NONE
    /**
     * True from the moment the outgoing artwork starts moving until the incoming one has landed.
     *
     * While it is set, nothing else may write the cover's translationX. The paused-state shrink and
     * the panel's slide transform both do so whenever playback state changes - which is exactly what
     * a track change causes - and their writes landing mid-animation is what made the movement
     * stutter.
     */
    private var coverSlideInFlight = false
    private var coverSlideOutDone = false
    private var coverSlideArtReady = false
    /**
     * The incoming artwork, held back until the outgoing cover has left.
     *
     * Cached artwork arrives within a frame or two, so applying it as soon as it loads meant the
     * cover that slid away was already showing the *new* track - the old one never left, and the
     * same picture slid out and back in.
     */
    private var pendingCoverDrawable: android.graphics.drawable.Drawable? = null
    private var slideFraction = 0F
    private var coverBaseTranslationY = 0F
    private var coverPauseScale = 1F
    private var coverPauseAnimator: ValueAnimator? = null
    private var maxDeviceVolume = 0
    private var volumeUpdateAnimator: ValueAnimator? = null
    private val audioManager by lazy {
        ContextCompat.getSystemService(context, AudioManager::class.java)
    }
    private var isVolumeReceiverRegistered = false
    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.media.VOLUME_CHANGED_ACTION",
                "android.media.MASTER_VOLUME_CHANGED_ACTION",
                "android.media.MASTER_MUTE_CHANGED_ACTION",
                "android.media.STREAM_MUTE_CHANGED_ACTION" -> updateVolumeSlider()
            }
        }
    }
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgressDisplay()
            if (positionUpdateRunning) {
                postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    init {
        inflate(context, R.layout.layout_full_player, this)

        blendView = findViewById(R.id.blend_view)
        overlayDivider = findViewById(R.id.divider)
        fadingEdgeLayout = findViewById(R.id.fading)
        lyricsBtn = findViewById(R.id.lyrics)
        volumeOverlaySlider = findViewById(R.id.volume_slider)
        progressOverlaySlider = findViewById(R.id.progressBar)
        speakerHintView = findViewById(R.id.speaker_hint)
        speakerFullHintView = findViewById(R.id.speaker_full_hint)
        currentTimestampTextView = findViewById(R.id.current_timestamp)
        leftTimestampTextView = findViewById(R.id.left_timeStamp)
        coverSimpleImageView = findViewById(R.id.cover)
        titleTextView = findViewById(R.id.title)
        subtitleTextView = findViewById(R.id.subtitle)
        subtitleTextView.setOnClickListener { view ->
            val item = instance?.currentMediaItem ?: return@setOnClickListener
            val artist = ArtistCredits.primaryArtist(item)
            if (artist.isBlank() || artist == "(Unknown Artist)") return@setOnClickListener
            view.performPressHaptic()
            activity.collapseNowPlaying()
            activity.fragmentSwitcherView.addFragmentToCurrentStack(
                ArtistDetailFragment.newInstance(artist)
            )
        }
        listOverlayButton = findViewById(R.id.list)
        airplayOverlayButton = findViewById(R.id.airplay)
        captionOverlayButton = findViewById(R.id.caption)
        starTransformButton = findViewById(R.id.star)
        ellipsisButton = findViewById(R.id.ellipsis)
        qualityBadge = findViewById(R.id.quality_badge)
        qualityAvailableHint = findViewById(R.id.quality_available_hint)
        qualityBadge.setOnClickListener {
            it.performPressHaptic()
            showQualityDetails()
        }
        outputDeviceIcon = findViewById(R.id.output_device_icon)
        outputDeviceName = findViewById(R.id.output_device_name)
        outputDeviceIcon.setOnClickListener {
            it.performPressHaptic()
            startSystemMediaControl()
        }
        controllerButton = findViewById(R.id.main_control_btn)
        previousButton = findViewById(R.id.backward_btn)
        nextButton = findViewById(R.id.forward_btn)
        fullPlayerToolbar = findViewById(R.id.full_player_tool_bar)
        queueContainer = findViewById(R.id.queue_container)
        queueShuffleButton = findViewById(R.id.btnShuffle)
        queueRepeatButton = findViewById(R.id.btnRepeat)
        queueAutoplayButton = findViewById(R.id.btnAutoplay)
        queueTextView = findViewById(R.id.queue)
        queueRecyclerView = findViewById(R.id.queue_list)
        queueRecyclerView.layoutManager = LinearLayoutManager(context)
        val queueAdapter = QueuePreviewAdapter(
            mutableListOf(),
            blendView,
            { from, to ->
                instance?.moveMediaItem(from, to)
            },
            { index ->
                instance?.seekTo(index, C.TIME_UNSET)
                instance?.play()
            },
            object : QueuePreviewAdapter.DragStartListener {
                override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
                    queueItemTouchHelper?.startDrag(viewHolder)
                }
            }
        )
        queueRecyclerView.adapter = queueAdapter
        queueItemTouchHelper = ItemTouchHelper(
            QueueItemTouchHelperCallback(queueAdapter) { index ->
                instance?.removeMediaItem(index)
            }
        ).apply {
            attachToRecyclerView(queueRecyclerView)
        }
        queueContainer.doOnLayout {
            queueEnterOffset = resolveQueueEnterOffset()
        }

        ellipsisButton.setOnCheckedChangeListener { v, _ ->
            v.performPressHaptic()
            callUpPlayerPopupMenu(v)
        }

        ellipsisButton.setOnLongClickListener {
            // TODO tell floating panel to intercept gesture
            it.performPressHaptic()
            callUpPlayerPopupMenu(it)
            true
        }

        fullPlayerToolbar.setOnEllipsisCheckedChangeListener(
            OverlayBackgroundButton.OnCheckedChangeListener { button, _ ->
                button.performPressHaptic()
                callUpPlayerPopupMenu(button)
            }
        )
        starTransformButton.setOnClickListener {
            toggleFavoriteForCurrentSong()
        }
        fullPlayerToolbar.setOnStarClickListener {
            toggleFavoriteForCurrentSong()
        }

        clipToOutline = true

        fadingEdgeLayout.visibility = GONE
        queueContainer.visibility = INVISIBLE
        lyricsViewModel = LyricsViewModel(
            context,
            positionProvider = { instance?.currentPosition ?: 0L },
            // Tapping a lyric jumps to it, which is the whole reason the timestamps are there.
            onSeek = { timestamp -> instance?.seekTo(timestamp) },
        )

        // The hidden button belongs to the pre-rewrite lyrics prototype. The three bottom buttons
        // are the actual mode controls in the working APK.
        lyricsBtn.visibility = GONE
        captionOverlayButton.setOnClickListener {
            it.performPressHaptic()
            toggleContentMode(ContentType.LYRICS)
        }

        volumeOverlaySlider.addEmphasizeListener(object : OverlaySlider.EmphasizeListener {
            override fun onEmphasizeProgressLeft(translationX: Float) {
                speakerHintView.translationX = -translationX
            }

            override fun onEmphasizeProgressRight(translationX: Float) {
                speakerFullHintView.translationX = translationX
            }

            override fun onEmphasizeAll(fraction: Float) {
                speakerHintView.transformValue = fraction
                speakerFullHintView.transformValue = fraction
            }

            override fun onEmphasizeStartLeft() {
                speakerHintView.playAnim()
            }

            override fun onEmphasizeStartRight() {
                speakerFullHintView.playAnim()
            }
        })
        volumeOverlaySlider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
            override fun onStartTracking(slider: OverlaySlider) {
                isUserVolumeScrubbing = true
                volumeUpdateAnimator?.cancel()
            }

            override fun onValueChanged(slider: OverlaySlider, value: Float, fromUser: Boolean, fromMomentum: Boolean) {
                if (!fromUser) return
                setDeviceVolume(value.toInt())
            }

            override fun onStopTracking(slider: OverlaySlider) {
                setDeviceVolume(slider.value.toInt())
                isUserVolumeScrubbing = false
            }
        })

        progressOverlaySlider.addEmphasizeListener(object : OverlaySlider.EmphasizeListener {
            override fun onEmphasizeVertical(translationX: Float, translationY: Float) {
                currentTimestampTextView.translationY = translationY
                currentTimestampTextView.translationX = -translationX
                leftTimestampTextView.translationY = translationY
                leftTimestampTextView.translationX = translationX
            }
        })

        progressOverlaySlider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
            override fun onStartTracking(slider: OverlaySlider) {
                isUserScrubbing = true
                stopPositionUpdates()
            }

            override fun onValueChanged(slider: OverlaySlider, value: Float, fromUser: Boolean, fromMomentum: Boolean) {
                if (!fromUser) return
                val duration = resolveDurationMs() ?: return
                updateProgressTexts(value.toLong(), duration)
            }

            override fun onStopTracking(slider: OverlaySlider) {
                val duration = resolveDurationMs()
                if (duration != null) {
                    val position = slider.value.toLong().coerceIn(0L, duration)
                    instance?.seekTo(position)
                    updateProgressTexts(position, duration)
                }
                isUserScrubbing = false
                if (instance?.isPlaying == true) {
                    startPositionUpdates()
                } else {
                    updateProgressDisplay()
                }
            }
        })

        coverSimpleImageView.doOnLayout {
            Log.d(TAG, "csi: ${coverSimpleImageView.left}, ${coverSimpleImageView.top}")
            floatingPanelLayout.setupTransitionImageView(
                coverSimpleImageView.width,
                coverSimpleImageView.height,
                coverSimpleImageView.left,
                coverSimpleImageView.top,
                AppCompatResources.getDrawable(context, R.drawable.default_cover)!!.toBitmap()
            )

            updateTransitionTargetForContentType(contentType)
        }

        listOverlayButton.setOnClickListener {
            it.performPressHaptic()
            toggleContentMode(ContentType.PLAYLIST)
        }

        queueShuffleButton.setOnClickListener {
            val controller = instance ?: return@setOnClickListener
            it.performPressHaptic()
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
            queueShuffleButton.isChecked = controller.shuffleModeEnabled
        }

        queueAutoplayButton.isChecked = isAutoplayEnabled()
        queueAutoplayButton.setOnClickListener {
            it.performPressHaptic()
            val enabled = !isAutoplayEnabled()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(PREF_AUTOPLAY, enabled).apply()
            queueAutoplayButton.isChecked = enabled
            Toast.makeText(
                context,
                if (enabled) R.string.autoplay_on else R.string.autoplay_off,
                Toast.LENGTH_SHORT
            ).show()
            // Turning it on with a queue already near its end should not require waiting for
            // another track to finish first.
            if (enabled) topUpQueueIfNeeded()
        }

        queueRepeatButton.setOnClickListener {
            val controller = instance ?: return@setOnClickListener
            it.performPressHaptic()
            val nextRepeatMode = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = nextRepeatMode
            updateRepeatButton(nextRepeatMode)
        }

        airplayOverlayButton.setOnClickListener {
            it.performPressHaptic()
            animateBottomButtonPress(airplayOverlayButton)
            startSystemMediaControl()
        }


        // The service resolves lyrics off the main thread and announces the result with this
        // command once it has them, which for a Jellyfin lookup is well after the track started.
        activity.controllerViewModel.customCommandListeners.addCallback(activity.lifecycle) {
                _, command, _ ->
            if (command.customAction == GramophonePlaybackService.SERVICE_GET_LYRICS) {
                refreshLyrics()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            } else {
                // The dispatcher walks listeners until one claims the command, so anything not
                // handled here has to decline rather than swallow it.
                Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
        }

        activity.controllerViewModel.addControllerCallback(activity.lifecycle) { _, _ ->
            firstTime = true
            instance?.addListener(this@FullPlayer)
            onRepeatModeChanged(instance?.repeatMode ?: Player.REPEAT_MODE_OFF)
            onShuffleModeEnabledChanged(instance?.shuffleModeEnabled == true)
            onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
            instance?.currentTimeline?.let {
                onTimelineChanged(it, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
            onMediaItemTransition(
                instance?.currentMediaItem,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
            onMediaMetadataChanged(instance?.mediaMetadata ?: MediaMetadata.EMPTY)
            instance?.currentTracks?.let { onTracksChanged(it) }
            refreshLyrics()
            updateVolumeSlider()
            firstTime = false
        }

        controllerButton.setOnClickListener {
            it.performPressHaptic()
            instance?.playOrPause()
        }

        previousButton.setOnClickListener {
            it.performPressHaptic()
            pendingCoverSlide = SLIDE_PREVIOUS
            instance?.seekToPrevious()
        }
        nextButton.setOnClickListener {
            it.performPressHaptic()
            pendingCoverSlide = SLIDE_NEXT
            instance?.seekToNext()
        }

        doOnLayout {
            floatingPanelLayout.addOnSlideListener(this)
            floatingPanelLayout.coverSwipeHandler = this

            finalTranslationX = 32.dp.px - coverSimpleImageView.left
            finalTranslationY = (20 - 18).dp.px
            finalScale = 74.dp.px / coverSimpleImageView.height
        }

        updateVolumeSlider()
    }

    private fun startSystemMediaControl() {
        if (Build.VERSION.SDK_INT >= 34) {
            val mediaRouter2 = MediaRouter2.getInstance(context)
            val tag = mediaRouter2.showSystemOutputSwitcher()
            if (!tag) {
                Toast.makeText(context, R.string.media_control_text_error, Toast.LENGTH_SHORT)
                    .show()
            }
        } else {
            val intent = Intent().apply {
                action = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
                setPackage("com.android.systemui")
                putExtra("package_name", context.packageName)
            }
            val tag = startNativeMediaDialog(intent)
            if (!tag) {
                Toast.makeText(context, R.string.media_control_text_error, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun startNativeMediaDialog(intent: Intent): Boolean {
        val resolveInfoList: List<ResolveInfo> =
            context.packageManager.queryIntentActivities(intent, 0)
        for (resolveInfo in resolveInfoList) {
            val activityInfo = resolveInfo.activityInfo
            val applicationInfo: ApplicationInfo? = activityInfo?.applicationInfo
            if (applicationInfo != null && (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    private var finalTranslationX = 0F
    private var finalTranslationY = 0F
    private var initialCoverRadius =
        resources.getDimensionPixelSize(R.dimen.full_cover_radius).toFloat()
    private var endCoverRadius = 22.dp.px
    private var initialElevation = 24.dp.px
    private var finalScale = 0F
    private val queueCoverRadius = 5.dp.px
    private var queueEnterOffset = 0F
    private val queueStartFraction = 1F / 1.2F
    private val lyricsStartFraction = 0.08F

    private fun updateTransitionTargetForContentType(value: ContentType) {
        val compactMode = value == ContentType.PLAYLIST || value == ContentType.LYRICS
        val targetView = if (compactMode) {
            fullPlayerToolbar.getCoverView()
        } else {
            coverSimpleImageView
        }
        val lockCornerRadius = compactMode
        val targetRadius = if (lockCornerRadius) queueCoverRadius else 0F
        val targetElevation = if (compactMode) null else initialElevation

        val update = {
            floatingPanelLayout.updateTransitionTarget(
                targetView,
                targetRadius,
                lockCornerRadius,
                targetElevation
            )
        }
        if (targetView.isLaidOut) {
            update()
        } else {
            targetView.doOnLayout { update() }
        }
    }

    private fun updateRepeatButton(repeatMode: Int) {
        val iconRes = if (repeatMode == Player.REPEAT_MODE_ONE) {
            R.drawable.ic_nowplaying_repeat_one
        } else {
            R.drawable.ic_nowplaying_repeat
        }
        queueRepeatButton.setIconResource(iconRes)
        queueRepeatButton.isChecked = repeatMode != Player.REPEAT_MODE_OFF
    }

    private fun toggleFavoriteForCurrentSong() {
        val mediaItem = instance?.currentMediaItem ?: return
        val key = buildSongKey(mediaItem)
        val keys = PlaylistAdapter.loadFavoriteKeys(context)
        val isFavorite = keys.contains(key)
        if (isFavorite) {
            keys.removeAll { it == key }
        } else {
            keys.add(0, key)
        }
        PlaylistAdapter.saveFavoriteKeys(context, keys)
        updateFavoriteButtons(!isFavorite)
        // Upstream keeps favourites in local preferences and nothing else. Favourites belong to the
        // server here, so the star has to reach it - otherwise starring a track on this phone is
        // invisible to the web client and gets undone by the next sync.
        CoroutineScope(Dispatchers.IO).launch {
            JellyfinReporter(context).setFavourite(mediaItem.mediaId, !isFavorite)
        }
        // Deliberately not refreshing the library here. Upstream called updateLibrary on every tap,
        // which on this fork means a full Jellyfin sync - the better part of a minute of requests
        // for one star.
    }

    private fun syncFavoriteButtonsForCurrentItem() {
        val mediaItem = instance?.currentMediaItem ?: return updateFavoriteButtons(false)
        val key = buildSongKey(mediaItem)
        val keys = PlaylistAdapter.loadFavoriteKeys(context)
        updateFavoriteButtons(keys.contains(key))
    }

    private fun updateFavoriteButtons(checked: Boolean) {
        if (starTransformButton.isChecked != checked) {
            starTransformButton.toggle()
        }
        fullPlayerToolbar.setStarChecked(checked)
    }

    private fun buildSongKey(item: MediaItem): String {
        val mediaId = item.mediaId
        if (mediaId.isNotBlank()) return mediaId
        return item.localConfiguration?.uri?.toString() ?: item.hashCode().toString()
    }

    private fun resolveDurationMs(): Long? {
        val duration = instance?.contentDuration
        if (duration != null && duration != C.TIME_UNSET) {
            return duration
        }
        return instance?.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0L }
    }

    private fun resolveMaxDeviceVolume(): Int {
        if (maxDeviceVolume <= 0) {
            maxDeviceVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
        return maxDeviceVolume
    }

    private fun resolveDeviceVolume(): Int {
        val controller = instance
        return if (controller != null && controller.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)) {
            controller.deviceVolume
        } else {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
    }

    private fun updateVolumeSlider(volume: Int? = null) {
        if (isUserVolumeScrubbing) return
        val maxVolume = resolveMaxDeviceVolume()
        if (maxVolume <= 0) return

        volumeOverlaySlider.valueFrom = 0f
        volumeOverlaySlider.valueTo = maxVolume.toFloat()
        val currentVolume = (volume ?: resolveDeviceVolume()).coerceIn(0, maxVolume).toFloat()
        animateVolumeSliderTo(currentVolume)
    }

    private fun setDeviceVolume(volume: Int) {
        val maxVolume = resolveMaxDeviceVolume()
        if (maxVolume <= 0) return
        val boundedVolume = volume.coerceIn(0, maxVolume)
        val controller = instance
        if (controller != null && controller.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS )) {
            controller.setDeviceVolume(boundedVolume, 0)
        } else {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, boundedVolume, 0)
        }
    }

    private fun animateVolumeSliderTo(targetValue: Float) {
        val startValue = volumeOverlaySlider.value
        if (startValue == targetValue) return
        volumeUpdateAnimator?.cancel()
        volumeUpdateAnimator = ValueAnimator.ofFloat(startValue, targetValue).apply {
            duration = LONG_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener {
                volumeOverlaySlider.value = it.animatedValue as Float
                volumeOverlaySlider.invalidate()
            }
            start()
        }
    }

    private fun updateProgressTexts(positionMs: Long, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val boundedPosition = positionMs.coerceIn(0L, safeDuration)
        val remaining = (safeDuration - boundedPosition).coerceAtLeast(0L)

        currentTimestampTextView.text = convertDurationToTimeStamp(boundedPosition)
        leftTimestampTextView.text =
            context.getString(R.string.time_remaining, convertDurationToTimeStamp(remaining))
    }

    private fun updateProgressDisplay() {
        val mediaDuration = resolveDurationMs()
        val currentPosition = instance?.currentPosition ?: 0L
        if (mediaDuration == null || instance?.mediaItemCount == 0) {
            val placeholder = context.getString(R.string.default_duration)
            currentTimestampTextView.text = placeholder
            leftTimestampTextView.text = placeholder
            progressOverlaySlider.valueTo = 1f
            progressOverlaySlider.value = 0f
            progressOverlaySlider.invalidate()
            return
        }

        if (isUserScrubbing) return

        val safeDuration = mediaDuration.coerceAtLeast(1L)
        val boundedPosition = currentPosition.coerceIn(0L, safeDuration)

        updateProgressTexts(boundedPosition, safeDuration)
        progressOverlaySlider.valueTo = safeDuration.toFloat()
        progressOverlaySlider.value = boundedPosition.toFloat()
        progressOverlaySlider.invalidate()
    }

    private fun startPositionUpdates() {
        if (positionUpdateRunning) return
        positionUpdateRunning = true
        removeCallbacks(positionUpdateRunnable)
        post(positionUpdateRunnable)
    }

    private fun stopPositionUpdates() {
        positionUpdateRunning = false
        removeCallbacks(positionUpdateRunnable)
    }

    private fun animateCoverChange(fraction: Float) {
        coverBaseTranslationX = lerp(0f, finalTranslationX, fraction)
        coverBaseTranslationY = lerp(0f, finalTranslationY, fraction)
        coverRestingTranslationX = coverBaseTranslationX
        applyCoverTranslation()
        coverSimpleImageView.translationY = coverBaseTranslationY
        coverSimpleImageView.pivotX = 0F
        coverSimpleImageView.pivotY = 0F
        coverBaseScale = lerp(1f, finalScale, fraction)
        applyCoverScale()
        coverSimpleImageView.elevation = lerp(initialElevation, 5f.dp.px, fraction)
        coverSimpleImageView.updateCornerRadius(
            lerp(
                initialCoverRadius,
                endCoverRadius,
                fraction
            ).toInt()
        )

        coverSimpleImageView.visibility = if (fraction == 1F) INVISIBLE else VISIBLE

        val coverTranslationY =
            coverBaseTranslationY - coverSimpleImageView.height * (1f - coverBaseScale)
        titleTextView.translationY = coverTranslationY
        starTransformButton.translationY = coverTranslationY
        ellipsisButton.translationY = coverTranslationY
        subtitleTextView.translationY = coverTranslationY

        val quickFraction = (fraction * 1.2f).coerceIn(0F, 1F)
        titleTextView.alpha = lerp(1F, 0F, quickFraction)
        subtitleTextView.alpha = lerp(1F, 0F, quickFraction)
        starTransformButton.alpha = lerp(1F, 0F, quickFraction)
        ellipsisButton.alpha = lerp(1F, 0F, quickFraction)

        fullPlayerToolbar.animateFade(fraction)
        animateQueuePanel(fraction)
    }

    private fun resolveQueueEnterOffset(): Float {
        return 0F
    }

    private fun animateQueuePanel(fraction: Float) {
        if (contentType != ContentType.PLAYLIST) {
            queueContainer.visibility = INVISIBLE
            setQueueChildrenAlpha(0F)
            return
        }
        val queueFraction = inverseLerp(queueStartFraction, 1F, fraction, clamp = true)
        if (queueFraction <= 0F) {
            queueEnterOffset = resolveQueueEnterOffset()
            queueContainer.translationY = queueEnterOffset
            queueContainer.visibility = INVISIBLE
            setQueueChildrenAlpha(0F)
            return
        }

        queueContainer.visibility = VISIBLE
        queueContainer.translationY = lerp(queueEnterOffset, 0F, queueFraction)
        setQueueChildrenAlpha(queueFraction)
    }

    private fun setQueueChildrenAlpha(alpha: Float) {
        queueShuffleButton.alpha = alpha
        queueRepeatButton.alpha = alpha
        queueAutoplayButton.alpha = alpha
        queueTextView.alpha = alpha
        queueRecyclerView.alpha = alpha
    }

    private fun callUpPlayerPopupMenu(v: View) {
        val anchorView = if (contentType != ContentType.NORMAL) {
            fullPlayerToolbar.getEllipsisView()
        } else {
            v
        }
        val showBelow = contentType != ContentType.NORMAL
        PlayerPopupMenu.show(
            host = floatingPanelLayout,
            anchorView = anchorView,
            showBelow = showBelow
        ) {
            ellipsisButton.isChecked = false
            fullPlayerToolbar.setEllipsisChecked(false)
        }
    }

    override fun dispatchApplyWindowInsets(platformInsets: WindowInsets): WindowInsets {
        if (initialMargin[3] != 0) return super.dispatchApplyWindowInsets(platformInsets)
        val insets = WindowInsetsCompat.toWindowInsetsCompat(platformInsets)
        val floatingInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
        )
        if (floatingInsets.bottom != 0) {
            initialMargin = intArrayOf(
                marginLeft,
                marginTop + floatingInsets.top,
                marginRight,
                marginBottom + floatingInsets.bottom
            )
            Log.d(TAG, "initTop: ${initialMargin[1]}")
            overlayDivider.updateLayoutParams<MarginLayoutParams> {
                topMargin = initialMargin[1] + overlayDivider.marginTop
            }
        }
        Log.d(
            TAG,
            "marginBottom: ${marginBottom}, InsetsBottom: ${floatingInsets.bottom}, marginTop: ${floatingInsets.top}"
        )
        return super.dispatchApplyWindowInsets(platformInsets)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (contentType == ContentType.LYRICS) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(hideControlsRunnable)
                    if (lyricsControlsHidden) {
                        revealingControlsGesture = true
                        showLyricsControls(scheduleHide = false)
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    scheduleControlsHide()
                    if (revealingControlsGesture) {
                        revealingControlsGesture = false
                        return true
                    }
                }
            }
            if (revealingControlsGesture) return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSlideStatusChanged(status: FloatingPanelLayout.SlideStatus) {
        when (status) {
            FloatingPanelLayout.SlideStatus.EXPANDED -> {
                coverSimpleImageView.alpha = 1F
            }

            else -> {
                coverSimpleImageView.alpha = 0F
            }
        }
    }

    var previousState = false
    fun freeze() {
        previousState = blendView.isRunning
        blendView.stopRotationAnimation()
    }

    fun unfreeze() {
        // TODO: Make it on demand
        if (previousState) {
            blendView.startRotationAnimation()
        } else {
            blendView.stopRotationAnimation()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        Log.d(TAG, "onMeasured")
    }

    override fun onSlide(value: Float) {
        slideFraction = value
        if (contentType == ContentType.PLAYLIST || contentType == ContentType.LYRICS) {
            fullPlayerToolbar.getCoverView().alpha = if (value >= 1F) 1F else 0F
        }
    }

    private var transformationFraction = 0F
    private var contentTypeAnimator: ValueAnimator? = null
    private var selectedBottomButton: OverlayButton? = null
    private val bottomButtonAnimators = mutableMapOf<OverlayButton, ValueAnimator>()

    private var contentType = ContentType.NORMAL
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            syncBottomButtons(value)

            when (value) {
                ContentType.LYRICS -> {
                    cancelControlsHide()
                    syncQualityBadgeVisibility()
                    showLyrics()
                    if (previous == ContentType.PLAYLIST) {
                        animateContentSwap(queueContainer, fadingEdgeLayout) {
                            scheduleControlsHide()
                        }
                    } else {
                        animatePlayerTransform(1F, animateLyrics = true) {
                            scheduleControlsHide()
                        }
                    }
                }

                ContentType.NORMAL -> {
                    cancelControlsHide()
                    animatePlayerTransform(
                        target = 0F,
                        animateLyrics = previous == ContentType.LYRICS,
                    ) {
                        hideLyrics()
                        instance?.currentTracks?.let { onTracksChanged(it) }
                    }
                }

                ContentType.PLAYLIST -> {
                    cancelControlsHide()
                    syncQualityBadgeVisibility()
                    queueContainer.visibility = VISIBLE
                    queueContainer.translationY = queueEnterOffset
                    setQueueChildrenAlpha(0F)
                    queueContainer.bringToFront()
                    if (previous == ContentType.LYRICS) {
                        animateContentSwap(fadingEdgeLayout, queueContainer)
                    } else {
                        animatePlayerTransform(1F)
                    }
                }
            }
            updateTransitionTargetForContentType(value)
        }

    private fun toggleContentMode(mode: ContentType) {
        if (contentTypeAnimator != null) return
        contentType = if (contentType == mode) ContentType.NORMAL else mode
    }

    private fun showLyrics() {
        fadingEdgeLayout.visibility = VISIBLE
        fadingEdgeLayout.alpha = 0F
        fadingEdgeLayout.bringToFront()
        bringPlayerChromeToFront()
        if (!lyricsViewAttached) {
            lyricsViewAttached = true
            lyricsViewModel?.onViewCreated(fadingEdgeLayout)
        }
        refreshLyrics()
    }

    /**
     * The lyrics surface fills the player so it can expand when the controls auto-hide. Keep the
     * interactive chrome later in the drawing/touch order or the lyrics scroll view consumes every
     * tap before the buttons can see it.
     */
    private fun bringPlayerChromeToFront() {
        listOf<View>(
            fullPlayerToolbar,
            progressOverlaySlider,
            currentTimestampTextView,
            leftTimestampTextView,
            controllerButton,
            previousButton,
            nextButton,
            volumeOverlaySlider,
            speakerHintView,
            speakerFullHintView,
            captionOverlayButton,
            airplayOverlayButton,
            outputDeviceIcon,
            outputDeviceName,
            listOverlayButton,
        ).forEach { it.bringToFront() }
    }

    private fun hideLyrics() {
        fadingEdgeLayout.visibility = INVISIBLE
        fadingEdgeLayout.alpha = 0F
        fadingEdgeLayout.translationY = 0F
        fadingEdgeLayout.scaleX = 1F
        fadingEdgeLayout.scaleY = 1F
    }

    private fun animatePlayerTransform(
        target: Float,
        animateLyrics: Boolean = false,
        onEnd: (() -> Unit)? = null,
    ) {
        contentTypeAnimator?.cancel()
        var cancelled = false
        contentTypeAnimator = ValueAnimator.ofFloat(transformationFraction, target).apply {
            duration = MID_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                transformationFraction = fraction
                animateCoverChange(fraction)
                if (animateLyrics) animateLyricsEntrance(fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    contentTypeAnimator = null
                    onEnd?.invoke()
                    updateTransitionTargetForContentType(contentType)
                }
            })
            start()
        }
    }

    private fun animateLyricsEntrance(fraction: Float) {
        val lyricsFraction = inverseLerp(lyricsStartFraction, 1F, fraction, clamp = true)
        val enterOffset = maxOf(
            progressOverlaySlider.top.toFloat() - fadingEdgeLayout.top.toFloat(),
            40.dp.px,
        )
        fadingEdgeLayout.translationY = lerp(enterOffset, 0F, lyricsFraction)
        fadingEdgeLayout.alpha = lyricsFraction
    }

    private fun animateContentSwap(from: View, to: View, onEnd: (() -> Unit)? = null) {
        contentTypeAnimator?.cancel()
        to.visibility = VISIBLE
        to.alpha = 0F
        to.scaleX = 0.92F
        to.scaleY = 0.92F
        var cancelled = false
        contentTypeAnimator = ValueAnimator.ofFloat(0F, 1F).apply {
            duration = MID_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                from.alpha = 1F - fraction
                from.scaleX = lerp(1F, 0.92F, fraction)
                from.scaleY = from.scaleX
                to.alpha = fraction
                to.scaleX = lerp(0.92F, 1F, fraction)
                to.scaleY = to.scaleX
                if (from === queueContainer) setQueueChildrenAlpha(1F - fraction)
                if (to === queueContainer) setQueueChildrenAlpha(fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    from.visibility = INVISIBLE
                    from.alpha = 0F
                    from.scaleX = 1F
                    from.scaleY = 1F
                    to.alpha = 1F
                    to.scaleX = 1F
                    to.scaleY = 1F
                    to.translationY = 0F
                    if (from === queueContainer) setQueueChildrenAlpha(0F)
                    if (to === queueContainer) setQueueChildrenAlpha(1F)
                    contentTypeAnimator = null
                    onEnd?.invoke()
                    updateTransitionTargetForContentType(contentType)
                }
            })
            start()
        }
    }

    private fun syncBottomButtons(value: ContentType) {
        setBottomButtonSelected(captionOverlayButton, value == ContentType.LYRICS)
        setBottomButtonSelected(listOverlayButton, value == ContentType.PLAYLIST)
        selectedBottomButton = when (value) {
            ContentType.LYRICS -> captionOverlayButton
            ContentType.PLAYLIST -> listOverlayButton
            ContentType.NORMAL -> null
        }
    }

    private fun setBottomButtonSelected(button: OverlayButton, selected: Boolean) {
        if (button.isChecked != selected) button.isChecked = selected
        animateBottomButtonScale(button, if (selected) 1.1F else 1F, selected)
    }

    private fun animateBottomButtonScale(
        button: OverlayButton,
        target: Float,
        selecting: Boolean,
    ) {
        if (button.scaleX == target && button.scaleY == target) return
        bottomButtonAnimators.remove(button)?.cancel()
        val animator = ValueAnimator.ofFloat(button.scaleX, target).apply {
            duration = if (selecting) 260L else 220L
            interpolator = OvershootInterpolator(if (selecting) 3.6F else 3.45F)
            addUpdateListener {
                val scale = it.animatedValue as Float
                button.scaleX = scale
                button.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bottomButtonAnimators[button] === animation) {
                        bottomButtonAnimators.remove(button)
                    }
                }
            })
            start()
        }
        bottomButtonAnimators[button] = animator
    }

    private fun animateBottomButtonPress(button: OverlayButton) {
        animateBottomButtonScale(button, 1.1F, selecting = true)
        button.removeCallbacks(resetAirplayButton)
        button.postDelayed(resetAirplayButton, 180L)
    }

    private val resetAirplayButton = Runnable {
        animateBottomButtonScale(airplayOverlayButton, 1F, selecting = false)
    }

    private var controlsAnimator: ValueAnimator? = null
    private var controlsHideFraction = 0F
    private var lyricsControlsHidden = false
    private var revealingControlsGesture = false
    private val lyricsClipRect = Rect()
    private val hideControlsRunnable = Runnable { hideLyricsControls() }

    private fun scheduleControlsHide() {
        removeCallbacks(hideControlsRunnable)
        if (contentType == ContentType.LYRICS) {
            postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private fun cancelControlsHide() {
        removeCallbacks(hideControlsRunnable)
        controlsAnimator?.cancel()
        controlsAnimator = null
        lyricsControlsHidden = false
        setControlsVisibility(true)
        applyControlsHideFraction(0F)
    }

    private fun hideLyricsControls() {
        if (lyricsControlsHidden || contentType != ContentType.LYRICS) return
        lyricsControlsHidden = true
        animateControlsTo(1F) {
            setControlsVisibility(false)
        }
    }

    private fun showLyricsControls(scheduleHide: Boolean) {
        removeCallbacks(hideControlsRunnable)
        if (contentType != ContentType.LYRICS) return
        val needsAnimation = lyricsControlsHidden || controlsHideFraction > 0F
        lyricsControlsHidden = false
        setControlsVisibility(true)
        if (needsAnimation) {
            animateControlsTo(0F)
        }
        if (scheduleHide) scheduleControlsHide()
    }

    private fun animateControlsTo(target: Float, onEnd: (() -> Unit)? = null) {
        controlsAnimator?.cancel()
        var cancelled = false
        controlsAnimator = ValueAnimator.ofFloat(controlsHideFraction, target).apply {
            duration = MID_DURATION
            interpolator = AnimationUtils.fastOutSlowInInterpolator
            addUpdateListener {
                applyControlsHideFraction(it.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    controlsAnimator = null
                    applyControlsHideFraction(target)
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun applyControlsHideFraction(fraction: Float) {
        controlsHideFraction = fraction
        listOf<View>(
            progressOverlaySlider,
            currentTimestampTextView,
            leftTimestampTextView,
            controllerButton,
            previousButton,
            nextButton,
            volumeOverlaySlider,
            speakerHintView,
            speakerFullHintView,
            captionOverlayButton,
            airplayOverlayButton,
            listOverlayButton,
            outputDeviceIcon,
            outputDeviceName,
            qualityBadge,
        ).forEach { applyControlCollapse(it, fraction) }
        updateLyricsClip(fraction)
    }

    private fun applyControlCollapse(view: View, fraction: Float) {
        view.alpha = 1F - fraction
        val translation = maxOf(height.toFloat() - view.bottom, 24.dp.px)
        view.translationY = fraction * translation
    }

    private fun setControlsVisibility(visible: Boolean) {
        val visibility = if (visible) VISIBLE else INVISIBLE
        listOf<View>(
            progressOverlaySlider,
            currentTimestampTextView,
            leftTimestampTextView,
            controllerButton,
            previousButton,
            nextButton,
            volumeOverlaySlider,
            speakerHintView,
            speakerFullHintView,
            captionOverlayButton,
            listOverlayButton,
        ).forEach { it.visibility = visibility }
        if (visible) {
            refreshOutputDevice()
            syncQualityBadgeVisibility()
        } else {
            airplayOverlayButton.visibility = INVISIBLE
            outputDeviceIcon.visibility = INVISIBLE
            outputDeviceName.visibility = INVISIBLE
            qualityBadge.visibility = INVISIBLE
        }
    }

    private fun updateLyricsClip(fraction: Float) {
        val height = fadingEdgeLayout.height
        if (height <= 0) {
            fadingEdgeLayout.doOnLayout { updateLyricsClip(fraction) }
            return
        }
        if (fraction >= 1F) {
            fadingEdgeLayout.clipBounds = null
            fadingEdgeLayout.setBottomFadeOffset(0)
            return
        }
        val controlsTop = (
            progressOverlaySlider.top - fadingEdgeLayout.top - 16.dp.px
        ).toInt().coerceIn(0, height)
        val clipBottom = (
            controlsTop + (height - controlsTop) * fraction
        ).toInt().coerceIn(0, height)
        lyricsClipRect.set(0, 0, fadingEdgeLayout.width, clipBottom)
        fadingEdgeLayout.clipBounds = lyricsClipRect
        fadingEdgeLayout.setBottomFadeOffset(height - clipBottom)
    }

    private var lastDisposable: Disposable? = null

    /**
     * The badge describes the format the player selected, which is only known once the tracks for
     * the new item have been read - hence here rather than on the media item transition.
     */
    /**
     * Pulls the lyrics the playback service resolved for the current track - embedded tags, a
     * matching .lrc, or the Jellyfin server - and hands them to the lyrics view. The service does
     * the resolving off the main thread and announces a result with this same command, so this runs
     * both on transition and when that announcement arrives.
     */
    private fun refreshLyrics() {
        val controller = instance ?: return
        CoroutineScope(Dispatchers.Main).launch {
            // Two steps on purpose. MediaController rejects calls from any thread but the one it
            // was built on, so the command has to be sent from here; waiting on the reply blocks,
            // so that part cannot be. Doing both off-main threw IllegalStateException every time,
            // which is why lyrics never appeared.
            val resolved = runCatching {
                val future = controller.sendCustomCommand(
                    SessionCommand(GramophonePlaybackService.SERVICE_GET_LYRICS, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                withContext(Dispatchers.IO) {
                    @Suppress("UNCHECKED_CAST")
                    BundleCompat.getParcelableArray(
                        future.get().extras, "lyrics", MediaStoreUtils.Lyric::class.java
                    ) as Array<MediaStoreUtils.Lyric>?
                }?.toList()
            }.onFailure { Log.e(TAG, "fetching lyrics failed", it) }.getOrNull()
            val mapped = resolved.orEmpty()
                // The service prepends an empty element as a lead-in; it has no text to show.
                .filter { !it.content.isNullOrBlank() }
                .map { LyricsLine(it.startTimestamp ?: 0L, null, it.content, it.translationContent) }
            withContext(Dispatchers.Main) {
                // An empty list renders as a black screen with nothing in it, which reads as a bug
                // rather than as "this track has no lyrics". Say so instead.
                lyricsViewModel?.setLyrics(
                    if (mapped.isEmpty()) {
                        Lyrics(listOf(LyricsLine(0L, null, context.getString(R.string.no_lyrics), null)))
                    } else {
                        Lyrics(mapped)
                    }
                )
            }
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        val details = AudioQuality.detailsOf(tracks)
        currentQualityDetails = details
        if (details == null) {
            qualityBadge.visibility = GONE
            showAvailableQualityHint()
        } else {
            qualityBadge.setText(details.quality.label)
            syncQualityBadgeVisibility()
        }
    }

    /**
     * Mentions, once and briefly, that the source is better than what is playing.
     *
     * Only reachable when there is no badge, which is exactly the case worth saying something
     * about: the badge describes what the decoder produced, so a lossless file streamed under a
     * cap arrives as AAC and shows nothing at all. Left permanent it would be a label complaining
     * about a setting the user chose deliberately; three seconds is enough to answer "could this
     * sound better?" without becoming furniture.
     */
    private fun showAvailableQualityHint() {
        qualityAvailableHint.animate().cancel()
        qualityAvailableHint.alpha = 0f

        val container = currentSourceContainer() ?: return
        if (container !in LOSSLESS_CONTAINERS) return
        // Nothing to advertise when the untouched file is already what is playing.
        if (StreamQuality.streamingQuality(context).isOriginal) return

        // Lossless, not Hi-Res: the library stores a container and nothing about bit depth or
        // sample rate, so the difference between 16/44 and 24/96 is not knowable here. Saying
        // Hi-Res on a guess would be worse than saying the smaller true thing.
        qualityAvailableHint.setText(R.string.quality_lossless_available)
        qualityAvailableHint.animate()
            .alpha(1f)
            .setDuration(QUALITY_HINT_FADE_MS)
            .withEndAction {
                qualityAvailableHint.animate()
                    .alpha(0f)
                    .setStartDelay(QUALITY_HINT_HOLD_MS)
                    .setDuration(QUALITY_HINT_FADE_MS)
                    .start()
            }
            .start()
    }

    /** The container of the file on the server, which the library knows even when playing AAC. */
    private fun currentSourceContainer(): String? =
        instance?.currentMediaItem?.mediaMetadata?.extras?.getString(EXTRA_SOURCE_CONTAINER)
            ?.lowercase()

    private fun syncQualityBadgeVisibility() {
        qualityBadge.visibility = when {
            currentQualityDetails == null -> GONE
            lyricsControlsHidden -> INVISIBLE
            else -> VISIBLE
        }
    }

    /**
     * Names the format behind the badge - "FLAC 24-bit/96 kHz" - which is the one thing the badge
     * itself cannot say, since 24/48 and 24/192 both read "Hi-Res Lossless".
     */
    private fun showQualityDetails() {
        val details = currentQualityDetails ?: return
        val parts = buildList {
            details.codec?.let { add(it) }
            val bitDepth = details.bitDepth
            val sampleRate = details.sampleRateHz
            if (bitDepth != null && sampleRate != null) {
                add(context.getString(R.string.music_quality_depth_rate, bitDepth, sampleRate.khz()))
            } else if (sampleRate != null) {
                add(context.getString(R.string.music_quality_rate, sampleRate.khz()))
            }
        }
        AlertDialog.Builder(context)
            .setTitle(details.quality.label)
            .setMessage(
                parts.joinToString(" ").ifEmpty { context.getString(R.string.music_quality_unknown) }
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 44100 reads as "44.1", 48000 as "48" - trailing zeroes here are noise. */
    private fun Int.khz(): String = "%.1f".format(this / 1000f).removeSuffix(".0")

    /**
     * Where the artwork is, for the panel's gesture handling. Null while the player is not fully
     * open, where the cover is a thumbnail in the collapsed bar and swiping it means nothing.
     */
    override fun coverBounds(): RectF? {
        // onSlide keeps this in step with the panel; a swipe only means anything once the player is
        // fully open, where the cover is a large target rather than a thumbnail in the collapsed bar.
        if (slideFraction < 1F) return null
        val location = IntArray(2)
        val panelLocation = IntArray(2)
        coverSimpleImageView.getLocationOnScreen(location)
        floatingPanelLayout.getLocationOnScreen(panelLocation)
        val left = (location[0] - panelLocation[0]).toFloat()
        val top = (location[1] - panelLocation[1]).toFloat()
        return RectF(
            left, top,
            left + coverSimpleImageView.width, top + coverSimpleImageView.height
        )
    }

    /**
     * The cover follows the finger at half distance rather than one-to-one: it is anchored in the
     * layout and cannot actually leave, and a cover that tracks the finger exactly reads as
     * something that should come away in your hand.
     */
    override fun onCoverSwipeMove(dx: Float) {
        coverSlideAnimator?.cancel()
        coverSlideAnimator = null
        val player = instance
        val actionAvailable = if (dx < 0F) {
            player?.hasNextMediaItem() == true
        } else {
            player?.hasPreviousMediaItem() == true || (player?.currentPosition ?: 0L) > 0L
        }
        coverSwipeHaptics.update(
            coverSimpleImageView,
            dx,
            coverSimpleImageView.width * COVER_SWIPE_THRESHOLD,
            actionAvailable,
        )
        coverSlideOffsetX = resistedSwipeDistance(
            dx,
            coverSimpleImageView.width * COVER_SWIPE_MAX_TRAVEL,
            COVER_SWIPE_FOLLOW,
        )
        applyCoverTranslation()
    }

    override fun onCoverSwipeEnd(dx: Float, velocityX: Float) {
        val flingThreshold = ViewConfiguration.get(context).scaledMinimumFlingVelocity *
            COVER_FLING_VELOCITY_MULTIPLIER
        val hasFling = abs(velocityX) >= flingThreshold
        val releaseDirection = if (hasFling) velocityX else dx
        if (abs(dx) >= coverSimpleImageView.width * COVER_SWIPE_THRESHOLD || hasFling) {
            // Dragging the current cover away to the left brings on the next track, matching
            // how every carousel on the platform reads. The cover is not sent home here - the
            // track change does that, carrying it the rest of the way out and bringing the new
            // one in.
            val player = instance
            val forwards = releaseDirection < 0F
            // At the end of the queue seekToNext does nothing, so no track change arrives and
            // nothing would ever bring the cover back - it sat where the finger left it.
            val willMove = if (forwards) {
                player?.hasNextMediaItem() == true
            } else {
                player?.hasPreviousMediaItem() == true || (player?.currentPosition ?: 0L) > 0L
            }
            if (willMove) {
                coverSwipeHaptics.commit(coverSimpleImageView)
                pendingCoverReleaseVelocity = velocityX
                pendingCoverSlide = if (forwards) SLIDE_NEXT else SLIDE_PREVIOUS
                if (forwards) player?.seekToNext() else player?.seekToPrevious()
                // A backstop for the cases the check above cannot see - a repeat mode changing
                // under us, or a queue emptied while the finger was down.
                postDelayed(coverSlideBackstop, COVER_SLIDE_BACKSTOP_MS)
                return
            }
        }
        // Not far enough to count. Back where it was.
        coverSwipeHaptics.release(coverSimpleImageView)
        val projectedOffset = resistedSwipeDistance(
            dx + velocityX * COVER_MOMENTUM_PROJECTION_SECONDS,
            coverSimpleImageView.width * COVER_SWIPE_MAX_TRAVEL,
            COVER_SWIPE_FOLLOW,
        )
        val continuesCurrentDirection = coverSlideOffsetX == 0F ||
            projectedOffset * coverSlideOffsetX > 0F
        if (continuesCurrentDirection && abs(projectedOffset) > abs(coverSlideOffsetX)) {
            animateCoverOffset(
                projectedOffset,
                COVER_MOMENTUM_MS,
                LinearInterpolator(),
            ) {
                animateCoverOffset(0F, COVER_SWIPE_SETTLE_MS, settleInterpolator)
            }
        } else {
            animateCoverOffset(0F, COVER_SWIPE_SETTLE_MS, settleInterpolator)
        }
    }

    /**
     * Shows what is actually connected in place of the fixed AirPlay glyph, with its name beneath.
     * On the phone's own speaker there is nothing worth naming, so the AirPlay button stays as it
     * was - a way into the system output picker.
     */
    private fun refreshOutputDevice() {
        val device = AudioOutput.current(context)
        if (!device.isExternal || device.name == null) {
            outputDeviceIcon.visibility = GONE
            outputDeviceName.visibility = GONE
            airplayOverlayButton.visibility = VISIBLE
            return
        }
        outputDeviceIcon.setImageResource(device.icon)
        outputDeviceIcon.visibility = VISIBLE
        outputDeviceName.text = device.name
        outputDeviceName.visibility = VISIBLE
        // Hidden rather than removed: the device icon is constrained to this button's bounds, so it
        // still has to occupy its place in the row.
        airplayOverlayButton.visibility = INVISIBLE
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int
    ) {
        coverSwipeHaptics.reset()
        fullPlayerToolbar.onMediaItemTransition(mediaItem, reason)
        // Hide until the new item's tracks arrive, so the previous track's badge does not linger
        // over a different song.
        qualityBadge.visibility = GONE
        lyricsViewModel?.setLyrics(Lyrics.Empty)
        refreshLyrics()
        if (instance?.mediaItemCount != 0) {
            lastDisposable?.dispose()
            lastDisposable = null
            // A track that ended on its own is still going forwards, so it gets the same movement
            // as pressing next; only the very first item appears without travelling.
            if (pendingCoverSlide == SLIDE_NONE && !firstTime) pendingCoverSlide = SLIDE_NEXT
            startCoverSlideOut()
            loadCoverForImageView()

            titleTextView.setTextAnimation(
                mediaItem?.mediaMetadata?.title ?: "",
                skipAnimation = firstTime
            )
            subtitleTextView.setTextAnimation(
                mediaItem?.mediaMetadata?.artist ?: context.getString(R.string.default_artist),
                skipAnimation = firstTime
            )
            updateProgressDisplay()
            syncFavoriteButtonsForCurrentItem()
            topUpQueueIfNeeded()
        } else {
            lastDisposable?.dispose()
            lastDisposable = null
            updateProgressDisplay()
            updateFavoriteButtons(false)
        }
    }

    /**
     * Carries the outgoing artwork off the side it is leaving by.
     *
     * Deliberately not tied to the artwork finishing loading: the cover has to start moving the
     * instant the track changes, or a slow network makes the player look stuck.
     */
    private fun startCoverSlideOut() {
        if (pendingCoverSlide == SLIDE_NONE) return
        coverSlideInFlight = true
        coverSlideOutDone = false
        coverSlideArtReady = false

        val target = -pendingCoverSlide * coverSlideDistance()
        // A swipe has already carried the cover part of the way, so the rest of the journey is
        // shorter and has to take proportionally less time. At a fixed duration the cover
        // visibly changed speed the instant the finger left it.
        val remaining = abs(target - coverSlideOffsetX)
        val velocityDuration = abs(pendingCoverReleaseVelocity)
            .takeIf {
                it >= ViewConfiguration.get(context).scaledMinimumFlingVelocity *
                    COVER_FLING_VELOCITY_MULTIPLIER
            }
            ?.let { ((remaining / it) * 1000F).toLong() }
        val duration = (velocityDuration ?: (COVER_SLIDE_OUT_MS *
            (remaining / coverSlideDistance())).toLong())
            .coerceIn(COVER_SLIDE_MIN_MS, COVER_SLIDE_OUT_MS)
        pendingCoverReleaseVelocity = 0F

        // Linear, not accelerating: a swipe hands over at speed, and easing in from a moving
        // finger reads as the cover briefly slowing down before it leaves.
        animateCoverOffset(target, duration, LinearInterpolator()) {
            coverSlideOutDone = true
            slideCoverInIfReady()
        }
        // The artwork is not going to be waited for indefinitely. On a shuffled library the
        // next cover is never cached, so waiting meant the screen sat empty for a network
        // round trip every time - which is the pause people describe as the app hanging.
        removeCallbacks(coverSlideInDeadline)
        postDelayed(coverSlideInDeadline, COVER_ART_WAIT_MS)
    }

    /**
     * Puts the new artwork everywhere it belongs.
     *
     * Everything except the large cover takes it at once - the blended background and the collapsed
     * bar should follow the track immediately. The large cover waits until it has finished leaving.
     */
    private fun applyCover(
        drawable: android.graphics.drawable.Drawable?,
        bitmap: android.graphics.Bitmap?
    ) {
        blendView.setImageBitmap(bitmap)
        fullPlayerToolbar.setImageViewCover(drawable)
        floatingPanelLayout.transitionImageView?.setImageDrawable(drawable)
        floatingPanelLayout.setPreviewCover(drawable)
        if (coverSlideInFlight && !coverSlideArtReady) {
            // Still on its way out, or waiting to come back - hold it until it is out of sight.
            pendingCoverDrawable = drawable
            onCoverArtReady()
        } else {
            // Either nothing is moving, or the cover came back before the artwork did and is
            // showing the placeholder; either way it belongs on screen now.
            coverSimpleImageView.setImageDrawable(drawable)
        }
    }

    private fun isAutoplayEnabled() =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_AUTOPLAY, false)

    /**
     * Adds more music when the queue is running out and infinity play is on.
     *
     * Runs on every track change rather than only at the very end, so the queue is topped up
     * before the gap is audible. Choosing the tracks touches the network, so it happens off the
     * main thread; adding them has to be back on it, because the controller allows nothing else.
     */
    private fun topUpQueueIfNeeded() {
        if (!isAutoplayEnabled()) return
        val player = instance ?: return
        val remaining = player.mediaItemCount - 1 - player.currentMediaItemIndex
        if (remaining > AutoplayQueue.TOP_UP_THRESHOLD) return

        val seed = player.currentMediaItem
        val queued = buildSet {
            for (index in 0 until player.mediaItemCount) {
                add(player.getMediaItemAt(index).mediaId)
            }
        }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val library = activity.reader.songListSnapshot()
            val batch = withContext(Dispatchers.IO) {
                AutoplayQueue.nextBatch(appContext, seed, library, queued)
            }
            if (batch.isNotEmpty()) instance?.addMediaItems(batch)
        }
    }

    /** Called when the new artwork is available. */
    private fun onCoverArtReady() {
        if (!coverSlideInFlight) return
        coverSlideArtReady = true
        slideCoverInIfReady()
    }

    /**
     * Brings the incoming artwork in from the opposite side.
     *
     * Waits for both the outgoing movement to finish and the new artwork to exist. Cached artwork
     * arrives in a few milliseconds, and starting the return before the cover had left teleported it
     * across the screen mid-flight.
     */
    private fun slideCoverInIfReady() {
        if (!coverSlideOutDone || !coverSlideArtReady) return
        val direction = pendingCoverSlide
        pendingCoverSlide = SLIDE_NONE
        if (direction == SLIDE_NONE) {
            coverSlideInFlight = false
            return
        }
        removeCallbacks(coverSlideInDeadline)
        // Swapped now, out of sight, so the cover that comes back is the new track's. When it
        // has not loaded yet the placeholder comes back instead and the real artwork appears
        // in place a moment later - far better than an empty screen while the network answers.
        val incoming = pendingCoverDrawable
        if (incoming != null) {
            coverSimpleImageView.setImageDrawable(incoming)
        } else {
            coverSimpleImageView.setImageDrawable(
                AppCompatResources.getDrawable(context, R.drawable.default_cover)
            )
        }
        pendingCoverDrawable = null
        coverSlideOffsetX = direction * coverSlideDistance()
        applyCoverTranslation()
        // Ends at exactly zero displacement, so nothing is left to correct and there is no snap.
        animateCoverOffset(0F, COVER_SLIDE_IN_MS, settleInterpolator) {
            coverSlideInFlight = false
        }
    }

    /**
     * Leaves quickly and settles softly, so the arrival reads as a landing rather than a stop.
     */
    private val settleInterpolator = PathInterpolator(0.17F, 0.89F, 0.32F, 1F)

    /** Far enough that the cover is clear of the screen rather than parked at its own edge. */
    private fun coverSlideDistance(): Float =
        (coverSimpleImageView.width + 48.dp.px).coerceAtLeast(1F)

    private fun loadCoverForImageView() {
        if (lastDisposable != null) {
            lastDisposable?.dispose()
            lastDisposable = null
            Log.e(TAG, "raced while loading cover in onMediaItemTransition?")
        }
        val mediaItem = instance?.currentMediaItem
        Log.d(TAG, "load cover for ${mediaItem?.mediaMetadata?.title} considered")
        if (coverSimpleImageView.width != 0 && coverSimpleImageView.height != 0) {
            Log.d(
                TAG,
                "load cover for ${mediaItem?.mediaMetadata?.title} at ${coverSimpleImageView.width} ${coverSimpleImageView.height}"
            )
            lastDisposable = context.imageLoader.enqueue(
                ImageRequest.Builder(context).apply {
                    data(mediaItem?.mediaMetadata?.artworkUri)
                    size(coverSimpleImageView.width, coverSimpleImageView.height)
                    scale(Scale.FILL)
                    target(onSuccess = {
                        applyCover(it.asDrawable(context.resources), it.toBitmap())
                    }, onError = {
                        applyCover(it?.asDrawable(context.resources), it?.toBitmap())
                    }) // do not react to onStart() which sets placeholder
                    allowHardware(coverSimpleImageView.isHardwareAccelerated)
                }.build()
            )
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateRepeatButton(repeatMode)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        queueShuffleButton.isChecked = shuffleModeEnabled
    }

    override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
        Log.d("FullPlayer", "onPlaybackStateChanged: $playbackState")
        val isPlaying = instance?.isPlaying == true
        updateCoverPauseScale(
            isPlaying = isPlaying,
            animate = !firstTime
        )
        if (isPlaying) {
            controllerButton.playAnimation(false)
        } else if (playbackState != Player.STATE_BUFFERING) {
            controllerButton.playAnimation(true)
        }
        if (isPlaying) {
            startPositionUpdates()
        } else {
            stopPositionUpdates()
            updateProgressDisplay()
        }
        /*
        if (instance?.isPlaying == true) {
            if (bottomSheetFullControllerButton.getTag(R.id.play_next) as Int? != 1) {
                bottomSheetFullControllerButton.icon =
                    AppCompatResources.getDrawable(
                        wrappedContext ?: context,
                        R.drawable.play_anim
                    )
                bottomSheetFullControllerButton.background =
                    AppCompatResources.getDrawable(context, R.drawable.bg_play_anim)
                bottomSheetFullControllerButton.icon.startAnimation()
                bottomSheetFullControllerButton.background.startAnimation()
                bottomSheetFullControllerButton.setTag(R.id.play_next, 1)
            }
            if (!isUserTracking) {
                progressDrawable.animate = true
            }
            if (!runnableRunning) {
                runnableRunning = true
                handler.postDelayed(positionRunnable, SLIDER_UPDATE_INTERVAL)
            }
            bottomSheetFullCover.startRotation()
        } else if (playbackState != Player.STATE_BUFFERING) {
            if (bottomSheetFullControllerButton.getTag(R.id.play_next) as Int? != 2) {
                bottomSheetFullControllerButton.icon =
                    AppCompatResources.getDrawable(
                        wrappedContext ?: context,
                        R.drawable.pause_anim
                    )
                bottomSheetFullControllerButton.background =
                    AppCompatResources.getDrawable(context, R.drawable.bg_pause_anim)
                bottomSheetFullControllerButton.icon.startAnimation()
                bottomSheetFullControllerButton.background.startAnimation()
                bottomSheetFullControllerButton.setTag(R.id.play_next, 2)
                bottomSheetFullCover.stopRotation()
            }
            if (!isUserTracking) {
                progressDrawable.animate = false
            }
        }

        */
    }

    private fun updateCoverPauseScale(isPlaying: Boolean, animate: Boolean) {
        val targetScale = if (isPlaying) 1F else PAUSED_COVER_SCALE
        if (coverPauseScale == targetScale) return
        coverPauseAnimator?.cancel()
        coverPauseAnimator = null
        if (!animate) {
            coverPauseScale = targetScale
            applyCoverScale()
            syncTransitionCoverScale()
            return
        }
        coverPauseAnimator = AnimationUtils.createValAnimator(
            coverPauseScale,
            targetScale,
            duration = LONG_DURATION,
            interpolator = AnimationUtils.easingStandardInterpolator
        ) {
            coverPauseScale = it
            applyCoverScale()
            syncTransitionCoverScale()
        }
    }

    private fun applyCoverScale() {
        val queueBlend = transformationFraction.coerceIn(0F, 1F)
        val effectivePauseScale = lerp(coverPauseScale, 1F, queueBlend)
        val scale = coverBaseScale * effectivePauseScale
        coverSimpleImageView.pivotX = 0F
        coverSimpleImageView.pivotY = 0F
        coverSimpleImageView.scaleX = scale
        coverSimpleImageView.scaleY = scale
        val pauseOffsetX =
            coverSimpleImageView.width * coverBaseScale * (1f - effectivePauseScale) / 2f
        val pauseOffsetY =
            coverSimpleImageView.height * coverBaseScale * (1f - effectivePauseScale) / 2f
        // The resting position is always recorded, even mid-slide, because that is where the
        // incoming artwork has to come to a stop.
        coverRestingTranslationX = coverBaseTranslationX + pauseOffsetX
        applyCoverTranslation()
        coverSimpleImageView.translationY = coverBaseTranslationY + pauseOffsetY
    }

    /** The one place the cover's horizontal position is written. */
    private fun applyCoverTranslation() {
        coverSimpleImageView.translationX = coverRestingTranslationX + coverSlideOffsetX
    }

    /**
     * Animates the displacement to [target].
     *
     * A value animator rather than ViewPropertyAnimator: the offset has to be readable every
     * frame so the paused-state shrink can keep adjusting the resting position underneath it.
     */
    private fun animateCoverOffset(
        target: Float,
        duration: Long,
        interpolator: Interpolator,
        onEnd: (() -> Unit)? = null,
    ) {
        coverSlideAnimator?.cancel()
        coverSlideAnimator = ValueAnimator.ofFloat(coverSlideOffsetX, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                coverSlideOffsetX = it.animatedValue as Float
                applyCoverTranslation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    coverSlideAnimator = null
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun syncTransitionCoverScale() {
        if (!coverSimpleImageView.isLaidOut) return
        updateTransitionTargetForContentType(contentType)
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        updateVolumeSlider(volume)
    }
    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
            updateProgressDisplay()
        }
        val window = Timeline.Window()
        val items = mutableListOf<QueueItem>()
        for (i in 0 until timeline.windowCount) {
            val w = timeline.getWindow(i, window)
            items.add(QueueItem(w.uid, w.mediaItem))
        }
        (queueRecyclerView.adapter as? QueuePreviewAdapter)?.updateItems(items)
    }

    override fun onDetachedFromWindow() {
        stopPositionUpdates()
        removeCallbacks(hideControlsRunnable)
        controlsAnimator?.cancel()
        controlsAnimator = null
        contentTypeAnimator?.cancel()
        contentTypeAnimator = null
        bottomButtonAnimators.values.toList().forEach { it.cancel() }
        bottomButtonAnimators.clear()
        airplayOverlayButton.removeCallbacks(resetAirplayButton)
        removeCallbacks(coverSlideBackstop)
        removeCallbacks(coverSlideInDeadline)
        coverSlideAnimator?.cancel()
        coverSlideAnimator = null
        coverSwipeHaptics.reset()
        lyricsViewModel?.release()
        lyricsViewModel = null
        lyricsViewAttached = false
        if (isVolumeReceiverRegistered) {
            runCatching { context.unregisterReceiver(volumeChangeReceiver) }
            isVolumeReceiverRegistered = false
        }
        audioDeviceCallback?.let {
            runCatching { AudioOutput.unregister(context, it) }
            audioDeviceCallback = null
        }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (audioDeviceCallback == null) {
            audioDeviceCallback = AudioOutput.register(context) { refreshOutputDevice() }
        }
        refreshOutputDevice()
        if (!isVolumeReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_MUTE_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(volumeChangeReceiver, filter)
            }
            isVolumeReceiverRegistered = true
        }
    }

    /*
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val isHeart = (mediaMetadata.userRating as? HeartRating)?.isHeart == true
        if (bottomSheetFavoriteButton.isChecked != isHeart) {
            bottomSheetFavoriteButton.removeOnCheckedChangeListener(this)
            bottomSheetFavoriteButton.isChecked =
                (mediaMetadata.userRating as? HeartRating)?.isHeart == true
            bottomSheetFavoriteButton.addOnCheckedChangeListener(this)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
            updateDuration()
        }
    }

    private fun updateDuration() {
        val duration = instance?.contentDuration?.let { if (it == C.TIME_UNSET) null else it }
            ?: instance?.currentMediaItem?.mediaMetadata?.durationMs
        if (duration != null && duration.toInt() != bottomSheetFullSeekBar.max) {
            bottomSheetFullDuration.setTextAnimation(
                CalculationUtils.convertDurationToTimeStamp(duration)
            )
            val position =
                CalculationUtils.convertDurationToTimeStamp(instance?.currentPosition ?: 0)
            if (!isUserTracking) {
                bottomSheetFullSeekBar.max = duration.toInt()
                bottomSheetFullSeekBar.progress = instance?.currentPosition?.toInt() ?: 0
                bottomSheetFullSlider.valueTo = duration.toFloat().coerceAtLeast(1f)
                bottomSheetFullSlider.value =
                    min(instance?.currentPosition?.toFloat() ?: 0f, bottomSheetFullSlider.valueTo)
                bottomSheetFullPosition.text = position
            }
            bottomSheetFullLyricView.updateLyricPositionFromPlaybackPos()
        }
    }
     */

    enum class ContentType {
        LYRICS, NORMAL, PLAYLIST
    }

    companion object {
        const val TAG = "FullPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 500L

        /** Containers worth mentioning when a quality cap is hiding what they hold. */
        private val LOSSLESS_CONTAINERS = setOf("flac", "alac", "wav", "aiff", "ape", "wv")

        private const val QUALITY_HINT_FADE_MS = 220L
        private const val QUALITY_HINT_HOLD_MS = 3_000L
        private const val PAUSED_COVER_SCALE = 0.84F

        /** How far the cover follows the finger, and how far it has to go to count as a swipe. */
        private const val COVER_SWIPE_FOLLOW = 0.56F
        private const val COVER_SWIPE_THRESHOLD = 0.32F
        private const val COVER_SWIPE_MAX_TRAVEL =
            COVER_SWIPE_THRESHOLD * COVER_SWIPE_FOLLOW
        private const val COVER_FLING_VELOCITY_MULTIPLIER = 1.35F
        private const val COVER_MOMENTUM_PROJECTION_SECONDS = 0.07F
        private const val COVER_MOMENTUM_MS = 70L
        private const val COVER_SWIPE_SETTLE_MS = 190L

        /** Which way the artwork travels on a track change. */
        private const val SLIDE_NONE = 0
        private const val SLIDE_NEXT = 1
        private const val SLIDE_PREVIOUS = -1
        private const val COVER_SLIDE_OUT_MS = 180L
        private const val COVER_SLIDE_IN_MS = 260L
        private const val COVER_SLIDE_MIN_MS = 70L
        private const val COVER_SLIDE_BACKSTOP_MS = 400L

        /** The longest the cover stays off screen waiting for artwork to load. */
        private const val COVER_ART_WAIT_MS = 90L
        private const val CONTROLS_HIDE_DELAY_MS = 3_000L

        private const val PREF_AUTOPLAY = "autoplay_similar"
    }

}
