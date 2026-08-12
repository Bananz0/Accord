package uk.akane.accord.ui

import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.CornerFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import org.akanework.gramophone.logic.data.lyrics.LyricsIndexWorker
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.logic.enableEdgeToEdgeProperly
import uk.akane.accord.logic.isDarkMode
import uk.akane.accord.logic.utils.CalculationUtils.lerp
import uk.akane.accord.logic.utils.UiUtils
import uk.akane.accord.setupwizard.fragments.SetupWizardFragment
import uk.akane.accord.ui.components.player.FloatingPanelLayout
import uk.akane.accord.ui.components.GlobalTapHaptics
import uk.akane.accord.ui.components.performPressHaptic
import uk.akane.accord.ui.fragments.BrowseFragment
import uk.akane.accord.ui.fragments.HomeFragment
import uk.akane.accord.ui.fragments.LibraryFragment
import uk.akane.accord.ui.fragments.SearchFragment
import uk.akane.accord.ui.viewmodels.MediaControllerViewModel
import uk.akane.cupertino.navigation.FragmentSwitcherView
import uk.akane.cupertino.utils.AnimationUtils
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStateAtLeast
import androidx.lifecycle.Lifecycle
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import org.akanework.gramophone.logic.data.library.songListSnapshot
import uk.akane.accord.ui.fragments.SettingsFragment

class MainActivity : AppCompatActivity() {
    companion object {
        const val DESIRED_BOTTOM_SHEET_OPEN_RATIO = 0.9f
        const val DESIRED_BOTTOM_SHEET_DISPLAY_RATIO = 0.85F

        const val PLAYBACK_AUTO_START_FOR_FGS = "AutoStartFgs"
        const val PLAYBACK_AUTO_PLAY_ID = "AutoStartId"
        const val PLAYBACK_AUTO_PLAY_POSITION = "AutoStartPos"

        private const val PLAY_ON_LAUNCH = "autoplay"
        private const val IMMERSIVE_MODE = "immersive_mode"
        private const val IMMERSIVE_MODE_RESET = "immersive_mode_reset"
        private const val BACKGROUNDLESS_STATUS_BAR = "backgroundless_status_bar"

        /** How long a launch will wait for the playback service to hand back the saved queue. */
        private const val PLAY_ON_LAUNCH_TIMEOUT_MS = 10_000L
    }

    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var floatingPanelLayout: FloatingPanelLayout
    private lateinit var shrinkContainerLayout: MaterialCardView
    /**
     * The display's corner radii, refined once window insets arrive.
     *
     * Square by default rather than lateinit. The real values are only knowable from the insets
     * dispatched to the bottom navigation bar, and anything that draws before that dispatch - a
     * cold launch onto a locked screen gets there first - would otherwise touch an uninitialised
     * property and take the app down. Square corners for the first frame are not worth a crash.
     */
    private var screenCorners = UiUtils.ScreenCorners(0f, 0f, 0f, 0f)
    lateinit var fragmentSwitcherView: FragmentSwitcherView

    private var bottomInset: Int = 0
    private var bottomDefaultRadius: Int = 0

    private var bottomNavigationPanelColor: Int = 0

    private var isWindowColorSet: Boolean = false

    private var isHandlingNowPlayingBack: Boolean = false
    private var nowPlayingBackStartFraction: Float = 0F

