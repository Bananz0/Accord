package uk.akane.accord.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import uk.akane.accord.R

/** Albums returned by Lidarr's metadata lookup inside the shared Search surface. */
class LidarrSearchResultsAdapter(
    private val onAlbumClick: (LidarrClient.AlbumResult) -> Unit,
) : RecyclerView.Adapter<LidarrSearchResultsAdapter.ViewHolder>() {

    private val items = mutableListOf<LidarrClient.AlbumResult>()

    fun submit(results: List<LidarrClient.AlbumResult>) {
        val diff = DiffUtil.calculateDiff(Diff(items.toList(), results))
        items.clear()
        items.addAll(results)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.layout_song_item, parent, false)
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val album = items[position]
        holder.title?.text = album.title
        holder.subtitle?.text = listOfNotNull(
            album.artistName.takeIf(String::isNotBlank),
            album.year?.toString(),
            holder.itemView.context.getString(R.string.requests_already_added)
                .takeIf { album.alreadyAdded },
        ).joinToString(" · ")
        holder.cover?.load(album.coverUrl) { crossfade(true) }
        holder.menu?.visibility = View.GONE
        holder.itemView.setOnClickListener { onAlbumClick(album) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView? = view.findViewById(R.id.cover)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
        val menu: View? = view.findViewById(R.id.menu_btn)
    }

    private class Diff(
        private val old: List<LidarrClient.AlbumResult>,
        private val new: List<LidarrClient.AlbumResult>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos].foreignAlbumId == new[newPos].foreignAlbumId
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
