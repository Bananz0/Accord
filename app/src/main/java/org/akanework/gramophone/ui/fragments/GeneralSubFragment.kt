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


import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import coil3.load
import com.google.android.material.card.MaterialCardView
import uk.akane.accord.R
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.logic.ui.MyRecyclerView
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.akanework.gramophone.ui.LibraryViewModel
import org.akanework.gramophone.ui.adapters.SongAdapter
import org.akanework.gramophone.ui.adapters.Sorter

/**
 * GeneralSubFragment:
 *   Inherited from [BaseFragment]. Sub fragment of all
 * possible item types. TODO: Artist / AlbumArtist
 *
 * @see BaseFragment
 * @author AkaneTan, nift4
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GeneralSubFragment : BaseFragment(true) {
    private val libraryViewModel: LibraryViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {

        lateinit var itemList: List<MediaItem>

        val rootView = inflater.inflate(R.layout.fragment_general_sub, container, false)
        val topAppBar = rootView.findViewById<MaterialToolbar>(R.id.topAppBar)
        val collapsingToolbarLayout =
            rootView.findViewById<CollapsingToolbarLayout>(R.id.collapsingtoolbar)
        val recyclerView = rootView.findViewById<MyRecyclerView>(R.id.recyclerview)
        val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.appbarlayout)
        appBarLayout.enableEdgeToEdgePaddingListener()

        if (libraryViewModel.albumItemList.value == null) {
            // TODO make it wait for lib load instead of breaking state restore
            // (still better than crashing, though)
            requireParentFragment().childFragmentManager.popBackStack()
            return null
        }
        val bundle = requireArguments()
        val itemType = bundle.getInt("Item")
        val position = bundle.getInt("Position")

        val title: String?

        var helper: Sorter.NaturalOrderHelper<MediaItem>? = null

        when (itemType) {
            R.id.album -> {
                val item = libraryViewModel.albumItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_album)
                itemList = item.songList
                helper =
                    Sorter.NaturalOrderHelper {
                        it.mediaMetadata.trackNumber?.plus(
                            it.mediaMetadata.discNumber?.times(1000) ?: 0
                        ) ?: 0
                    }
            }

            R.id.special_album -> {
                if (libraryViewModel.privateAlbumList.isNotEmpty()) {
                    val item = libraryViewModel.privateAlbumList[position]
                    title = item.title ?: requireContext().getString(R.string.unknown_album)
                    itemList = item.songList
                    helper =
                        Sorter.NaturalOrderHelper {
                            it.mediaMetadata.trackNumber?.plus(
                                it.mediaMetadata.discNumber?.times(1000) ?: 0
                            ) ?: 0
                        }
                } else {
                    requireParentFragment().childFragmentManager.popBackStack()
                    return null
                }
            }

            /*R.id.artist -> {
                val item = libraryViewModel.artistItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_artist)
                itemList = item.songList
            } TODO */

            R.id.genres -> {
                // Genres
                val item = libraryViewModel.genreItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_genre)
                itemList = item.songList
            }

            R.id.dates -> {
                // Dates
                val item = libraryViewModel.dateItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_year)
                itemList = item.songList
            }

            /*R.id.album_artist -> {
                // Album artists
                val item = libraryViewModel.albumArtistItemList.value!![position]
                title = item.title ?: requireContext().getString(R.string.unknown_artist)
                itemList = item.songList
            } TODO */

            R.id.playlist -> {
                // Playlists
                val item = libraryViewModel.playlistList.value!![position]
                title = if (item is MediaStoreUtils.RecentlyAdded) {
                    requireContext().getString(R.string.recently_added)
                } else {
                    item.title ?: requireContext().getString(R.string.unknown_playlist)
                }
                itemList = item.songList
                helper = Sorter.NaturalOrderHelper { itemList.indexOf(it) }
            }

            else -> throw IllegalArgumentException()
        }

        // Show title text.
        collapsingToolbarLayout.title = title

        // Album artwork. Only albums have a cover worth showing here, and the header is grown to
        // make room for it only in that case so artists and genres keep their compact header.
        val coverUri = when (itemType) {
            R.id.album -> libraryViewModel.albumItemList.value?.getOrNull(position)?.cover
            R.id.special_album -> libraryViewModel.privateAlbumList.getOrNull(position)?.cover
            else -> null
        }
        if (coverUri != null) {
            val coverFrame = rootView.findViewById<MaterialCardView>(R.id.sub_cover_frame)
            val cover = rootView.findViewById<ImageView>(R.id.sub_cover)
            coverFrame.visibility = View.VISIBLE
            collapsingToolbarLayout.updateLayoutParams {
                height = resources.getDimensionPixelSize(R.dimen.sub_app_bar_height_with_cover)
            }
            // No error()/placeholder() here: Coil3 has no Int overloads for them, so passing a
            // drawable id silently binds to kotlin.error() and throws IllegalStateException at
            // runtime. The ImageView's android:src provides the fallback instead.
            cover.load(coverUri)
        }

        val songAdapter =
            SongAdapter(
                this,
                itemList,
                true,
                helper,
                true,
                isSubFragment = true
            )

        recyclerView.enableEdgeToEdgePaddingListener()
        recyclerView.setAppBar(appBarLayout)
        recyclerView.adapter = songAdapter.concatAdapter

        // Build FastScroller.
        recyclerView.fastScroll(songAdapter, songAdapter.itemHeightHelper)

        topAppBar.setNavigationOnClickListener {
            Log.d("TAG", "ok${requireParentFragment().childFragmentManager.fragments.size}")
            (requireParentFragment() as BaseWrapperFragment).childFragmentManager.popBackStack()
        }

        return rootView
    }
}