    /**
     * Swallows the system volume panel while the now-playing screen is open.
     *
     * That screen has a volume slider of its own, and the system's overlay lands on top of it - two
     * bars for one thing. The volume itself still changes, and the player's slider follows it
     * through the broadcast it already listens for. Collapsed, the keys behave normally.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && isNowPlayingOpen()) {
            ContextCompat.getSystemService(this, AudioManager::class.java)?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE
                else AudioManager.ADJUST_LOWER,
                // No FLAG_SHOW_UI: that flag is the overlay.
                0
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Consumed as well, or the system handles the release and shows the panel anyway. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && isNowPlayingOpen()) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    private fun isNowPlayingOpen() =
        ::floatingPanelLayout.isInitialized && floatingPanelLayout.slideFraction > 0F

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android's splash hook must be installed before Activity restoration. Installing it after
        // super.onCreate exposed the empty switcher container as a black frame on cold launches.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // So the hardware keys act on music rather than on the ringer when nothing is playing yet.
        volumeControlStream = AudioManager.STREAM_MUSIC

        UiUtils.init(this)

        bottomDefaultRadius = resources.getDimensionPixelSize(R.dimen.bottom_panel_radius)
        bottomNavigationPanelColor = getColor(R.color.bottomNavigationPanelColor)

        enableEdgeToEdgeProperly()

        // No stored value for immersive mode can reflect a decision: the switch was declared with
        // a default of true, but nothing read it, so what is on disk is just the default written
        // the first time the Appearance screen bound the row. Honouring that now would hide the
        // system bars on every device that has ever opened those settings. Cleared once, so the
        // feature starts off and the switch means something from here on.
        if (!prefs.getBoolean(IMMERSIVE_MODE_RESET, false)) {
            prefs.edit()
                .putBoolean(IMMERSIVE_MODE_RESET, true)
                .putBoolean(IMMERSIVE_MODE, false)
                .apply()
        }
        applySystemBarMode()

        // Only a genuine launch. A recreation - rotation, theme change - arrives with saved state,
        // and starting the music again there would be a rotation that plays music.
        val launching = savedInstanceState == null

        lifecycle.addObserver(controllerViewModel)
        controllerViewModel.addControllerCallback(lifecycle) { controller, _ ->
            if (launching) playOnLaunchIfWanted(controller)
        }

        // The navigation bar on every screen draws the signed-in user's Jellyfin picture, so it is
        // fetched once here rather than by each bar. Off the main thread: it opens the credential
        // store and asks the server.
        lifecycleScope.launch(Dispatchers.IO) { JellyfinUserImage.refresh() }

        // Queued, not run. The constraints mean it waits for a charger and an unmetered network, so
        // this costs nothing now and the lyric index quietly fills in overnight.
        LyricsIndexWorker.enqueue(applicationContext)

        setContentView(R.layout.activity_main)

        // Every clickable view gets a press cue from here, so feedback does not depend on whether
        // whoever wrote a given screen remembered to ask for it.
        GlobalTapHaptics.install(window)

        // Gated on having a Jellyfin server rather than on media permissions: the library lives on
        // the server, so an install with permissions but no server still has nothing to show.
        if (!JellyfinCredentialStore.hasStoredSession(this)) {
            insertContainer(SetupWizardFragment())
        } else {
            updateLibrary()
        }

        bottomNavigationView = findViewById(R.id.bottom_nav)
        floatingPanelLayout = findViewById(R.id.floating)
        shrinkContainerLayout = findViewById(R.id.shrink_container)
        fragmentSwitcherView = findViewById(R.id.switcher)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (floatingPanelLayout.slideFraction > 0F) {
                    isHandlingNowPlayingBack = true
                    nowPlayingBackStartFraction = floatingPanelLayout.slideFraction
                    floatingPanelLayout.setSlideFraction(nowPlayingBackStartFraction)
                    return
                }
                if (backEvent.swipeEdge != BackEventCompat.EDGE_LEFT &&
                    backEvent.swipeEdge != BackEventCompat.EDGE_RIGHT
                ) return
                fragmentSwitcherView.startPredictiveBack()
                fragmentSwitcherView.updatePredictiveBack(backEvent.progress)
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (isHandlingNowPlayingBack) {
                    val progress = backEvent.progress.coerceIn(0F, 1F)
                    floatingPanelLayout.setSlideFraction(nowPlayingBackStartFraction * (1F - progress))
                    return
                }
                if (backEvent.swipeEdge != BackEventCompat.EDGE_LEFT &&
                    backEvent.swipeEdge != BackEventCompat.EDGE_RIGHT
                ) return
                fragmentSwitcherView.updatePredictiveBack(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                if (isHandlingNowPlayingBack) {
                    isHandlingNowPlayingBack = false
                    floatingPanelLayout.animateTo(nowPlayingBackStartFraction)
                    return
                }
                fragmentSwitcherView.cancelPredictiveBack()
            }

            override fun handleOnBackPressed() {
                if (isHandlingNowPlayingBack || floatingPanelLayout.slideFraction > 0F) {
                    isHandlingNowPlayingBack = false
                    floatingPanelLayout.collapse()
                    return
                }
                if (fragmentSwitcherView.commitPredictiveBack()) {
                    return
                }
                if (!fragmentSwitcherView.popBackTopFragmentIfExists()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        val searchFragment = SearchFragment()
        fragmentSwitcherView.setup(
            this,
            listOf(
                HomeFragment(),
                BrowseFragment(),
                LibraryFragment(),
                searchFragment
            ),
            listOf(
                "Home",
                "Browse",
                "Library",
                "Search"
            )
        )
        fragmentSwitcherView.onStackChangeListener = { continueStackUnwind() }

        var bottomNavigationHapticsReady = false
        bottomNavigationView.setOnItemSelectedListener { item ->
            if (bottomNavigationHapticsReady) bottomNavigationView.performPressHaptic()
            val target = when (item.itemId) {
                    R.id.home -> 0
                    R.id.browse -> 1
                    R.id.library -> 2
                    R.id.search -> 3
                    else -> throw IllegalArgumentException("Invalid itemId!")
                }
            fragmentSwitcherView.switchBaseFragment(target)
            if (item.itemId == R.id.home) unwindCurrentStackTo(0)
            true
        }
        bottomNavigationView.setOnItemReselectedListener { item ->
            if (bottomNavigationHapticsReady) bottomNavigationView.performPressHaptic()
            when (item.itemId) {
                R.id.home -> unwindCurrentStackTo(0)
                R.id.search -> searchFragment.focusSearch()
            }
        }
        // Listener setup can select the initial Home item; startup should never buzz by itself.
        bottomNavigationView.post { bottomNavigationHapticsReady = true }

        floatingPanelLayout.addOnSlideListener(object : FloatingPanelLayout.OnSlideListener {
            override fun onSlideStatusChanged(status: FloatingPanelLayout.SlideStatus) {
                when (status) {
                    FloatingPanelLayout.SlideStatus.EXPANDED -> {
                        if (!isDarkMode() &&
                            floatingPanelLayout.insetController.isAppearanceLightStatusBars) {
                            floatingPanelLayout.insetController
                                .isAppearanceLightStatusBars = false
                        }
                    }
                    FloatingPanelLayout.SlideStatus.COLLAPSED -> {
                        shrinkContainerLayout.apply {
                            scaleX = 1f
                            scaleY = 1f
                        }
                        if (!isDarkMode() && ! floatingPanelLayout.insetController.isAppearanceLightStatusBars) {
                            floatingPanelLayout.insetController
                                .isAppearanceLightStatusBars = true
                        }
                    }
                    FloatingPanelLayout.SlideStatus.SLIDING -> {
                        if (!isDarkMode() && ! floatingPanelLayout.insetController.isAppearanceLightStatusBars) {
                            floatingPanelLayout.insetController
                                .isAppearanceLightStatusBars = true
                        }
                    }
                }
            }

            override fun onSlide(value: Float) {
                if (!isWindowColorSet) {
                    findViewById<View>(R.id.main).setBackgroundColor(
                        getColor(R.color.windowColor)
                    )
                    isWindowColorSet = true
                }
                shrinkContainer(value, DESIRED_BOTTOM_SHEET_OPEN_RATIO)
                val cornerProgress = (screenCorners.getAvgRadius() - bottomDefaultRadius) * value + bottomDefaultRadius
                floatingPanelLayout.panelCornerRadius = cornerProgress
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView) { v, windowInsetsCompat ->
            val insets = windowInsetsCompat.getInsets(WindowInsetsCompat.Type.navigationBars())
            val windowInsets = windowInsetsCompat.toWindowInsets()!!
            screenCorners = UiUtils.ScreenCorners(
                (windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0).toFloat(),
                (windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0).toFloat(),
                (windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0).toFloat(),
                (windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0).toFloat()
            )

            shrinkContainerLayout.shapeAppearanceModel =
                shrinkContainerLayout.shapeAppearanceModel
                    .toBuilder()
                    .setTopLeftCorner(CornerFamily.ROUNDED, screenCorners.topLeft)
                    .setTopRightCorner(CornerFamily.ROUNDED, screenCorners.topRight)
                    .setBottomLeftCorner(CornerFamily.ROUNDED, screenCorners.bottomLeft)
                    .setBottomRightCorner(CornerFamily.ROUNDED, screenCorners.bottomRight)
                    .build()
            bottomInset = insets.bottom
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

    }

    val bottomHeight: Int
        get() = bottomNavigationView.paddingBottom +
                resources.getDimensionPixelSize(R.dimen.bottom_nav_height_raw) +
                resources.getDimensionPixelSize(R.dimen.preview_player_height)

    private fun shrinkContainer(value: Float, ratio: Float) {
        shrinkContainerLayout.alpha = lerp(1f, 0.5f, value)
        shrinkContainerLayout.apply {
            scaleX = lerp(1f, ratio, value)
            scaleY = lerp(1f, ratio, value)
        }
    }

    private fun shrinkFloatingPanel(value: Float, ratio: Float) {
        floatingPanelLayout.alpha = lerp(1f, 0.5f, value)
        floatingPanelLayout.apply {
            scaleX = lerp(1f, ratio, value)
            scaleY = lerp(1f, ratio, value)
        }
    }

    private var containerId: Int = View.NO_ID

    private fun insertContainer(fragment: Fragment) {
        val container = FragmentContainerView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        containerId = container.id

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        rootView.addView(container)

        // Deferred until the activity is at least started, not merely until the container has been
        // laid out. A post alone commits whenever the next frame happens to arrive, and if the
        // activity was stopped in between - which is what launching onto a locked screen does - the
        // transaction lands after onSaveInstanceState and FragmentManager throws. Waiting for
        // STARTED also keeps the setup wizard, which is the fragment this usually carries:
        // committing with state loss instead would have dropped it and left a signed-out app
        // showing an empty library with no way to sign in.
        lifecycleScope.launch {
            lifecycle.withStateAtLeast(Lifecycle.State.STARTED) {}
            supportFragmentManager.beginTransaction()
                .replace(container.id, fragment)
                .runOnCommit {
                    // Round corner handling
                    val containerCardView: MaterialCardView = container.findViewById(R.id.root_card_view)
                    containerCardView.shapeAppearanceModel =
                        containerCardView.shapeAppearanceModel
                            .toBuilder()
                            .setTopLeftCorner(CornerFamily.ROUNDED, screenCorners.topLeft)
                            .setTopRightCorner(CornerFamily.ROUNDED, screenCorners.topRight)
                            .setBottomLeftCorner(CornerFamily.ROUNDED, screenCorners.bottomLeft)
                            .setBottomRightCorner(
                                CornerFamily.ROUNDED,
                                screenCorners.bottomRight
                            )
                            .build()
                    val screenHeight =
                        Resources.getSystem().displayMetrics.heightPixels.toFloat()

                    containerCardView.updateLayoutParams<MarginLayoutParams> {
                        topMargin =
                            ((1F - DESIRED_BOTTOM_SHEET_DISPLAY_RATIO + 0.05F) / 2 * screenHeight).toInt()
                    }

                    containerCardView.translationY = screenHeight
                    containerCardView.setCardBackgroundColor(resources.getColor(R.color.setupWizardSurfaceColor, null))

                    containerCardView.post {

                        containerCardView.visibility = View.VISIBLE

                        AnimationUtils.createValAnimator<Float>(
                            containerCardView.translationY,
                            0F,
                            duration = AnimationUtils.LONG_DURATION
                        ) { animatedValue ->
                            containerCardView.translationY = animatedValue
                            shrinkContainer(1f - animatedValue / screenHeight, DESIRED_BOTTOM_SHEET_DISPLAY_RATIO)
                            shrinkFloatingPanel(1f - animatedValue / screenHeight, DESIRED_BOTTOM_SHEET_DISPLAY_RATIO)
                        }
                    }
                }
                .commit()
        }
    }

    fun showContainer(fragment: Fragment) {
        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val existing = rootView.findViewById<FragmentContainerView>(containerId)
        if (existing != null) return
        insertContainer(fragment)
    }

    fun removeContainer() {
        val rootView = findViewById<ViewGroup>(android.R.id.content)

        val container = rootView.findViewById<FragmentContainerView>(containerId)
            ?: return

        val containerCardView: MaterialCardView = container.findViewById(R.id.root_card_view)
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels.toFloat()

        AnimationUtils.createValAnimator<Float>(
            0F,
            screenHeight,
            duration = AnimationUtils.LONG_DURATION,
            doOnEnd = {
                supportFragmentManager.findFragmentById(container.id)?.let {
                    supportFragmentManager.beginTransaction().remove(it).commit()
                }
                rootView.removeView(container)
                containerId = View.NO_ID
            }
        ) { animatedValue ->
            containerCardView.translationY = animatedValue
            shrinkContainer(1f - animatedValue / screenHeight, DESIRED_BOTTOM_SHEET_DISPLAY_RATIO)
            shrinkFloatingPanel(1f - animatedValue / screenHeight, DESIRED_BOTTOM_SHEET_DISPLAY_RATIO)
        }
    }

    /** Opens settings, from the profile control on whichever screen is showing. */
    fun openSettings() {
        // Settings itself owns an avatar and a general overflow menu. Both can route here, and
        // pushing again used to create Settings-on-Settings. If its root already exists, return to
        // that root instead of duplicating it.
        val existing = supportFragmentManager.fragments.any {
            it is SettingsFragment && it.isAdded
        }
        if (existing) {
            unwindCurrentStackTo(1)
            return
        }
        fragmentSwitcherView.addFragmentToCurrentStack(SettingsFragment())
    }

