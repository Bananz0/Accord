package org.akanework.gramophone.ui.home


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import uk.akane.accord.R
import org.akanework.gramophone.logic.ui.coolCrossfade
import androidx.media3.session.MediaController

/**
 * The vertical list of home rows.
 *
 * Each row keeps its own horizontal adapter, and rows share a [RecyclerView.RecycledViewPool] so
 * scrolling past several of them does not inflate a fresh set of cards for each.
 */
class HomeSectionAdapter(
    /**
     * The controller to play a tapped card through. Passed as a lookup rather than an activity so
     * the same feed serves the old screens and the Accord ones, whose activities share no type.
     */
    private val player: () -> MediaController?
) : RecyclerView.Adapter<HomeSectionAdapter.ViewHolder>() {

    private val sections = mutableListOf<HomeSection>()
    private val cardPool = RecyclerView.RecycledViewPool()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.section_title)
        val subtitle: TextView = view.findViewById(R.id.section_subtitle)
        val items: RecyclerView = view.findViewById(R.id.section_items)

        init {
            items.layoutManager =
                LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            items.setRecycledViewPool(cardPool)
            items.isNestedScrollingEnabled = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.homepage_section, parent, false)
    )

    override fun getItemCount(): Int = sections.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val section = sections[position]
        holder.title.text = section.title
        holder.subtitle.text = section.subtitle
        holder.subtitle.visibility =
            if (section.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.items.adapter = CardAdapter(section.cards)
    }

    /** What is currently on screen, for callers that need to re-submit with an extra row folded in. */
    fun currentSections(): List<HomeSection> = sections.toList()

    fun submit(newSections: List<HomeSection>) {
        val diff = DiffUtil.calculateDiff(SectionDiff(sections.toList(), newSections))
        sections.clear()
        sections.addAll(newSections)
        diff.dispatchUpdatesTo(this)
    }

    private inner class CardAdapter(
        private val cards: List<HomeCard>
    ) : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

        inner class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.homepage_recommend_card, parent, false)
        )

        override fun getItemCount(): Int = cards.size

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            val card = cards[position]
            // No error()/placeholder(): Coil3 has no Int overloads, so a drawable id silently binds
            // to kotlin.error() and throws at runtime. The layout's own src is the fallback.
            holder.cover.load(card.cover) { coolCrossfade(true) }
            holder.title.text = card.title
            holder.subtitle.text = card.subtitle
            holder.subtitle.visibility =
                if (card.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener {
                if (card.songs.isEmpty()) return@setOnClickListener
                player()?.apply {
                    setMediaItems(card.songs, card.startIndex, C.TIME_UNSET)
                    prepare()
                    play()
                }
            }
        }
    }

    /**
     * Sections are identified by a stable id, so a rebuild that only changes a row's contents
     * rebinds that row instead of dropping the whole feed and scrolling back to the top.
     */
    private class SectionDiff(
        private val old: List<HomeSection>,
        private val new: List<HomeSection>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
