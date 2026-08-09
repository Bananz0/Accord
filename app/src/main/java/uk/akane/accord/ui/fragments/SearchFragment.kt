package uk.akane.accord.ui.fragments

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.Accord
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.SearchResultsAdapter
import uk.akane.accord.ui.adapters.LidarrSearchResultsAdapter
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.adapters.SearchAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.widget.fadOutAnimation
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.accord.ui.components.TrackSwipeActions
import androidx.core.view.updatePadding
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import org.akanework.gramophone.ui.fragments.settings.LidarrSettingsFragment
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.performPressHaptic

class SearchFragment: Fragment() {
    private lateinit var navigationBar: NavigationBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var indicatorTitleTextView: TextView
    private lateinit var indicatorSubtitleTextView: TextView
    private lateinit var detailedSearchContainer: View
    private lateinit var searchBarNav: View
    private lateinit var searchBarDetail: View
    private lateinit var tabContainer: View
    private lateinit var tabIndicator: View
    private lateinit var tabAppleTextView: TextView
    private lateinit var tabLibraryTextView: TextView
    private lateinit var searchInputNav: EditText
    private lateinit var searchInputDetail: EditText

    private lateinit var searchResults: RecyclerView
    private lateinit var searchStatus: View
    private lateinit var searchEmpty: TextView
    private lateinit var searchStatusAction: TextView
    private lateinit var resultsAdapter: SearchResultsAdapter
    private lateinit var lidarrResultsAdapter: LidarrSearchResultsAdapter

    /** The library to search. Kept in step with the reader so results follow a sync. */
    private var library: List<MediaItem> = emptyList()
    private var pendingQuery: Job? = null