    private var stackUnwindTarget: Int? = null

    /** Pops a detail stack one animated page at a time; used by Home and duplicate Settings taps. */
    private fun unwindCurrentStackTo(size: Int) {
        stackUnwindTarget = size.coerceAtLeast(0)
        continueStackUnwind()
    }

    private fun continueStackUnwind() {
        val target = stackUnwindTarget ?: return
        if (fragmentSwitcherView.isNavigationInProgress) {
            fragmentSwitcherView.postDelayed(::continueStackUnwind, 32L)
            return
        }
        if (fragmentSwitcherView.currentStackSize <= target) {
            stackUnwindTarget = null
            return
        }
        if (!fragmentSwitcherView.popBackTopFragmentIfExists()) {
            fragmentSwitcherView.postDelayed(::continueStackUnwind, 32L)
        }
    }

    /** Gets the now-playing panel out of the way before a screen is pushed behind it. */
    fun collapseNowPlaying() {
        if (::floatingPanelLayout.isInitialized) floatingPanelLayout.collapse()
    }

    /**
     * Opens every track in the library whose [key] matches [wanted] - the artist behind
     * "Go to Artist", the album behind "Go to Album".
     */
    fun openMatchingTracks(wanted: String, key: (androidx.media3.common.MediaItem) -> String?) {
        lifecycleScope.launch {
            val tracks = reader.songListSnapshot().filter { key(it) == wanted }
            if (tracks.isEmpty()) return@launch
            fragmentSwitcherView.addFragmentToCurrentStack(
                uk.akane.accord.ui.fragments.browse.StationDetailFragment.newInstance(
                    title = wanted,
                    subtitle = null,
                    mediaIds = tracks.map { it.mediaId },
                )
            )
        }
    }

