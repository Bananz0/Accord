package uk.akane.accord.ui.adapters.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import kotlin.random.Random
import uk.akane.accord.ui.components.TrackRowMenu

class SongAdapter(
    private val recyclerView: RecyclerView,
    private val fragment: Fragment,
    private val sourceTransform: suspend (List<MediaItem>) -> List<MediaItem> = { it },
    private val onContentLoaded: (() -> Unit),
) : RecyclerView.Adapter<SongAdapter.ViewHolder>() {

    private val list = mutableListOf<SongListItem>()
    private val songList = mutableListOf<MediaItem>()

    private val mainActivity
        get() = fragment.activity as MainActivity

    /** Everything the library holds, before the screen's search box narrows it. */
    private var unfiltered: List<MediaItem> = emptyList()
    private var filter: String = ""

    init {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                (fragment.activity as MainActivity).reader.songListFlow.collectLatest { newList ->
                    val transformed = sourceTransform(newList)
                    unfiltered = transformed
                    submitList(applyFilter(transformed))
                }
            }
        }
    }

    /**
     * Narrows the list to what matches the screen's search box. Upstream includes that box in the
     * layout and never reads it, so typing in it did nothing.
     */
    fun setFilter(query: String) {
        val next = query.trim()
        if (next == filter) return
        filter = next
        submitList(applyFilter(unfiltered))
    }

    private fun applyFilter(source: List<MediaItem>): List<MediaItem> {
        if (filter.isEmpty()) return source
        val needle = filter.normaliseForFilter()
        return source.filter { item ->
            val metadata = item.mediaMetadata
            metadata.title?.toString()?.normaliseForFilter()?.contains(needle) == true ||
                metadata.artist?.toString()?.normaliseForFilter()?.contains(needle) == true ||
                metadata.albumTitle?.toString()?.normaliseForFilter()?.contains(needle) == true
        }
    }

    private fun String.normaliseForFilter(): String =
        lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val layoutId = when (viewType) {
            VIEW_TYPE_CONTROL -> R.layout.layout_master_control
            VIEW_TYPE_CATEGORY -> R.layout.adapter_category_header
            else -> R.layout.layout_song_item
        }
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        )
    }

    override fun getItemViewType(position: Int): Int {
        return when (list[position]) {
            is SongListItem.Control -> VIEW_TYPE_CONTROL
            is SongListItem.Header -> VIEW_TYPE_CATEGORY
            is SongListItem.Track -> VIEW_TYPE_NORMAL
        }
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        when (val item = list[position]) {
            is SongListItem.Control -> {
                bindControl(holder)
            }
            is SongListItem.Header -> {
                holder.title?.text = item.title
                holder.subtitle?.visibility = View.GONE
                holder.cover?.visibility = View.GONE
            }
            is SongListItem.Track -> {
                holder.cover?.visibility = View.VISIBLE
                holder.subtitle?.visibility = View.VISIBLE
                holder.cover?.load(item.mediaItem.mediaMetadata.artworkUri) {
                    crossfade(true)
                    size(62.dp.px.toInt(), 62.dp.px.toInt())
                }
                holder.title?.text = item.mediaItem.mediaMetadata.title
                holder.subtitle?.text = item.mediaItem.mediaMetadata.artist

                // Upstream draws this button and leaves it inert, which is what made offline
                // downloads unreachable in the new UI.
                holder.menuButton?.setOnClickListener { anchor ->
                    TrackRowMenu.show(anchor, item.mediaItem)
                }

                holder.itemView.setOnClickListener {
                    val mediaController = mainActivity.getPlayer()
                    mediaController?.apply {
                        setMediaItems(
                            songList,
                            songList.indexOf(item.mediaItem),
                            C.TIME_UNSET
                        )
                        prepare()
                        play()
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = list.size

    /**
     * The track at [position], or null where the row is not one.
     *
     * These lists interleave a control block and category headings with the songs, and the swipe
     * gestures act on tracks. Returning null for the rest is what stops a heading being dragged
     * aside to queue nothing.
     */
    fun itemAt(position: Int): MediaItem? =
        (list.getOrNull(position) as? SongListItem.Track)?.mediaItem

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView? = view.findViewById(R.id.cover)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
        val menuButton: View? = view.findViewById(R.id.menu_btn)
    }

    private fun bindControl(holder: ViewHolder) {
        val playAll = holder.itemView.findViewById<MaterialButton?>(R.id.play_all)
        val shuffleAll = holder.itemView.findViewById<MaterialButton?>(R.id.shuffle_all)

        playAll?.setOnClickListener {
            val mediaController = mainActivity.getPlayer() ?: return@setOnClickListener
            if (songList.isEmpty()) {
                Toast.makeText(mainActivity, R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            mediaController.setMediaItems(songList, /* startIndex */ 0, C.TIME_UNSET)
            mediaController.prepare()
            mediaController.play()
        }

        shuffleAll?.setOnClickListener {
            val mediaController = mainActivity.getPlayer() ?: return@setOnClickListener
            if (songList.isEmpty()) {
                Toast.makeText(mainActivity, R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val shuffled = songList.shuffled(Random(System.currentTimeMillis()))
            mediaController.setMediaItems(shuffled, /* startIndex */ 0, C.TIME_UNSET)
            mediaController.prepare()
            mediaController.play()
        }
    }

    /**
     * Serialises submissions. Upstream diffs against the adapter's own mutable list from a
     * background thread, which is safe only while emissions are rare - MediaStore changing is. This
     * app's library arrives from Jellyfin and MediaStore both, so two submissions can overlap, and
     * the second cleared the list the first was still diffing: IndexOutOfBounds inside DiffUtil.
     */
    private val submitMutex = Mutex()

    private fun submitList(newList: List<MediaItem>) {
        CoroutineScope(Dispatchers.Default).launch { submitMutex.withLock {
            val grouped = newList
                .groupBy { it.mediaMetadata.title?.firstOrNull()?.uppercaseChar() ?: '#' }
                .toSortedMap()

            val items = mutableListOf<SongListItem>()
            val newSongList = mutableListOf<MediaItem>()

            items.add(SongListItem.Control)
            for ((key, tracks) in grouped) {
                items.add(SongListItem.Header(key.toString()))
                items.addAll(tracks.map {
                    newSongList.add(it)
                    SongListItem.Track(it)
                })
            }

            // Snapshot on the main thread: copying the live list from here would race the clear
            // below, and diffing against it directly is what crashed.
            val oldSnapshot = withContext(Dispatchers.Main) { list.toList() }
            val diffResult = DiffUtil.calculateDiff(GenreDiffCallback(oldSnapshot, items))

            withContext(Dispatchers.Main) {
                list.clear()
                list.addAll(items)

                songList.clear()
                songList.addAll(newSongList)

                diffResult.dispatchUpdatesTo(this@SongAdapter)
                recyclerView.post { onContentLoaded.invoke() }
            }
        } }
    }

    class GenreDiffCallback(
        private val oldList: List<SongListItem>,
        private val newList: List<SongListItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            when {
                oldList[oldItemPosition] is SongListItem.Control &&
                        newList[newItemPosition] is SongListItem.Control -> true
                oldList[oldItemPosition] is SongListItem.Header &&
                        newList[newItemPosition] is SongListItem.Header ->
                    (oldList[oldItemPosition] as SongListItem.Header).title ==
                            (newList[newItemPosition] as SongListItem.Header).title
                oldList[oldItemPosition] is SongListItem.Track &&
                        newList[newItemPosition] is SongListItem.Track ->
                    (oldList[oldItemPosition] as SongListItem.Track).mediaItem.mediaId ==
                            (newList[newItemPosition] as SongListItem.Track).mediaItem.mediaId
                else -> false
            }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            oldList[oldItemPosition] == newList[newItemPosition]
    }

    sealed class SongListItem {
        object Control : SongListItem()
        data class Header(val title: String) : SongListItem()
        data class Track(val mediaItem: MediaItem) : SongListItem()
    }


    companion object {
        const val VIEW_TYPE_NORMAL = 0
        const val VIEW_TYPE_CONTROL = 1
        const val VIEW_TYPE_CATEGORY = 2
    }
}
