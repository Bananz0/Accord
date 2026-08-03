package org.akanework.gramophone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.ui.LibraryViewModel
import org.akanework.gramophone.ui.adapters.AlbumAdapter
import org.akanework.gramophone.ui.adapters.ArtistAdapter
import org.akanework.gramophone.ui.adapters.SongAdapter

/**
 * The whole library, filtered to one category, opened from the Library screen's category list.
 *
 * [AdapterFragment] already does this job for the Browse tab, but it reads its app bar off a
 * [BrowseFragment] parent, so it cannot be pushed onto the Library back stack. This is the same
 * pattern against the standalone sub-fragment layout.
 *
 * The adapters are handed the view model's LiveData rather than a snapshot, so a sync that finishes
 * while this screen is open updates it in place.
 */
class LibraryCategorySubFragment : BaseFragment() {

    private val libraryViewModel: LibraryViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_general_sub, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbarLayout =
            rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        val recyclerView = rootView.findViewById<MyRecyclerView>(R.id.recyclerview)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        val categoryId = requireArguments().getInt(EXTRA_CATEGORY)
        collapsingToolbarLayout.title = getString(titleFor(categoryId))

        val adapter = when (categoryId) {
            R.id.songs -> SongAdapter(
                this, libraryViewModel.mediaItemList, true, null,
                ownsView = true, isSubFragment = true
            )

            R.id.albums -> AlbumAdapter(this, libraryViewModel.albumItemList, isSubFragment = true)
            R.id.artists -> ArtistAdapter(
                this, libraryViewModel.artistItemList, libraryViewModel.albumArtistItemList
            )

            else -> throw IllegalArgumentException("unsupported category $categoryId")
        }

        recyclerView.enableEdgeToEdgePaddingListener()
        recyclerView.setAppBar(appBarLayout)
        recyclerView.adapter = adapter.concatAdapter
        recyclerView.fastScroll(adapter, adapter.itemHeightHelper)

        topAppBar.setNavigationOnClickListener {
            (requireParentFragment() as BaseWrapperFragment).childFragmentManager.popBackStack()
        }

        return rootView
    }

    private fun titleFor(categoryId: Int) = when (categoryId) {
        R.id.songs -> R.string.category_songs
        R.id.albums -> R.string.category_albums
        R.id.artists -> R.string.category_artists
        else -> throw IllegalArgumentException("unsupported category $categoryId")
    }

    companion object {
        private const val EXTRA_CATEGORY = "Category"

        fun of(categoryId: Int) = LibraryCategorySubFragment().apply {
            arguments = Bundle().apply { putInt(EXTRA_CATEGORY, categoryId) }
        }
    }
}