    /**
     * Opens a station built around [seed] - the same idea as the player's Create Station, so
     * a row and the now-playing screen agree on what it means.
     */
    fun openStationFor(seed: androidx.media3.common.MediaItem) {
        collapseNowPlaying()
        lifecycleScope.launch {
            val library = reader.songListSnapshot()
            // AutoplayQueue may consult Last.fm. It is explicitly worker-thread code and used to
            // run here on the main thread, making Create station appear inert while the UI froze.
            val tracks = withContext(Dispatchers.IO) {
                org.akanework.gramophone.logic.data.AutoplayQueue.nextBatch(
                    applicationContext, seed, library, setOf(seed.mediaId)
                )
            }
            if (tracks.isEmpty()) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    R.string.station_no_similar_tracks,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            fragmentSwitcherView.addFragmentToCurrentStack(
                uk.akane.accord.ui.fragments.browse.StationDetailFragment.newInstance(
                    title = getString(
                        R.string.station_from_song,
                        seed.mediaMetadata.title?.toString().orEmpty()
                    ),
                    subtitle = seed.mediaMetadata.artist?.toString(),
                    mediaIds = (listOf(seed) + tracks).map { it.mediaId },
                    kind = uk.akane.accord.ui.fragments.browse.StationDetailFragment.CollectionKind.STATION,
                )
            )
        }
    }

