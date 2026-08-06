package org.akanework.gramophone.ui.fragments


import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import uk.akane.accord.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.handleGeneralMenuItem
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlugins
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import org.akanework.gramophone.logic.data.jellyfin.JellyfinItemResolver
import org.akanework.gramophone.ui.JellyfinLoginActivity
import org.akanework.gramophone.ui.LibraryViewModel
import org.akanework.gramophone.ui.MainActivity
import org.akanework.gramophone.ui.adapters.HomepageCarouselAdapter
import org.akanework.gramophone.ui.components.ItemSnapHelper
import org.akanework.gramophone.ui.home.HomeFeed
import org.akanework.gramophone.ui.home.HomeSection
import org.akanework.gramophone.ui.home.HomeSectionAdapter


class HomepageFragment : BaseFragment(null) {

    private lateinit var appBarLayout: AppBarLayout
    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private lateinit var sectionAdapter: HomeSectionAdapter

    /** The network-backed row, kept separate so a rebuild does not discard it. */
    private var similarSection: HomeSection? = null

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = layoutInflater.inflate(R.layout.fragment_homepage, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val recyclerView = rootView.findViewById<RecyclerView>(R.id.recyclerview_top)
        val nestedScrollView = rootView.findViewById<NestedScrollView>(R.id.nested)
        val sectionsView = rootView.findViewById<RecyclerView>(R.id.home_sections)

        sectionAdapter = HomeSectionAdapter(player = { (requireActivity() as MainActivity).getPlayer() })

        appBarLayout = rootView.findViewById(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        // The header uses the upstream Accord circular actions instead of the stock overflow
        // affordance, so the menu is presented from the ellipsis button rather than by the toolbar.
        // The entries and their behaviour are unchanged - handleGeneralMenuItem is the same handler
        // applyGeneralMenuItem installs on every other screen's toolbar.
        val moreButton = rootView.findViewById<ImageButton>(R.id.nav_action_more)
        moreButton.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menuInflater.inflate(R.menu.home_menu, menu)
                setOnMenuItemClickListener {
                    handleGeneralMenuItem(it, this@HomepageFragment, libraryViewModel)
                }
            }.show()
        }
        rootView.findViewById<ImageButton>(R.id.nav_action_account).setOnClickListener {
            showAccountSheet()
        }

        val syncIndicator = rootView.findViewById<CircularProgressIndicator>(R.id.sync_indicator)
        libraryViewModel.isSyncing.observe(viewLifecycleOwner) { syncing ->
            syncIndicator.visibility = if (syncing == true) View.VISIBLE else View.GONE
        }

        nestedScrollView.enableEdgeToEdgePaddingListener()

        recyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.adapter = HomepageCarouselAdapter(requireActivity() as MainActivity)

        sectionsView.layoutManager = LinearLayoutManager(context)
        sectionsView.adapter = sectionAdapter

        ItemSnapHelper().attachToRecyclerView(recyclerView)

        // Both live inside the NestedScrollView, which does the scrolling; leaving them nested
        // scrollable makes the vertical list fight the parent for the gesture.
        ViewCompat.setNestedScrollingEnabled(recyclerView, false)
        ViewCompat.setNestedScrollingEnabled(sectionsView, false)

        // The feed is derived from the library, so it has to be rebuilt whenever a sync lands -
        // otherwise a first run shows an empty home until the screen is revisited.
        libraryViewModel.mediaItemList.observe(viewLifecycleOwner) {
            rebuildFeed()
            fetchSimilarArtists()
        }

        return rootView
    }

    /**
     * Rebuilds the rows off the main thread.
     *
     * Sorting and grouping thousands of tracks per row is too much work for a frame, and this runs
     * on every sync.
     */
    private fun rebuildFeed() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sections = withContext(Dispatchers.Default) {
                HomeFeed.build(
                    requireContext(),
                    libraryViewModel.mediaItemList.value.orEmpty(),
                    libraryViewModel.artistInputs()
                )
            }
            if (!isAdded) return@launch
            sectionAdapter.submit(withSimilar(sections))
        }
    }

    /**
     * Asks Last.fm for artists similar to the user's most-played one.
     *
     * Kept apart from [rebuildFeed] because it is the one row that needs the network: a slow or
     * unreachable Last.fm must not hold up the rows that come straight from the library.
     */
    private fun fetchSimilarArtists() {
        if (similarSection != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val section = withContext(Dispatchers.IO) {
                HomeFeed.similarArtistSection(requireContext(), libraryViewModel.artistInputs())
            } ?: return@launch
            if (!isAdded) return@launch
            similarSection = section
            rebuildFeed()
        }
    }

    /** Places the Last.fm row third, after the rows drawn from the user's own history. */
    private fun withSimilar(sections: List<HomeSection>): List<HomeSection> {
        val similar = similarSection ?: return sections
        if (sections.isEmpty()) return listOf(similar)
        return sections.toMutableList().apply {
            add(minOf(2, size), similar)
        }
    }

    /**
     * Shows which server this device is signed in to, and offers to sign out.
     *
     * Sending an already-signed-in user to the login screen would be a dead end, so the account
     * button leads here instead. Reading the credential store opens keystore-backed preferences,
     * which is disk work, so the lookup happens off the main thread.
     */
    private fun showAccountSheet() {
        viewLifecycleOwner.lifecycleScope.launch {
            val server = withContext(Dispatchers.IO) {
                JellyfinClientHolder.credentials.let { it.serverUrl to it.userId }
            }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.jellyfin_account_title)
                .setMessage(getString(R.string.jellyfin_account_message, server.first ?: "-"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.jellyfin_sign_out) { _, _ -> signOut() }
                .show()
        }
    }

    private fun signOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                JellyfinClientHolder.credentials.apply {
                    clearSession()
                    publishSessionFlag(requireContext().applicationContext)
                }
                JellyfinClientHolder.invalidate()
                JellyfinPlugins.invalidate()
                JellyfinUserImage.clear()
                // The cached library belongs to the account being signed out of; leaving it would
                // show another user's collection after the next sign in.
                AppDatabase.getInstance(requireContext()).cachedSongDao().deleteAll()
                JellyfinItemResolver.clear()
            }
            if (!isAdded) return@launch
            startActivity(Intent(requireContext(), JellyfinLoginActivity::class.java))
            requireActivity().finish()
        }
    }

}

/** Adapts this app's artist model to the shape [HomeFeed] asks for. */
private fun org.akanework.gramophone.ui.LibraryViewModel.artistInputs() =
    artistItemList.value.orEmpty().map { HomeFeed.ArtistInput(it.title, it.songList) }
