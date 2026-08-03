/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import coil3.imageLoader
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgeProperly
import org.akanework.gramophone.logic.postAtFrontOfQueueAsync
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import org.akanework.gramophone.logic.data.jellyfin.JellyfinIdMap
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.utils.DatabaseUtils
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.akanework.gramophone.logic.utils.RecommendationFactory
import org.akanework.gramophone.ui.components.PlayerBottomSheet
import org.akanework.gramophone.ui.fragments.BaseFragment

// @author v3ndable: Personal Change immersive Mode
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.akanework.gramophone.logic.applyImmersiveMode
import androidx.preference.PreferenceManager
import android.content.SharedPreferences

// ---

/**
 * MainActivity:
 *   Core of gramophone, one and the only activity
 * used across the application.
 *
 * @author AkaneTan, nift4
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_READ_MEDIA_AUDIO = 100
        const val PLAYBACK_AUTO_START_FOR_FGS = "AutoStartFgs"
    }

    // Import our viewModels.
    val libraryViewModel: LibraryViewModel by viewModels()
    val startingActivity = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    private val handler = Handler(Looper.getMainLooper())
    private val reportFullyDrawnRunnable = Runnable { if (!ready) reportFullyDrawn() }
    private var ready = false
    private var autoPlay = false
    lateinit var playerBottomSheet: PlayerBottomSheet
        private set
    private lateinit var intentSender: ActivityResultLauncher<IntentSenderRequest>
    lateinit var bottomNavigationView: BottomNavigationView
    private var intentSenderAction: (() -> Boolean)? = null

    private lateinit var container: FragmentContainerView

    /**
     * updateLibrary:
     *   Syncs the library from the Jellyfin server into [libraryViewModel].
     */
    fun updateLibrary(then: (() -> Unit)? = null) {
        // If library load takes more than 3s, exit splash to avoid ANR
        if (!ready) handler.postDelayed(reportFullyDrawnRunnable, 3000)
        CoroutineScope(Dispatchers.IO).launch {
            val api = JellyfinClientHolder.api()
            if (api == null) {
                // Signed out; the login screen is responsible for getting us back here.
                withContext(Dispatchers.Main) {
                    if (!ready) reportFullyDrawn()
                    then?.let { it() }
                }
                return@launch
            }
            val db = AppDatabase.getInstance(this@MainActivity)
            val cacheDao = db.cachedSongDao()
            val idMap = JellyfinIdMap(db.jellyfinIdDao())
            val loader = JellyfinLibraryLoader(api, idMap)

            // Show whatever was cached first. A full sync of a large library takes the better part
            // of a minute, and there is no reason to stare at an empty screen while it runs.
            val cached = try {
                loader.loadFromCache(cacheDao)
            } catch (e: Exception) {
                Log.e("MainActivity", "Reading library cache failed", e)
                null
            }
            if (cached != null) {
                val cachedFavourites = loader.favouriteLocalIds.toSet()
                withContext(Dispatchers.Main) {
                    publishLibrary(cached)
                    if (!ready) reportFullyDrawn()
                    then?.let { it() }
                }
                DatabaseUtils.getPrivatePlaylist(libraryViewModel, this@MainActivity)
                DatabaseUtils.syncFavouritesFromServer(
                    cachedFavourites, libraryViewModel, this@MainActivity
                )
            }

            withContext(Dispatchers.Main) { showSyncBar() }
            var favouriteIds: Set<Long> = emptySet()
            val store = try {
                loader.load(cacheDao) { loaded, total ->
                    // Fired once per 500-item page, so posting straight to the main thread is
                    // cheap enough without extra throttling.
                    handler.post { updateSyncBar(loaded, total) }
                }.also { favouriteIds = loader.favouriteLocalIds.toSet() }
            } catch (e: Exception) {
                Log.e("MainActivity", "Jellyfin library sync failed", e)
                null
            }
            if (store != null) {
                // Favourites are owned by the server, so adopt its view before the UI reads them.
                DatabaseUtils.getPrivatePlaylist(libraryViewModel, this@MainActivity)
                DatabaseUtils.syncFavouritesFromServer(
                    favouriteIds, libraryViewModel, this@MainActivity
                )
            }
            // With a cache already on screen, a failed refresh is not worth a toast - the user has
            // a working library and the next launch will try again.
            val hadCache = cached != null
            withContext(Dispatchers.Main) {
                if (store != null) {
                    publishLibrary(store)
                } else if (!hadCache) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.jellyfin_error_unreachable),
                        Toast.LENGTH_LONG
                    ).show()
                }
                hideSyncBar()
                if (!ready) reportFullyDrawn()
                then?.let { it() }
            }
        }
    }

    /** Pushes a built library into the view model. Main thread only. */
    private fun publishLibrary(store: MediaStoreUtils.LibraryStoreClass) {
        libraryViewModel.mediaItemList.value = store.songList
        libraryViewModel.albumItemList.value = store.albumList
        libraryViewModel.artistItemList.value = store.artistList
        libraryViewModel.albumArtistItemList.value = store.albumArtistList
        libraryViewModel.genreItemList.value = store.genreList
        libraryViewModel.dateItemList.value = store.dateList
        libraryViewModel.playlistList.value = store.playlistList
        libraryViewModel.folderStructure.value = store.folderStructure
        libraryViewModel.shallowFolderStructure.value = store.shallowFolder
        libraryViewModel.allFolderSet.value = store.folders
        if (libraryViewModel.recommendList.value == null) {
            libraryViewModel.recommendList.value = RecommendationFactory(
                context = this,
                libraryViewModel = libraryViewModel
            ).fetchRecommendList()
        }
    }

    /**
     * Progress for the library sync.
     *
     * The splash screen gives up after 3s but a full sync of a large library takes far longer, so
     * without this the user stares at an empty library with no indication anything is happening.
     */
    private var syncBar: Snackbar? = null

    private fun showSyncBar() {
        if (syncBar != null) return
        syncBar = Snackbar.make(
            container,
            getString(R.string.jellyfin_syncing_library),
            Snackbar.LENGTH_INDEFINITE
        ).apply {
            anchorView = bottomNavigationView
            show()
        }
    }

    private fun updateSyncBar(loaded: Int, total: Int) {
        syncBar?.setText(
            if (total > 0) getString(R.string.jellyfin_syncing_progress, loaded, total)
            else getString(R.string.jellyfin_syncing_library)
        )
    }

    private fun hideSyncBar() {
        syncBar?.dismiss()
        syncBar = null
    }

    /**
     * Sends the user to sign in when there is no stored session, otherwise runs [onStaying].
     *
     * This must decide synchronously. Deferring the decision to a coroutine and starting the login
     * activity afterwards counts as a background activity launch, which StrictMode's penaltyDeath
     * kills the process for on a cold start. The flag it reads is a plain boolean mirrored out of
     * the encrypted store, so no keystore work happens here.
     */
    private fun routeToLoginIfSignedOut(onStaying: () -> Unit) {
        if (JellyfinCredentialStore.hasStoredSession(this)) {
            onStaying()
        } else {
            startActivity(Intent(this, JellyfinLoginActivity::class.java))
            finish()
        }
    }

    /**
     * onCreate - core of MainActivity.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !ready }
        enableEdgeToEdgeProperly()
        super.onCreate(savedInstanceState)

        // @author v3ndable: Personal Change immersive Mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // ---

        autoPlay = intent?.extras?.getBoolean(PLAYBACK_AUTO_START_FOR_FGS, false) == true
        intentSender =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == RESULT_OK) {
                    if (intentSenderAction != null) {
                        intentSenderAction!!()
                    } else {
                        Toast.makeText(
                            this, getString(
                                R.string.delete_in_progress
                            ), Toast.LENGTH_LONG
                        ).show()
                    }
                }
                intentSenderAction = null
            }

        supportFragmentManager.registerFragmentLifecycleCallbacks(object :
            FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                super.onFragmentStarted(fm, f)
                if (fm.fragments.lastOrNull() != f) return
                // this won't be called in case we show()/hide() so
                // we handle that case in BaseFragment
                if (f is BaseFragment && f.wantsPlayer != null) {
                    playerBottomSheet.visible = f.wantsPlayer
                }
            }
        }, false)

        // Set content Views.
        setContentView(R.layout.activity_main)
        window.decorView.setBackgroundColor(
            ContextCompat.getColor(
                this,
                R.color.contrast_colorBackground
            )
        )
        playerBottomSheet = findViewById(R.id.player_layout)
        bottomNavigationView = findViewById(R.id.bottom_nav)
        container = findViewById(R.id.container)

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val immersiveEnabled = prefs.getBoolean("immersive_mode", true)
        applyImmersiveMode(immersiveEnabled)

        // @author v3ndable: Pushes navbar to bottom to prevent overlapping with player
        ViewCompat.setOnApplyWindowInsetsListener(bottomNavigationView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(playerBottomSheet) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
        // ---

        // Modifies FragmentContainerView's insets to account for bottom sheet size.
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            playerBottomSheet.generateBottomSheetInsets(insets)
        }

        if (savedInstanceState != null) {
            val translationY = savedInstanceState.getFloat("bottomNavigationTranslationY")
            bottomNavigationView.translationY = translationY
        }

        // The library lives on a Jellyfin server now, so there are no storage permissions to ask
        // for - the gate is whether we have a session.
        routeToLoginIfSignedOut {
            if (libraryViewModel.mediaItemList.value == null) {
                updateLibrary {
                    playerBottomSheet.fullPlayer.updateFavStatus()
                }
            } else reportFullyDrawn() // <-- when recreating activity due to rotation
        }
    }

    // https://twitter.com/Piwai/status/1529510076196630528
    override fun reportFullyDrawn() {
        handler.removeCallbacks(reportFullyDrawnRunnable)
        if (ready) throw IllegalStateException("ready is already true")
        ready = true
        Choreographer.getInstance().postFrameCallback {
            handler.postAtFrontOfQueueAsync {
                super.reportFullyDrawn()
            }
        }
    }

    fun retractNavigationViewWithProgress(progressHeight: Float) {
        bottomNavigationView.translationY = progressHeight
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("bottomNavigationTranslationY", bottomNavigationView.translationY)
    }

    /**
     * startFragment:
     *   Used by child fragments / drawer to start
     * a fragment inside MainActivity's fragment
     * scope.
     *
     * @param frag: Target fragment.
     */
    fun startFragment(frag: Fragment, args: (Bundle.() -> Unit)? = null) {
        supportFragmentManager
            .beginTransaction()
            .addToBackStack(System.currentTimeMillis().toString())
            .hide(supportFragmentManager.fragments.let { it[it.size - 1] })
            .add(R.id.container, frag.apply { args?.let { arguments = Bundle().apply(it) } })
            .commit()
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        // https://github.com/androidx/media/issues/805
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && (getPlayer()?.playWhenReady != true || getPlayer()?.mediaItemCount == 0)
        ) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        }
        super.onDestroy()
        // we don't ever want covers to be the cause of service being killed by too high mem usage
        // (this is placed after super.onDestroy() to make sure all ImageViews are dead)
        imageLoader.memoryCache?.clear()
    }

    fun scaleContainer(factor: Float) {
        container.scaleX = 1f - factor * 0.10f
        container.scaleY = 1f - factor * 0.10f
    }

    /**
     * getPlayer:
     *   Returns a media controller.
     */
    fun getPlayer() = playerBottomSheet.getPlayer()

    fun consumeAutoPlay(): Boolean {
        return autoPlay.also { autoPlay = false }
    }

    /**
     * Activate immersive Mode immediately
     */
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "immersive_mode") {
                val enabled = PreferenceManager
                    .getDefaultSharedPreferences(this)
                    .getBoolean(key, true)

                applyImmersiveMode(enabled)
            }
        }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(listener)
        super.onStop()
    }
}