    /** Plays the whole library in a random order, from the screen-level overflow menu. */
    fun shuffleWholeLibrary() {
        lifecycleScope.launch {
            val library = reader.songListSnapshot()
            if (library.isEmpty()) return@launch
            getPlayer()?.apply {
                setMediaItems(library.shuffled(), 0, C.TIME_UNSET)
                prepare()
                play()
            }
        }
    }

    /**
     * Asks for a library sync. The work belongs to the application; only the callback is ours,
     * and it is on this activity's scope so it dies with the screen instead of resurrecting it.
     */
    fun updateLibrary(then: (() -> Unit)? = null) {
        val job = accord.refreshLibrary()
        if (then == null) return
        lifecycleScope.launch {
            job.join()
            then()
        }
    }


    /**
     * Starts the restored queue, if the user asked for that.
     *
     * The queue is not there when the controller connects. The playback service reads it back from
     * disk on its own handler and only then hands it to the player, so a launch that finds an empty
     * timeline has to wait for one rather than conclude there is nothing to play. The wait is
     * bounded: a queue that surfaces a minute later belongs to whatever the user is doing by then,
     * not to the launch.
     */
    private fun playOnLaunchIfWanted(controller: Player) {
        if (!prefs.getBoolean(PLAY_ON_LAUNCH, false)) return
        if (controller.isPlaying || controller.playWhenReady) return
        if (controller.mediaItemCount > 0) {
            controller.prepare()
            controller.play()
            return
        }
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (timeline.isEmpty) return
                runCatching { controller.removeListener(this) }
                if (!controller.isPlaying && !controller.playWhenReady) {
                    controller.prepare()
                    controller.play()
                }
            }
        }
        controller.addListener(listener)
        window.decorView.postDelayed(
            // Released controllers throw rather than ignore this, and the window outlives them.
            { runCatching { controller.removeListener(listener) } },
            PLAY_ON_LAUNCH_TIMEOUT_MS
        )
    }

    /** Applies the two Appearance choices without giving up the edge-to-edge layout. */
    @Suppress("DEPRECATION")
    private fun applySystemBarMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val immersive = prefs.getBoolean(IMMERSIVE_MODE, false)
        val backgroundless = prefs.getBoolean(BACKGROUNDLESS_STATUS_BAR, true)

        // Android 15+ enforces edge-to-edge for this target SDK; setting the colour still keeps
        // Android 12–14 correct. NavigationBar handles the actual inset surface on newer systems.
        window.statusBarColor =
            if (backgroundless) Color.TRANSPARENT else getColor(R.color.windowColor)
        window.isStatusBarContrastEnforced = !backgroundless
        controller.isAppearanceLightStatusBars = !isDarkMode()

        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Applies immersive mode the moment it is switched, rather than at the next launch.
     *
     * Settings is a fragment inside this activity, so the toggle is on screen while the bars are:
     * without this the user flips a switch that visibly does nothing.
     */
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == IMMERSIVE_MODE || key == BACKGROUNDLESS_STATUS_BAR) {
                applySystemBarMode()
            }
        }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onStop()
    }

    /**
     * Re-asserts immersive mode after the bars have been swiped back in.
     *
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE only makes them transient while the window has focus.
     * Coming back from another app, or from a dialog, leaves them showing until asked again.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarMode()
    }

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    inline val accord: Accord
        get() = application as Accord

    inline val reader
        get() = accord.reader

    /**
     * getPlayer:
     *   Returns a media controller.
     */
    fun getPlayer() = controllerViewModel.get()

    val controllerViewModel: MediaControllerViewModel by viewModels()

}
