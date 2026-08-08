package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.logic.ArtistCredits
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import kotlin.random.Random
import uk.akane.accord.ui.components.CollectionPopupMenu
import uk.akane.accord.ui.components.TrackRowMenu
import uk.akane.accord.ui.components.TrackSwipeActions
import uk.akane.accord.ui.components.performPressHaptic

class AlbumDetailFragment : SwitcherPostponeFragment() {

    private val activity
        get() = requireActivity() as MainActivity

    private lateinit var headerArt: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var metaView: TextView
    private lateinit var quoteView: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var shuffleButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var scrollView: NestedScrollView
    private lateinit var headerContainer: View
    private lateinit var navigationBar: NavigationBar

    private val trackAdapter = AlbumTrackAdapter()
    private var currentTracks: List<MediaItem> = emptyList()
    private var didLoadOnce = false
    private var collectionDownloaded = false

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_album, container, false)

        headerArt = rootView.findViewById(R.id.ivHeaderArt)
        titleView = rootView.findViewById(R.id.tvAlbumTitle)
        artistView = rootView.findViewById(R.id.tvAlbumArtist)
        metaView = rootView.findViewById(R.id.tvMeta)
        quoteView = rootView.findViewById(R.id.tvQuote)
        playButton = rootView.findViewById(R.id.btnPlay)
        shuffleButton = rootView.findViewById(R.id.btnShuffle)
        recyclerView = rootView.findViewById(R.id.rvTracks)
        scrollView = rootView.findViewById(R.id.scrollContainer)
        headerContainer = rootView.findViewById(R.id.headerContainer)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        val albumTitle = requireArguments().getString(ARG_TITLE).orEmpty()
        val albumArtist = requireArguments().getString(ARG_ARTIST).orEmpty()
        titleView.text = albumTitle
        bindArtistLink(albumArtist)
        navigationBar.setTitle(albumTitle)
        // Without this the three dots showed the general screen menu - refresh the library,
        // open settings - on a screen that is plainly about one album.
        navigationBar.setMenuEntries(
            entries = {
                CollectionPopupMenu.build(
                    resources,
                    withArtist = true,
                    withDownload = !collectionDownloaded,
                    withRemoveDownload = collectionDownloaded,
                )
            },
            onClick = { entry ->
                CollectionPopupMenu.handle(activity, entry, currentTracks, albumTitle)
            },
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = trackAdapter
        navigationBar.attach(scrollView, applyTopPadding = false)
        TrackSwipeActions.attach(
            recyclerView = recyclerView,
            activity = activity,
            trackAt = { index -> currentTracks.getOrNull(index) },
        )

        val headerHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        val headerParams = headerContainer.layoutParams
        if (headerParams.height != headerHeight) {
            headerParams.height = headerHeight
            headerContainer.layoutParams = headerParams
        }
        navigationBar.doOnLayout {
            navigationBar.setCollapseStartOffsetPx((headerHeight - navigationBar.height).coerceAtLeast(0))
        }

        playButton.setOnClickListener {
            val mediaController = activity.getPlayer() ?: return@setOnClickListener
            it.performPressHaptic()
            if (currentTracks.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mediaController.setMediaItems(currentTracks, 0, C.TIME_UNSET)
            mediaController.prepare()
            mediaController.play()
        }

        shuffleButton.setOnClickListener {
            val mediaController = activity.getPlayer() ?: return@setOnClickListener
            it.performPressHaptic()
            if (currentTracks.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val shuffled = currentTracks.shuffled(Random(System.currentTimeMillis()))
            mediaController.setMediaItems(shuffled, 0, C.TIME_UNSET)
            mediaController.prepare()
            mediaController.play()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                activity.reader.songListFlow.collectLatest { songs ->
                    val filtered = songs.filter { song ->
                        val songAlbum = safeAlbum(song.mediaMetadata.albumTitle?.toString())
                        val albumMatches = songAlbum.equals(albumTitle, ignoreCase = true)
                        if (!albumMatches) return@filter false

                        if (albumArtist.isBlank() || albumArtist == "(Unknown Artist)") {
                            true
                        } else {
                            ArtistCredits.primaryArtist(song).equals(albumArtist, ignoreCase = true)
                        }
                    }
                    val sorted = filtered.sortedWith(
                        compareBy<MediaItem> { it.mediaMetadata.trackNumber ?: Int.MAX_VALUE }
                            .thenBy { it.mediaMetadata.title?.toString().orEmpty() }
                    )
                    updateAlbumDetails(sorted)
                }
            }
        }

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
        if (!hidden) refreshDownloadState()
    }

    private fun updateAlbumDetails(tracks: List<MediaItem>) {
        currentTracks = tracks
        trackAdapter.submitList(tracks)
        refreshDownloadState(tracks)

        val first = tracks.firstOrNull()
        if (artistView.text.isBlank() || artistView.text.toString() == "(Unknown Artist)") {
            bindArtistLink(
                first?.mediaMetadata?.albumArtist?.toString()
                    ?: first?.mediaMetadata?.artist?.toString().orEmpty()
            )
        }
        if (first?.mediaMetadata?.artworkUri != null) {
            headerArt.load(first.mediaMetadata.artworkUri) { crossfade(true) }
        } else {
            headerArt.setImageResource(R.drawable.default_cover)
        }

        val genre = first?.mediaMetadata?.genre?.toString()?.trim().orEmpty()
        val year = first?.mediaMetadata?.releaseYear?.takeIf { it > 0 }?.toString().orEmpty()
        val metaParts = listOfNotNull(
            genre.takeIf { it.isNotBlank() }?.uppercase(),
            year.takeIf { it.isNotBlank() }
        )
        if (metaParts.isEmpty()) {
            metaView.visibility = View.GONE
        } else {
            metaView.visibility = View.VISIBLE
            metaView.text = metaParts.joinToString(" \u00b7 ")
        }

        val description = first?.mediaMetadata?.description?.toString()?.trim().orEmpty()
        if (description.isBlank()) {
            quoteView.visibility = View.GONE
        } else {
            quoteView.visibility = View.VISIBLE
            quoteView.text = description
        }

        if (!didLoadOnce) {
            didLoadOnce = true
            notifyContentLoaded()
        }
    }

    private fun safeAlbum(value: String?): String =
        value?.trim().orEmpty().ifEmpty { "(Unknown Album)" }

    private fun safeArtist(value: String?): String =
        value?.trim().orEmpty().ifEmpty { "(Unknown Artist)" }

    private fun bindArtistLink(value: String) {
        val artist = safeArtist(value)
        val canOpen = artist != "(Unknown Artist)"
        artistView.text = artist
        artistView.isClickable = canOpen
        artistView.isFocusable = canOpen
        artistView.setOnClickListener(if (canOpen) View.OnClickListener {
            activity.fragmentSwitcherView.addFragmentToCurrentStack(
                ArtistDetailFragment.newInstance(artist)
            )
        } else null)
    }

    private fun refreshDownloadState(tracks: List<MediaItem> = currentTracks) {
        if (!isAdded || tracks.isEmpty()) {
            collectionDownloaded = false
            return
        }
        val expectedIds = tracks.map(MediaItem::mediaId)
        viewLifecycleOwner.lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.areAllDownloaded(requireContext(), tracks)
            }
            if (currentTracks.map(MediaItem::mediaId) == expectedIds) {
                collectionDownloaded = downloaded
            }
        }
    }

    private inner class AlbumTrackAdapter : RecyclerView.Adapter<AlbumTrackAdapter.ViewHolder>() {
        private val items = mutableListOf<MediaItem>()

        fun submitList(tracks: List<MediaItem>) {
            items.clear()
            items.addAll(tracks)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_album_track_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val title = item.mediaMetadata.title?.toString()?.trim().orEmpty()
            val trackNumber = item.mediaMetadata.trackNumber?.takeIf { it > 0 }

            holder.trackNumber?.text = (trackNumber ?: (position + 1)).toString()
            holder.title?.text = title

            holder.itemView.setOnClickListener {
                val mediaController = activity.getPlayer() ?: return@setOnClickListener
                if (items.isEmpty()) return@setOnClickListener
                mediaController.setMediaItems(items, position, C.TIME_UNSET)
                mediaController.prepare()
                mediaController.play()
            }
            // The button was inflated and located, and nothing was ever attached to it.
            holder.menu?.setOnClickListener { anchor -> TrackRowMenu.show(anchor, item) }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val trackNumber: TextView? = view.findViewById(R.id.track_number)
            val title: TextView? = view.findViewById(R.id.title)
            val menu: View? = view.findViewById(R.id.menu_btn)
        }
    }

    companion object {
        private const val ARG_TITLE = "album_title"
        private const val ARG_ARTIST = "album_artist"

        fun newInstance(title: String, artist: String): AlbumDetailFragment {
            return AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_ARTIST, artist)
                }
            }
        }
    }
}
