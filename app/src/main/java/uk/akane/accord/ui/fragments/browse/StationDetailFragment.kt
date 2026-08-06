package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
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
import kotlinx.coroutines.launch
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import kotlin.random.Random

/**
 * A home-screen station opened as its own screen - Daily shuffle, Most played, Favourites and the
 * rest - so a tap gives somewhere to look at the tracks and choose where to start, rather than
 * immediately taking over what is playing.
 *
 * The station is carried as a list of media ids and resolved against the library, so it survives
 * the fragment being recreated without a list of MediaItems being pushed through a Bundle.
 */
class StationDetailFragment : SwitcherPostponeFragment() {

    private val activity
        get() = requireActivity() as MainActivity

    private lateinit var headerArt: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var metaView: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var shuffleButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var scrollView: NestedScrollView
    private lateinit var headerContainer: View
    private lateinit var navigationBar: NavigationBar

    private val trackAdapter = StationTrackAdapter()
    private var currentTracks: List<MediaItem> = emptyList()

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
        playButton = rootView.findViewById(R.id.btnPlay)
        shuffleButton = rootView.findViewById(R.id.btnShuffle)
        recyclerView = rootView.findViewById(R.id.rvTracks)
        scrollView = rootView.findViewById(R.id.scrollContainer)
        headerContainer = rootView.findViewById(R.id.headerContainer)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        // The album layout carries a review blurb. A station has no such thing, and leaving the view
        // in place showed whatever text the album screen last had.
        rootView.findViewById<TextView>(R.id.tvQuote).visibility = View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val subtitle = requireArguments().getString(ARG_SUBTITLE).orEmpty()
        titleView.text = title
        artistView.text = subtitle
        navigationBar.setTitle(title)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = trackAdapter
        navigationBar.attach(scrollView, applyTopPadding = false)

        val headerHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        headerContainer.layoutParams = headerContainer.layoutParams.also { it.height = headerHeight }
        navigationBar.doOnLayout {
            navigationBar.setCollapseStartOffsetPx(
                (headerHeight - navigationBar.height).coerceAtLeast(0)
            )
        }

        playButton.setOnClickListener { play(currentTracks, 0) }
        shuffleButton.setOnClickListener {
            play(currentTracks.shuffled(Random(System.currentTimeMillis())), 0)
        }

        val wantedIds = requireArguments().getStringArray(ARG_MEDIA_IDS)?.toList().orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity.reader.songListFlow.collectLatest { songs ->
                    val byId = songs.associateBy { it.mediaId }
                    // Keeps the station's own order, which for most played or recently added is the
                    // entire point.
                    currentTracks = wantedIds.mapNotNull { byId[it] }
                    trackAdapter.submitList(currentTracks)
                    metaView.text = resources.getQuantityString(
                        R.plurals.songs, currentTracks.size, currentTracks.size
                    )
                    headerArt.load(currentTracks.firstOrNull()?.mediaMetadata?.artworkUri) {
                        crossfade(true)
                    }
                    // Releases the switcher transition postponed in init, so the screen animates in
                    // with its tracks already on it rather than sliding in empty.
                    notifyContentLoaded()
                }
            }
        }

        return rootView
    }

    private fun play(tracks: List<MediaItem>, startIndex: Int) {
        if (tracks.isEmpty()) return
        activity.getPlayer()?.apply {
            setMediaItems(tracks, startIndex, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    private inner class StationTrackAdapter :
        RecyclerView.Adapter<StationTrackAdapter.ViewHolder>() {

        private val items = mutableListOf<MediaItem>()

        fun submitList(tracks: List<MediaItem>) {
            items.clear()
            items.addAll(tracks)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_album_track_item, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.trackNumber?.text = (position + 1).toString()
            holder.title?.text = item.mediaMetadata.title?.toString()?.trim().orEmpty()
            holder.itemView.setOnClickListener { play(items.toList(), position) }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val trackNumber: TextView? = view.findViewById(R.id.track_number)
            val title: TextView? = view.findViewById(R.id.title)
        }
    }

    companion object {
        private const val ARG_TITLE = "station_title"
        private const val ARG_SUBTITLE = "station_subtitle"
        private const val ARG_MEDIA_IDS = "station_media_ids"

        fun newInstance(title: String, subtitle: String?, mediaIds: List<String>) =
            StationDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_SUBTITLE, subtitle.orEmpty())
                    putStringArray(ARG_MEDIA_IDS, mediaIds.toTypedArray())
                }
            }
    }
}
