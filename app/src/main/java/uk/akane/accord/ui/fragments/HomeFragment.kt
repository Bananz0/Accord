package uk.akane.accord.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.ui.home.HomeFeed
import org.akanework.gramophone.ui.home.HomeSection
import org.akanework.gramophone.ui.home.HomeSectionAdapter
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar

class HomeFragment: Fragment() {
    private lateinit var navigationBar: NavigationBar
    private lateinit var subtitle: TextView
    private lateinit var sectionAdapter: HomeSectionAdapter

    /** Fetched once per view, and folded back into the feed on every later rebuild. */
    private var similarSection: HomeSection? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_home, container, false)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        subtitle = rootView.findViewById(R.id.subtitle)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        sectionAdapter = HomeSectionAdapter { (activity as? MainActivity)?.getPlayer() }
        rootView.findViewById<RecyclerView>(R.id.home_sections).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sectionAdapter
        }

        observeLibrarySync()
        observeLibrary()
        return rootView
    }

    /**
     * A Jellyfin sync takes the better part of a minute on a large library and, until now, gave no
     * sign it was happening - the library simply sat there looking empty. The home subtitle doubles
     * as that indicator, and goes back to its normal text when the sync finishes.
     */
    private fun observeLibrarySync() {
        val jellyfin = (requireActivity().application as Accord).jellyfinReader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                jellyfin.syncProgress.collect { progress ->
                    subtitle.text = when {
                        progress == null -> getString(R.string.recommendations)
                        progress.second > 0 ->
                            getString(R.string.sync_progress, progress.first, progress.second)
                        else -> getString(R.string.sync_in_progress)
                    }
                }
            }
        }
    }

    /**
     * Rebuilds the rows whenever the library changes, which on a Jellyfin sync means twice - once
     * from cache, once from the server.
     */
    private fun observeLibrary() {
        val reader = (requireActivity().application as Accord).reader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(reader.songListFlow, reader.artistListFlow) { songs, artists ->
                    songs to artists.map { HomeFeed.ArtistInput(it.title, it.songList) }
                }.collect { (songs, artists) ->
                    val sections = withContext(Dispatchers.Default) {
                        HomeFeed.build(requireContext(), songs, artists)
                    }
                    if (!isAdded) return@collect
                    sectionAdapter.submit(withSimilar(sections))
                    fetchSimilarArtists(artists)
                }
            }
        }
    }

    /**
     * The one row that needs the network. Kept off the main rebuild so a slow or unreachable
     * Last.fm cannot hold up the rows that come straight from the library.
     */
    private fun fetchSimilarArtists(artists: List<HomeFeed.ArtistInput>) {
        if (similarSection != null || artists.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val section = withContext(Dispatchers.IO) {
                HomeFeed.similarArtistSection(requireContext(), artists)
            } ?: return@launch
            if (!isAdded) return@launch
            similarSection = section
            sectionAdapter.submit(withSimilar(sectionAdapter.currentSections()))
        }
    }

    private fun withSimilar(sections: List<HomeSection>): List<HomeSection> {
        val similar = similarSection ?: return sections
        if (sections.any { it.id == similar.id }) return sections
        // Second from the top: recognisable enough to be worth surfacing, but not ahead of what the
        // user was actually listening to.
        return sections.toMutableList().apply { add(minOf(1, size), similar) }
    }
}
