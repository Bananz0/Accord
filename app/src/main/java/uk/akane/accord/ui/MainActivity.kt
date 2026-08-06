package uk.akane.accord.ui

import android.content.res.Resources
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
import androidx.core.view.updateLayoutParams
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
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.logic.enableEdgeToEdgeProperly
import uk.akane.accord.logic.isDarkMode
import uk.akane.accord.logic.isEssentialPermissionGranted
import uk.akane.accord.logic.utils.CalculationUtils.lerp
import uk.akane.accord.logic.utils.UiUtils
import uk.akane.accord.setupwizard.fragments.SetupWizardFragment
import uk.akane.accord.ui.components.player.FloatingPanelLayout
import uk.akane.accord.ui.fragments.BrowseFragment
import uk.akane.accord.ui.fragments.HomeFragment
import uk.akane.accord.ui.fragments.LibraryFragment
import uk.akane.accord.ui.fragments.SearchFragment
import uk.akane.accord.ui.viewmodels.MediaControllerViewModel
import uk.akane.cupertino.navigation.FragmentSwitcherView
import uk.akane.cupertino.utils.AnimationUtils
import androidx.lifecycle.lifecycleScope
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import kotlinx.coroutines.flow.first
import uk.akane.accord.ui.fragments.SettingsFragment

class MainActivity : AppCompatActivity() {
    companion object {
        const val DESIRED_BOTTOM_SHEET_OPEN_RATIO = 0.9f
        const val DESIRED_BOTTOM_SHEET_DISPLAY_RATIO = 0.85F

        const val PLAYBACK_AUTO_START_FOR_FGS = "AutoStartFgs"
        const val PLAYBACK_AUTO_PLAY_ID = "AutoStartId"
        const val PLAYBACK_AUTO_PLAY_POSITION = "AutoStartPos"
    }

    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var floatingPanelLayout: FloatingPanelLayout
    private lateinit var shrinkContainerLayout: MaterialCardView
    private lateinit var screenCorners: UiUtils.ScreenCorners
    lateinit var fragmentSwitcherView: FragmentSwitcherView

    private var bottomInset: Int = 0
    private var bottomDefaultRadius: Int = 0

    private var bottomNavigationPanelColor: Int = 0

    private var isWindowColorSet: Boolean = false

    private var ready: Boolean = false
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
        super.onCreate(savedInstanceState)

        // So the hardware keys act on music rather than on the ringer when nothing is playing yet.
        volumeControlStream = AudioManager.STREAM_MUSIC

        UiUtils.init(this)

        bottomDefaultRadius = resources.getDimensionPixelSize(R.dimen.bottom_panel_radius)
        bottomNavigationPanelColor = getColor(R.color.bottomNavigationPanelColor)

        installSplashScreen().setKeepOnScreenCondition { !ready }
        enableEdgeToEdgeProperly()

        lifecycle.addObserver(controllerViewModel)
        controllerViewModel.addControllerCallback(lifecycle) { controller, controllerLifecycle ->
            ready = true
        }

        // The navigation bar on every screen draws the signed-in user's Jellyfin picture, so it is
        // fetched once here rather than by each bar. Off the main thread: it opens the credential
        // store and asks the server.
        lifecycleScope.launch(Dispatchers.IO) { JellyfinUserImage.refresh() }

        setContentView(R.layout.activity_main)

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

        fragmentSwitcherView.setup(
            this,
            listOf(
                HomeFragment(),
                BrowseFragment(),
                LibraryFragment(),
                SearchFragment()
            ),
            listOf(
                "Home",
                "Browse",
                "Library",
                "Search"
            )
        )

        bottomNavigationView.setOnItemSelectedListener { item ->
            fragmentSwitcherView.switchBaseFragment(
                when (item.itemId) {
                    R.id.home -> 0
                    R.id.browse -> 1
                    R.id.library -> 2
                    R.id.search -> 3
                    else -> throw IllegalArgumentException("Invalid itemId!")
                }
            )
            true
        }

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

        container.post {
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
        fragmentSwitcherView.addFragmentToCurrentStack(SettingsFragment())
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
            val tracks = reader.songListFlow.first().filter { key(it) == wanted }
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
            val library = reader.songListFlow.first()
            val tracks = org.akanework.gramophone.logic.data.AutoplayQueue.nextBatch(
                applicationContext, seed, library, setOf(seed.mediaId)
            )
            if (tracks.isEmpty()) return@launch
            fragmentSwitcherView.addFragmentToCurrentStack(
                uk.akane.accord.ui.fragments.browse.StationDetailFragment.newInstance(
                    title = getString(
                        R.string.station_from_song,
                        seed.mediaMetadata.title?.toString().orEmpty()
                    ),
                    subtitle = seed.mediaMetadata.artist?.toString(),
                    mediaIds = (listOf(seed) + tracks).map { it.mediaId },
                )
            )
        }
    }

    /** Plays the whole library in a random order, from the screen-level overflow menu. */
    fun shuffleWholeLibrary() {
        lifecycleScope.launch {
            val library = reader.songListFlow.first()
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
