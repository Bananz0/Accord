package uk.akane.accord.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.components.TrackRowMenu

/**
 * The results of a library search.
 *
 * Upstream's search screen has a query field, tabs and a results area holding three hardcoded rows -
 * typing in it did nothing at all. This is the list those rows stood in for.
 */
class SearchResultsAdapter(
    private val player: () -> MediaController?
) : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submit(results: List<MediaItem>) {
        val diff = DiffUtil.calculateDiff(Diff(items.toList(), results))
        items.clear()
        items.addAll(results)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.layout_song_item, parent, false)
    )

    /** What row [position] is showing, for the swipe actions. */
    fun itemAt(position: Int): MediaItem? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title?.text = item.mediaMetadata.title
        holder.subtitle?.text = item.mediaMetadata.artist
        holder.cover?.load(item.mediaMetadata.artworkUri) {
            crossfade(true)
            size(62.dp.px.toInt(), 62.dp.px.toInt())
        }
        holder.itemView.setOnClickListener {
            // Plays the whole result set from here, so skipping forward stays within the search.
            player()?.apply {
                setMediaItems(items.toList(), position, C.TIME_UNSET)
                prepare()
                play()
            }
        }
        // The row layout has always drawn a menu button; nothing was behind it here either.
        holder.menu?.setOnClickListener { anchor -> TrackRowMenu.show(anchor, item) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView? = view.findViewById(R.id.cover)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
        val menu: View? = view.findViewById(R.id.menu_btn)
    }

    private class Diff(
        private val old: List<MediaItem>,
        private val new: List<MediaItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].mediaId == new[newPos].mediaId
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].mediaId == new[newPos].mediaId
    }
}
