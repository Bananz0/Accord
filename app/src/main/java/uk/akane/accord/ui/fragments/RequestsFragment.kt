package uk.akane.accord.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import org.akanework.gramophone.logic.data.lidarr.LidarrRequester
import org.akanework.gramophone.logic.data.requests.PlaylistLinkResolver
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.TrackSwipeActions

/**
 * Asking Lidarr for music that is not in the library yet.
 *
 * Two ways in, both through the same field. Type a name and it searches Lidarr, which looks the
 * album up in MusicBrainz and can add it. Paste a playlist link - Deezer or Spotify - and every
 * track on it that is missing becomes a request, which is the case worth having: someone shares a
 * playlist and the library catches up with it.
 *
 * Lidarr's unit is the album, so a single track is requested by asking for the album that carries
 * it. There is no way to fetch one song on its own, and pretending otherwise would just fail
 * quietly.
 */
class RequestsFragment : Fragment() {

    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private val adapter = ResultAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_requests, container, false)

        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            (activity as? MainActivity)?.fragmentSwitcherView?.popBackTopFragmentIfExists()
        }

        query = rootView.findViewById(R.id.request_query)
        status = rootView.findViewById(R.id.request_status)
        results = rootView.findViewById(R.id.request_results)
        results.layoutManager = LinearLayoutManager(requireContext())
        results.adapter = adapter
        // Same gesture as everywhere else in the app; here both directions mean request it.
        TrackSwipeActions.attachRequest(
            recyclerView = results,
            canSwipe = { position -> adapter.albumAt(position)?.alreadyAdded == false },
            onRequest = { position -> adapter.albumAt(position)?.let { addAlbum(it) } },
        )

        query.setOnEditorActionListener { _, _, _ ->
            submit(query.text?.toString().orEmpty())
            true
        }
        return rootView
    }

    /** A link is imported; anything else is treated as a search term. */
    private fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        // Searching only needs somewhere to ask. isConfigured() additionally demands a root folder
        // and the two profiles, which are only needed to actually add something - gating search on
        // them meant a fully reachable Lidarr still answered "set up Lidarr first".
        val store = LidarrCredentialStore(requireContext())
        if (store.serverUrl.isNullOrBlank() || store.apiKey.isNullOrBlank()) {
            showStatus(getString(R.string.requests_no_lidarr))
            return
        }
        if (text.startsWith("http", ignoreCase = true) || text.startsWith("spotify:")) {
            importLink(text)
        } else {
            search(text)
        }
    }

    private fun search(term: String) {
        showStatus(getString(R.string.requests_searching))
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { lidarrClient().searchAlbums(term) }
            }
            found.onSuccess { albums ->
                adapter.submit(albums)
                showStatus(
                    if (albums.isEmpty()) getString(R.string.requests_no_results)
                    else resources.getQuantityString(
                        R.plurals.requests_results, albums.size, albums.size
                    )
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.requests_failed)) }
        }
    }

    private fun importLink(url: String) {
        showStatus(getString(R.string.requests_reading_link))
        viewLifecycleOwner.lifecycleScope.launch {
            when (val resolved = withContext(Dispatchers.IO) {
                PlaylistLinkResolver.resolve(requireContext(), url)
            }) {
                is PlaylistLinkResolver.Result.Resolved -> requestAll(resolved.tracks)
                is PlaylistLinkResolver.Result.Failed -> showStatus(resolved.reason)
                is PlaylistLinkResolver.Result.Unsupported ->
                    showStatus("${resolved.provider}: ${resolved.reason}")
                PlaylistLinkResolver.Result.NotALink ->
                    showStatus(getString(R.string.requests_unknown_link))
            }
        }
    }

    private suspend fun requestAll(tracks: List<LidarrRequester.Wanted>) {
        if (tracks.isEmpty()) {
            showStatus(getString(R.string.requests_no_results))
            return
        }
        showStatus(getString(R.string.requests_sending, tracks.size))
        val outcome = withContext(Dispatchers.IO) {
            runCatching { LidarrRequester.request(requireContext(), tracks) }
        }
        outcome.onSuccess {
            showStatus(getString(R.string.requests_outcome, it.requested, it.notFound))
        }.onFailure { showStatus(it.message ?: getString(R.string.requests_failed)) }
    }

    private fun lidarrClient() = LidarrClient(LidarrCredentialStore(requireContext()))

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {
        private val items = mutableListOf<LidarrClient.AlbumResult>()

        fun albumAt(position: Int): LidarrClient.AlbumResult? = items.getOrNull(position)

        fun submit(albums: List<LidarrClient.AlbumResult>) {
            items.clear()
            items.addAll(albums)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_song_item, parent, false)
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val album = items[position]
            holder.title?.text = album.title
            holder.subtitle?.text = listOfNotNull(
                album.artistName.takeIf { it.isNotBlank() },
                album.year?.toString(),
                getString(R.string.requests_already_added).takeIf { album.alreadyAdded }
            ).joinToString(" · ")
            holder.cover?.load(album.coverUrl) { crossfade(true) }
            holder.menu?.visibility = View.GONE

            holder.itemView.setOnClickListener {
                if (album.alreadyAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.requests_already_added,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                addAlbum(album)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView? = view.findViewById(R.id.cover)
            val title: TextView? = view.findViewById(R.id.title)
            val subtitle: TextView? = view.findViewById(R.id.subtitle)
            val menu: View? = view.findViewById(R.id.menu_btn)
        }
    }

    private fun addAlbum(album: LidarrClient.AlbumResult) {
        // Lidarr will not accept an album without a root folder and both profiles. Rather than
        // sending the user off to find three fields, ask for them here - the server knows what
        // the choices are - and carry on with the request that prompted it.
        if (!LidarrCredentialStore(requireContext()).isConfigured()) {
            LidarrSetupPrompt.ensureConfigured(requireContext(), viewLifecycleOwner) {
                addAlbum(album)
            }
            return
        }
        showStatus(getString(R.string.requests_adding, album.title))
        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { lidarrClient().addAlbum(album) }
            }
            added.onSuccess {
                showStatus(
                    getString(
                        if (it) R.string.requests_added else R.string.requests_failed,
                        album.title
                    )
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.requests_failed)) }
        }
    }
}
