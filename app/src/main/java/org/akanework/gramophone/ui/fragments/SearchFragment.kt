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

package org.akanework.gramophone.ui.fragments


import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import uk.akane.accord.R
import org.akanework.gramophone.logic.applyGeneralMenuItem
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.LibraryViewModel
import org.akanework.gramophone.ui.adapters.SongAdapter
import kotlinx.coroutines.Job

/**
 * SearchFragment:
 *   A fragment that contains a search bar which browses
 * the library finding items matching user input.
 *
 * @author AkaneTan
 */
class SearchFragment : BaseFragment(null) {
    private val handler = Handler(Looper.getMainLooper())
    private val libraryViewModel: LibraryViewModel by activityViewModels()

    private var searchJob: Job? = null

    /** Identifies the newest query, so slower older ones cannot overwrite its results. */
    private var searchSeq = 0
    private lateinit var editText: EditText

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        val rootView = inflater.inflate(R.layout.legacy_fragment_search, container, false)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appbarlayout)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)

        appBarLayout.enableEdgeToEdgePaddingListener()
        editText = rootView.findViewById(R.id.edit_text)
        val recyclerView = rootView.findViewById<MyRecyclerView>(R.id.recyclerview)
        val songAdapter =
            SongAdapter(
                this, listOf(),
                true, null, false, isSubFragment = true,
                allowDiffUtils = true, rawOrderExposed = true
            )
        topAppBar.overflowIcon = AppCompatResources.getDrawable(
            requireContext(), R.drawable.ic_more_vert_bold
        )!!.apply {
            setTint(
                resources.getColor(R.color.contrast_themeColor, null)
            )
        }

        recyclerView.enableEdgeToEdgePaddingListener(ime = true)
        recyclerView.setAppBar(appBarLayout)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = songAdapter.concatAdapter

        // Build FastScroller.
        recyclerView.fastScroll(songAdapter, songAdapter.itemHeightHelper)

        // The library used to come from MediaStore and was ready before this screen could be
        // opened. Coming from Jellyfin it can take the better part of a minute on a cold start, so
        // a query typed during the sync would filter an empty list and report "no results" for a
        // library that simply had not arrived yet. Re-running the active query whenever the
        // library changes makes results appear as soon as they exist.
        libraryViewModel.mediaItemList.observe(viewLifecycleOwner) {
            if (!editText.text.isNullOrBlank()) runSearch(editText.text.toString(), songAdapter)
        }

        editText.addTextChangedListener { rawText ->
            // TODO sort results by match quality? (using NaturalOrderHelper)
            if (rawText.isNullOrBlank()) {
                songAdapter.updateList(listOf(), now = true, true)
            } else {
                // make sure the user doesn't edit away our text while we are filtering
                runSearch(rawText.toString(), songAdapter)
            }
        }

        topAppBar.applyGeneralMenuItem(this, libraryViewModel)

        return rootView
    }

    /**
     * Filters the library for [text] off the main thread and hands the results to the adapter.
     *
     * Results are stamped with the query they belong to and dropped if a newer query has started.
     * Cancelling the previous job is not enough on its own: a broad query like "be" matches
     * thousands of tracks and takes longer than the "becky" that supersedes it, so without this
     * check it finishes last and overwrites the newer, correct results.
     */
    private fun runSearch(text: String, songAdapter: SongAdapter) {
        searchJob?.cancel()
        val seq = ++searchSeq
        searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val normalizedSearch = text.normalizeSearch()
            val library = libraryViewModel.mediaItemList.value
            val results = library?.filter {
                // Makes cancellation actually take effect part-way through a large library.
                ensureActive()
                val isMatchingTitle =
                    it.mediaMetadata.title?.toString()?.normalizeSearch()?.contains(normalizedSearch) == true
                val isMatchingAlbum =
                    it.mediaMetadata.albumTitle?.toString()?.normalizeSearch()?.contains(normalizedSearch) == true
                val isMatchingArtist =
                    it.mediaMetadata.artist?.toString()?.normalizeSearch()?.contains(normalizedSearch) == true
                isMatchingTitle || isMatchingAlbum || isMatchingArtist
            }
            Log.d(
                "SearchFragment",
                "query='$normalizedSearch' library=${library?.size} results=${results?.size} seq=$seq"
            )
            handler.post {
                if (seq != searchSeq) return@post
                songAdapter.updateList(results ?: listOf(), now = true, true)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewLifecycleOwner.lifecycleScope.cancel()
    }

    private fun String.normalizeSearch(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
}