    private var indicatorTitleVisible = true
    private var indicatorSubtitleVisible = true
    private var isSearchExpanded = false
    private var isAppleTabSelected = true
    private var enterAnimator: AnimatorSet? = null
    private var exitAnimator: AnimatorSet? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_search, container, false)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        recyclerView = rootView.findViewById(R.id.rv)
        indicatorTitleTextView = rootView.findViewById(R.id.no_track_title)
        indicatorSubtitleTextView = rootView.findViewById(R.id.no_track_subtitle)
        detailedSearchContainer = rootView.findViewById(R.id.detailed_search_container)
        searchBarNav = rootView.findViewById(R.id.search_bar_nav)
        searchBarDetail = rootView.findViewById(R.id.search_bar_detail)
        tabContainer = rootView.findViewById(R.id.tab_container)
        tabIndicator = rootView.findViewById(R.id.tab_apple_music_container)
        tabAppleTextView = rootView.findViewById(R.id.tab_apple_music)
        tabLibraryTextView = rootView.findViewById(R.id.tab_library)
        searchInputNav = searchBarNav.findViewById(R.id.search_input)
        searchInputDetail = searchBarDetail.findViewById(R.id.search_input)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            detailedSearchContainer.setPadding(
                detailedSearchContainer.paddingLeft,
                systemBars.top,
                detailedSearchContainer.paddingRight,
                detailedSearchContainer.paddingBottom
            )
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                systemBars.bottom + resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
            )
            insets
        }

        recyclerView.layoutManager = GridLayoutManager(context, 2)
        recyclerView.adapter = SearchAdapter(requireContext(), this) {
            indicatorSubtitleTextView.fadOutAnimation(interpolator = AnimationUtils.easingStandardInterpolator)
            indicatorTitleTextView.fadOutAnimation(interpolator = AnimationUtils.easingStandardInterpolator)
        }
        navigationBar.attach(recyclerView)

        tabAppleTextView.setOnClickListener { selectTab(true) }
        tabLibraryTextView.setOnClickListener { selectTab(false) }
        tabContainer.doOnLayout { updateTabIndicator(false) }
        updateTabSelection()

        detailedSearchContainer.visibility = View.GONE
        resetTabRevealState()

        searchResults = detailedSearchContainer.findViewById(R.id.search_results)
        searchStatus = detailedSearchContainer.findViewById(R.id.search_status)
        searchEmpty = detailedSearchContainer.findViewById(R.id.search_empty)
        searchStatusAction = detailedSearchContainer.findViewById(R.id.search_status_action)
        resultsAdapter = SearchResultsAdapter { (activity as? MainActivity)?.getPlayer() }
        lidarrResultsAdapter = LidarrSearchResultsAdapter(::requestLidarrAlbum)
        searchResults.layoutManager = LinearLayoutManager(requireContext())
        searchResults.adapter = resultsAdapter
        // This list is not the one the navigation bar is attached to, so nothing was leaving
        // room for the mini player: the last result sat behind it and sprang back when
        // dragged into view.
        searchResults.clipToPadding = false
        // Applied straight away rather than on layout: this list lives inside a container that
        // starts hidden, so a layout-time hook either never ran or ran before the mini player had
        // a height - which is why the last result stayed underneath it.
        searchResults.updatePadding(bottom = (requireActivity() as MainActivity).bottomHeight)
        ViewCompat.setOnApplyWindowInsetsListener(searchResults) { v, insets ->
            // Recomputed when the insets land, since the mini player sits above the gesture bar.
            v.updatePadding(bottom = (requireActivity() as MainActivity).bottomHeight)
            insets
        }
        // Same gesture as every other list: right queues it, left downloads it.
        TrackSwipeActions.attach(
            recyclerView = searchResults,
            activity = requireActivity() as MainActivity,
            trackAt = { index ->
                if (isAppleTabSelected) resultsAdapter.itemAt(index) else null
            },
        )

        observeLibrary()
        searchInputDetail.doAfterTextChanged { runQuery(it?.toString().orEmpty()) }

        searchBarNav.setOnClickListener { enterSearchMode() }
        searchInputNav.setOnClickListener { enterSearchMode() }
        searchInputNav.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) enterSearchMode()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSearchExpanded) {
                        exitSearchMode()
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }

    /** A second tap on the already-selected Search destination goes straight to typing. */
    fun focusSearch() {
        if (!isAdded || view == null) return
        if (isSearchExpanded) focusDetailedSearch() else enterSearchMode()
    }

    private fun observeLibrary() {
        val reader = (requireActivity().application as Accord).reader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.songListFlow.collectLatest { songs ->
                    library = songs
                    // A sync finishing mid-query should widen the results, not leave them stale.
                    if (isAppleTabSelected) {
                        runQuery(searchInputDetail.text?.toString().orEmpty())
                    }
                }
            }
        }
    }

    /**
     * Matches title, artist and album, all normalised, so "sabrina" finds "Sabrina Carpenter" and
     * punctuation in a tag does not hide a track.
     *
     * Debounced and run off the main thread: this scans the whole library on every keystroke, and
     * that library is thousands of items.
     */
    private fun runQuery(rawQuery: String) {
        pendingQuery?.cancel()
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) {
            resultsAdapter.submit(emptyList())
            lidarrResultsAdapter.submit(emptyList())
            // An empty box is exactly when a broken Lidarr is worth saying out loud - waiting for a
            // query to report it means the user types something first and blames the search.
            if (isAppleTabSelected) hideStatus() else showLidarrSetupStatusIfNeeded()
            return
        }
        pendingQuery = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            if (isAppleTabSelected) {
                val matches = withContext(Dispatchers.Default) {
                    val needle = query.normaliseForSearch()
                    library.asSequence()
                        .filter { item ->
                            val metadata = item.mediaMetadata
                            metadata.title?.toString()?.normaliseForSearch()?.contains(needle) == true ||
                                metadata.artist?.toString()?.normaliseForSearch()
                                    ?.contains(needle) == true ||
                                metadata.albumTitle?.toString()?.normaliseForSearch()
                                    ?.contains(needle) == true
                        }
                        .take(SEARCH_RESULT_LIMIT)
                        .toList()
                }
                resultsAdapter.submit(matches)
                if (matches.isEmpty()) showStatus(getString(R.string.search_no_results))
                else hideStatus()
            } else {
                searchLidarr(query)
            }
        }
    }

    /**
     * Shows the background message, optionally with a tappable line under it.
     *
     * Everything that can leave this screen with nothing to show goes through here so the list and
     * the message can never both be visible, and so a dead end always offers the way out of it.
     */
    private fun showStatus(
        message: CharSequence,
        actionText: CharSequence? = null,
        action: (() -> Unit)? = null,
    ) {
        searchEmpty.text = message
        if (actionText != null && action != null) {
            searchStatusAction.text = actionText
            searchStatusAction.visibility = View.VISIBLE
            searchStatusAction.setOnClickListener {
                it.performPressHaptic()
                action()
            }
        } else {
            searchStatusAction.visibility = View.GONE
            searchStatusAction.setOnClickListener(null)
        }
        searchStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        searchStatus.visibility = View.GONE
        searchStatusAction.setOnClickListener(null)
    }

    /**
     * Reports an unusable Lidarr on the empty Lidarr tab, and says which kind of unusable it is.
     *
     * Missing credentials and missing profiles need different actions - the first cannot be guessed
     * and the second is discovered from the server - so they are not collapsed into one message.
     *
     * @return true if a message was shown.
     */
    private fun showLidarrSetupStatusIfNeeded(): Boolean {
        val store = LidarrCredentialStore(requireContext())
        return when {
            store.serverUrl.isNullOrBlank() || store.apiKey.isNullOrBlank() -> {
                showStatus(
                    getString(R.string.search_lidarr_not_configured),
                    getString(R.string.search_lidarr_set_up),
                ) { openLidarrSettings() }
                true
            }
            !store.isConfigured() -> {
                showStatus(
                    getString(R.string.search_lidarr_incomplete),
                    getString(R.string.search_lidarr_open_settings),
                ) { openLidarrSettings() }
                true
            }
            else -> {
                hideStatus()
                false
            }
        }
    }

    private fun openLidarrSettings() {
        (activity as? MainActivity)?.fragmentSwitcherView
            ?.addFragmentToCurrentStack(LidarrSettingsFragment())
    }

    private suspend fun searchLidarr(query: String) {
        lidarrResultsAdapter.submit(emptyList())
        if (showLidarrSetupStatusIfNeeded()) return

        val store = LidarrCredentialStore(requireContext())
        val result = withContext(Dispatchers.IO) {
            runCatching { LidarrClient(store).searchAlbums(query) }
        }
        result.onSuccess { albums ->
            lidarrResultsAdapter.submit(albums)
            if (albums.isEmpty()) showStatus(getString(R.string.requests_no_results))
            else hideStatus()
        }.onFailure { error ->
            lidarrResultsAdapter.submit(emptyList())
            showStatus(
                getString(
                    R.string.search_lidarr_unreachable,
                    error.message ?: getString(R.string.requests_failed),
                ),
                getString(R.string.search_lidarr_retry),
            ) { runQuery(searchInputDetail.text?.toString().orEmpty()) }
        }
    }

    private fun requestLidarrAlbum(album: LidarrClient.AlbumResult) {
        if (album.alreadyAdded) {
            Toast.makeText(requireContext(), R.string.requests_already_added, Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (!LidarrCredentialStore(requireContext()).isConfigured()) {
            LidarrSetupPrompt.ensureConfigured(requireContext(), viewLifecycleOwner) {
                requestLidarrAlbum(album)
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    LidarrClient(LidarrCredentialStore(requireContext())).addAlbum(album)
                }
            }
            Toast.makeText(
                requireContext(),
                added.fold(
                    onSuccess = {
                        if (it) getString(R.string.requests_added, album.title)
                        else getString(R.string.requests_failed)
                    },
                    onFailure = { it.message ?: getString(R.string.requests_failed) },
                ),
                Toast.LENGTH_LONG,
            ).show()
            if (added.getOrDefault(false)) runQuery(searchInputDetail.text?.toString().orEmpty())
        }
    }

    private fun String.normaliseForSearch(): String =
        lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }

    private fun enterSearchMode() {
        if (isSearchExpanded) return
        isSearchExpanded = true
        cancelSearchAnimations()

        indicatorTitleVisible = indicatorTitleTextView.visibility == View.VISIBLE && indicatorTitleTextView.alpha > 0f
        indicatorSubtitleVisible = indicatorSubtitleTextView.visibility == View.VISIBLE && indicatorSubtitleTextView.alpha > 0f

        detailedSearchContainer.visibility = View.VISIBLE
        detailedSearchContainer.alpha = 0f
        detailedSearchContainer.translationY = 24.dp.px
        searchBarDetail.alpha = 1f
        resetTabRevealState()

        if (!detailedSearchContainer.isLaidOut) {
            detailedSearchContainer.doOnLayout { startEnterAnimation() }
        } else {
            startEnterAnimation()
        }
    }

    private fun startEnterAnimation() {
        val duration = (AnimationUtils.MID_DURATION * 1.15f).toLong()
        val fadeDuration = duration
        val pageOffsetY = -24.dp.px
        val detailOffsetY = 24.dp.px
        val mainViews = getMainPageViews()
        searchInputDetail.setText(searchInputNav.text)
        searchInputDetail.setSelection(searchInputDetail.text.length)

        mainViews.forEach { view ->
            view.visibility = View.VISIBLE
            view.alpha = 1f
            view.translationY = 0f
        }
        detailedSearchContainer.alpha = 0f
        detailedSearchContainer.translationY = detailOffsetY

        val moveOut = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, pageOffsetY).apply {
                this.duration = duration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val fadeOut = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f).apply {
                this.duration = fadeDuration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val moveInDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.TRANSLATION_Y, 0f).apply {
            this.duration = duration
            interpolator = AnimationUtils.easingStandardInterpolator
        }
        val fadeInDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.ALPHA, 1f).apply {
            this.duration = fadeDuration
            interpolator = AnimationUtils.easingStandardInterpolator
        }

        val enterSet = AnimatorSet().apply {
            playTogether(*(moveOut + fadeOut + listOf(moveInDetail, fadeInDetail)).toTypedArray())
            doOnEnd {
                mainViews.forEach { it.visibility = View.INVISIBLE }
                focusDetailedSearch()
            }
        }

        enterAnimator = enterSet
        enterSet.start()
    }

    private fun focusDetailedSearch() {
        searchInputNav.clearFocus()
        searchInputDetail.requestFocus()
        showKeyboard(searchInputDetail)
    }

    private fun exitSearchMode() {
        if (!isSearchExpanded) return
        isSearchExpanded = false
        cancelSearchAnimations()

        hideKeyboard(searchInputDetail)
        searchInputDetail.clearFocus()
        searchInputNav.setText(searchInputDetail.text)
        searchInputNav.setSelection(searchInputNav.text.length)

        if (!detailedSearchContainer.isLaidOut) {
            detailedSearchContainer.doOnLayout { startExitAnimation() }
        } else {
            startExitAnimation()
        }
    }

    private fun startExitAnimation() {
        val duration = (AnimationUtils.MID_DURATION * 1.15f).toLong()
        val fadeDuration = duration
        val pageOffsetY = -24.dp.px
        val detailOffsetY = 24.dp.px
        val mainViews = getMainPageViews()

        mainViews.forEach { view ->
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = pageOffsetY
        }
        detailedSearchContainer.alpha = 1f
        detailedSearchContainer.translationY = 0f

        val moveIn = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f).apply {
                this.duration = duration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val fadeIn = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.ALPHA, 1f).apply {
                this.duration = fadeDuration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val moveOutDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.TRANSLATION_Y, detailOffsetY).apply {
            this.duration = duration
            interpolator = AnimationUtils.easingStandardInterpolator
        }
        val fadeOutDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.ALPHA, 0f).apply {
            this.duration = fadeDuration
            interpolator = AnimationUtils.easingStandardInterpolator
        }

        val exitSet = AnimatorSet().apply {
            playTogether(*(moveIn + fadeIn + listOf(moveOutDetail, fadeOutDetail)).toTypedArray())
            doOnEnd {
                detailedSearchContainer.visibility = View.GONE
                resetTabRevealState()
            }
        }

        exitAnimator = exitSet
        exitSet.start()
    }

    private fun cancelSearchAnimations() {
        enterAnimator?.cancel()
        exitAnimator?.cancel()
        enterAnimator = null
        exitAnimator = null
    }

    private fun resetTabRevealState() {
        tabContainer.alpha = 1f
        tabContainer.translationY = 0f
        tabContainer.scaleX = 1f
        tabContainer.scaleY = 1f
    }

    private fun getMainPageViews(): List<View> {
        val views = mutableListOf<View>(navigationBar, recyclerView)
        if (indicatorTitleVisible) {
            indicatorTitleTextView.visibility = View.VISIBLE
            views.add(indicatorTitleTextView)
        } else {
            indicatorTitleTextView.visibility = View.GONE
        }
        if (indicatorSubtitleVisible) {
            indicatorSubtitleTextView.visibility = View.VISIBLE
            views.add(indicatorSubtitleTextView)
        } else {
            indicatorSubtitleTextView.visibility = View.GONE
        }
        return views
    }

    private fun selectTab(isAppleTab: Boolean) {
        if (isAppleTabSelected == isAppleTab) return
        isAppleTabSelected = isAppleTab
        tabContainer.performPressHaptic()
        updateTabSelection()
        updateTabIndicator(true)
        searchResults.adapter = if (isAppleTabSelected) resultsAdapter else lidarrResultsAdapter
        searchResults.scrollToPosition(0)
        runQuery(searchInputDetail.text?.toString().orEmpty())
    }

    private fun updateTabSelection() {
        val selectedColor = requireContext().getColor(R.color.onSurfaceColor)
        val inactiveColor = requireContext().getColor(R.color.onSurfaceColorInactive)
        tabAppleTextView.setTextColor(if (isAppleTabSelected) selectedColor else inactiveColor)
        tabLibraryTextView.setTextColor(if (isAppleTabSelected) inactiveColor else selectedColor)
    }

    private fun updateTabIndicator(animate: Boolean) {
        val container = tabIndicator.parent as View
        val containerWidth = container.width
        if (containerWidth == 0) return
        val marginParams = tabIndicator.layoutParams as ViewGroup.MarginLayoutParams
        val horizontalMargin = marginParams.leftMargin + marginParams.rightMargin
        val segmentWidth = (containerWidth - horizontalMargin) / 2f
        val targetWidth = segmentWidth.toInt()
        if (marginParams.width != targetWidth) {
            marginParams.width = targetWidth
            tabIndicator.layoutParams = marginParams
        }
        val targetTranslation = if (isAppleTabSelected) {
            0f
        } else {
            (containerWidth - targetWidth - horizontalMargin).toFloat()
        }
        if (animate) {
            tabIndicator.animate()
                .translationX(targetTranslation)
                .setDuration(AnimationUtils.MID_DURATION)
                .setInterpolator(AnimationUtils.easingStandardInterpolator)
                .start()
        } else {
            tabIndicator.translationX = targetTranslation
        }
    }

    private fun showKeyboard(target: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(target: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private companion object {
        /** Long enough that a fast typist scans the library once, short enough to feel immediate. */
        const val SEARCH_DEBOUNCE_MS = 180L

        /** The list is scrolled, not read whole; past this it is cheaper to refine the query. */
        const val SEARCH_RESULT_LIMIT = 200
    }
}
