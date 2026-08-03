package org.akanework.gramophone.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.resourceUri
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.akanework.gramophone.ui.MainActivity
import java.util.Calendar
import kotlin.random.Random

/**
 * The mixes on the home screen.
 *
 * These were previously decorative: four fixed cards with an empty song list and no click handling.
 * Each card now builds its own mix from the library. Play counts, favourites and date added all
 * come from Jellyfin, so the mixes account for listening done on any client, not just this phone.
 */
class HomepageCarouselAdapter(
    private val mainActivity: MainActivity
) : RecyclerView.Adapter<HomepageCarouselAdapter.ViewHolder>() {

    val carouselList = mutableListOf(
        MediaStoreUtils.HomepageCarouselHolder(
            MediaStoreUtils.CarouselType.DAILY_SHUFFLE,
            cover = mainActivity.resourceUri(R.drawable.accord_mix_3),
            banner = mainActivity.resourceUri(R.drawable.accord_mix_3_banner),
            songList = mutableListOf(),
            hint = ContextCompat.getString(mainActivity, R.string.daily_shuffle)
        ),
        MediaStoreUtils.HomepageCarouselHolder(
            MediaStoreUtils.CarouselType.MEET_AGAIN,
            cover = mainActivity.resourceUri(R.drawable.accord_mix_2),
            banner = mainActivity.resourceUri(R.drawable.accord_mix_2_banner),
            songList = mutableListOf(),
            hint = ContextCompat.getString(mainActivity, R.string.mix_most_played)
        ),
        MediaStoreUtils.HomepageCarouselHolder(
            MediaStoreUtils.CarouselType.CUSTOM,
            cover = mainActivity.resourceUri(R.drawable.accord_mix_1),
            banner = mainActivity.resourceUri(R.drawable.accord_mix_1_banner),
            songList = mutableListOf(),
            hint = ContextCompat.getString(mainActivity, R.string.mix_favourites)
        ),
        MediaStoreUtils.HomepageCarouselHolder(
            MediaStoreUtils.CarouselType.NEW_DELIVERY,
            cover = mainActivity.resourceUri(R.drawable.accord_mix_4),
            banner = mainActivity.resourceUri(R.drawable.accord_mix_4_banner),
            songList = mutableListOf(),
            hint = ContextCompat.getString(mainActivity, R.string.mix_recently_added)
        ),
    )

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val coverImageView: ImageView = view.findViewById(R.id.cover)
        val bannerImageView: ImageView = view.findViewById(R.id.banner)
        val hintTextView: TextView = view.findViewById(R.id.hint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.homepage_carousel, parent, false)
        )

    override fun getItemCount(): Int = carouselList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = carouselList[position]
        holder.coverImageView.setImageURI(item.cover)
        holder.bannerImageView.setImageURI(item.banner)
        holder.hintTextView.text = item.hint
        holder.itemView.setOnClickListener {
            // Built at tap time so a mix always reflects the library as it stands, including a
            // sync that finished after the home screen was drawn.
            val songs = buildMix(item.carouselType)
            if (songs.isEmpty()) return@setOnClickListener
            mainActivity.getPlayer()?.apply {
                setMediaItems(songs, 0, C.TIME_UNSET)
                prepare()
                play()
            }
        }
    }

    private fun buildMix(type: MediaStoreUtils.CarouselType): List<MediaItem> {
        val library = mainActivity.libraryViewModel.mediaItemList.value ?: return emptyList()
        if (library.isEmpty()) return emptyList()
        return when (type) {
            // Seeded by the day so the shuffle stays put until tomorrow rather than changing
            // every time the card is tapped.
            MediaStoreUtils.CarouselType.DAILY_SHUFFLE ->
                library.shuffled(Random(dailySeed())).take(MIX_SIZE)

            MediaStoreUtils.CarouselType.MEET_AGAIN ->
                library.filter { it.playCount() > 0 }
                    .sortedByDescending { it.playCount() }
                    .take(MIX_SIZE)

            MediaStoreUtils.CarouselType.NEW_DELIVERY ->
                library.sortedByDescending { it.addDate() }.take(MIX_SIZE)

            // Favourites, falling back to a plain shuffle when nothing is starred yet so the card
            // still does something.
            else -> library.filter { it.isFavourite() }.shuffled().take(MIX_SIZE)
                .ifEmpty { library.shuffled().take(MIX_SIZE) }
        }
    }

    private fun dailySeed(): Long = Calendar.getInstance().let {
        it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR)
    }

    private fun MediaItem.playCount(): Int =
        mediaMetadata.extras?.getInt(JellyfinLibraryLoader.EXTRA_PLAY_COUNT, 0) ?: 0

    private fun MediaItem.isFavourite(): Boolean =
        mediaMetadata.extras?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

    private fun MediaItem.addDate(): Long =
        mediaMetadata.extras?.getLong("AddDate", 0L) ?: 0L

    private companion object {
        const val MIX_SIZE = 50
    }
}
