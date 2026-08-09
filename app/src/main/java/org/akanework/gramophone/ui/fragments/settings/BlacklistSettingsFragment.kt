package org.akanework.gramophone.ui.fragments.settings


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.ui.adapters.BlacklistAdapter
import org.akanework.gramophone.ui.fragments.BaseFragment
import uk.akane.accord.Accord
import uk.akane.accord.R

/**
 * Hides artists and songs from the library.
 *
 * The page used to list local folders, which is meaningless against a Jellyfin server - it showed a
 * short list of device directories nobody recognised and looked broken. Artists and songs are what
 * there is actually a reason to hide: the comedy album that keeps surfacing in a shuffle, or the
 * one artist on a shared server nobody in the house wants to hear.
 */
class BlacklistSettingsFragment : BaseFragment() {

    private lateinit var adapter: BlacklistAdapter

    /** The whole list, rebuilt when the library changes and re-filtered as the user types. */
    private var allRows: List<BlacklistAdapter.Row> = emptyList()
    private var filter: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_blacklist_settings, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)

        rootView.findViewById<AppBarLayout>(R.id.appbarlayout).enableEdgeToEdgePaddingListener()

        topAppBar.setNavigationOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        adapter = BlacklistAdapter((requireActivity().application as Accord).blacklist)
        rootView.findViewById<RecyclerView>(R.id.recyclerview).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@BlacklistSettingsFragment.adapter
        }

        rootView.findViewById<EditText>(R.id.blacklist_filter).doAfterTextChanged {
            filter = it?.toString().orEmpty().trim()
            publish()
        }

        observeLibrary()
        return rootView
    }

    /**
     * Reads the unfiltered library.
     *
     * Deliberately not the app's `reader`: that one already has the blacklist applied, so anything
     * blocked would vanish from this page and could never be unblocked again.
     */
    private fun observeLibrary() {
        val source = (requireActivity().application as Accord).unfilteredReader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(source.songListFlow, source.artistListFlow) { songs, artists ->
                    buildRows(
                        artists = artists.mapNotNull { it.title }.distinct()
                            .sortedBy { it.lowercase() },
                        songs = songs,
                    )
                }.collect { rows ->
                    allRows = rows
                    publish()
                }
            }
        }
    }

    private fun buildRows(
        artists: List<String>,
        songs: List<MediaItem>,
    ): List<BlacklistAdapter.Row> = buildList {
        add(BlacklistAdapter.Row.Header(R.string.blacklist_section_artists, artists.size))
        artists.forEach { add(BlacklistAdapter.Row.Artist(it)) }

        val songRows = songs
            .filter { it.mediaId.isNotBlank() }
            .distinctBy { it.mediaId }
            .map {
                BlacklistAdapter.Row.Song(
                    mediaId = it.mediaId,
                    title = it.mediaMetadata.title?.toString().orEmpty(),
                    artist = it.mediaMetadata.artist?.toString(),
                )
            }
            .sortedBy { it.title.lowercase() }
        add(BlacklistAdapter.Row.Header(R.string.blacklist_section_songs, songRows.size))
        addAll(songRows)
    }

    /**
     * Applies the current filter, dropping headings whose section came back empty so a search does
     * not leave labels with nothing under them.
     */
    private fun publish() {
        if (filter.isEmpty()) {
            adapter.submit(allRows)
            return
        }
        val needle = filter.lowercase()
        val kept = allRows.filter { row ->
            when (row) {
                is BlacklistAdapter.Row.Header -> true
                is BlacklistAdapter.Row.Artist -> row.name.lowercase().contains(needle)
                is BlacklistAdapter.Row.Song ->
                    row.title.lowercase().contains(needle) ||
                        row.artist?.lowercase()?.contains(needle) == true
            }
        }
        adapter.submit(
            kept.filterIndexed { index, row ->
                row !is BlacklistAdapter.Row.Header ||
                    kept.getOrNull(index + 1).let { it != null && it !is BlacklistAdapter.Row.Header }
            }
        )
    }
}
