package org.akanework.gramophone.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import org.akanework.gramophone.logic.data.library.BlacklistStore
import uk.akane.accord.R

/**
 * The blacklist page's single list: artists and songs under their own headings.
 *
 * One adapter rather than two screens because they answer the same question, and because a heading
 * with nothing under it is how the user learns a section is empty - which the old folder-only page
 * could never say.
 */
class BlacklistAdapter(
    private val blacklist: BlacklistStore,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class Header(val titleRes: Int, val count: Int) : Row
        data class Artist(val name: String) : Row
        data class Song(val mediaId: String, val title: String, val artist: String?) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) VIEW_TYPE_HEADER else VIEW_TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.adapter_blacklist_header, parent, false))
        } else {
            EntryViewHolder(
                inflater.inflate(R.layout.adapter_blacklist_folder_card, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row)
            is Row.Artist -> (holder as EntryViewHolder).bind(
                label = row.name,
                detail = null,
                checked = blacklist.isArtistBlocked(row.name),
            ) { checked -> blacklist.setArtistBlocked(row.name, checked) }

            is Row.Song -> (holder as EntryViewHolder).bind(
                label = row.title,
                detail = row.artist,
                checked = blacklist.isSongBlocked(row.mediaId),
            ) { checked -> blacklist.setSongBlocked(row.mediaId, checked) }
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.header_title)

        fun bind(row: Row.Header) {
            title.text = if (row.count > 0) {
                itemView.context.getString(row.titleRes) + "  ·  ${row.count}"
            } else {
                itemView.context.getString(row.titleRes)
            }
        }
    }

    private class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val checkBox: MaterialCheckBox = view.findViewById(R.id.checkbox)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)

        fun bind(
            label: String,
            detail: String?,
            checked: Boolean,
            onToggle: (Boolean) -> Unit,
        ) {
            title.text = label
            subtitle.text = detail
            subtitle.visibility = if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
            // Cleared first: a recycled row would otherwise fire the previous row's toggle while
            // this one's state is being restored.
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = checked
            checkBox.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
            itemView.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_ENTRY = 1
    }
}
