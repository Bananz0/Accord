package org.akanework.gramophone.ui.fragments.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.data.spotify.SpotifyClient
import org.akanework.gramophone.logic.data.spotify.SpotifyCredentialStore
import org.akanework.gramophone.logic.data.spotify.SpotifyPlaylistImporter
import org.akanework.gramophone.logic.utils.DatabaseUtils
import org.akanework.gramophone.ui.LibraryViewModel
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class SpotifySettingsFragment : BaseSettingFragment(
    R.string.settings_category_spotify,
    { SpotifySettingsTopFragment() }
)

/**
 * Connects a Spotify account and imports playlists into the local library.
 *
 * Import is one-way and read-only: playlist and track names come across, are matched against the
 * user's own Jellyfin tracks, and become an ordinary local playlist. Nothing is written back to
 * Spotify and no audio is taken from it.
 */
class SpotifySettingsTopFragment : BasePreferenceFragment() {

    private val libraryViewModel: LibraryViewModel by activityViewModels()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_spotify, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // Also covers coming back from the browser after authorising.
        refreshSummaries()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "spotify_account" -> onAccountClicked()
            "spotify_client_id" -> showClientIdDialog()
            "spotify_import" -> showPlaylistPicker()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSummaries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val store = SpotifyCredentialStore(requireContext())
                Triple(store.isLinked(), store.hasClientId(), store.displayName)
            }
            if (!isAdded) return@launch
            val (linked, hasClientId, name) = state
            findPreference<Preference>("spotify_account")?.summary = when {
                !hasClientId -> getString(R.string.spotify_account_needs_client_id)
                linked -> getString(R.string.spotify_account_linked, name ?: "")
                else -> getString(R.string.spotify_account_not_linked)
            }
            findPreference<Preference>("spotify_import")?.isEnabled = linked
        }
    }

    private fun onAccountClicked() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = withContext(Dispatchers.IO) { SpotifyCredentialStore(requireContext()) }
            val linked = withContext(Dispatchers.IO) { store.isLinked() }
            val hasClientId = withContext(Dispatchers.IO) { store.hasClientId() }
            if (!isAdded) return@launch
            when {
                !hasClientId -> showClientIdDialog()
                linked -> showDisconnectDialog()
                else -> startAuthorisation(store)
            }
        }
    }

    /**
     * Hands the user to Spotify in a browser.
     *
     * Deliberately not an in-app WebView: the user is typing their Spotify password, and they should
     * be able to see the real address bar and use their password manager.
     */
    private fun startAuthorisation(store: SpotifyCredentialStore) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                SpotifyClient(store).buildAuthorizationUrl()
            }
            if (!isAdded) return@launch
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(), R.string.spotify_error_no_browser, Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDisconnectDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.spotify_account)
            .setMessage(R.string.spotify_disconnect_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lastfm_disconnect) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SpotifyCredentialStore(requireContext()).clear(requireContext())
                    }
                    if (isAdded) refreshSummaries()
                }
            }
            .show()
    }

    private fun showClientIdDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_spotify_client_id, null)
        val field = view.findViewById<TextInputEditText>(R.id.client_id)

        viewLifecycleOwner.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                SpotifyCredentialStore(requireContext()).clientId
            }
            if (!isAdded) return@launch
            field.setText(existing)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.spotify_client_id)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = field.text?.toString()?.trim().orEmpty()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            SpotifyCredentialStore(requireContext()).apply {
                                clientId = value
                                publishLinkFlag(requireContext())
                            }
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun showPlaylistPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) {
                try {
                    val store = SpotifyCredentialStore(requireContext())
                    SpotifyClient(store)
                        .playlists(requireContext(), System.currentTimeMillis())
                } catch (e: Exception) {
                    null
                }
            }
            if (!isAdded) return@launch
            if (playlists.isNullOrEmpty()) {
                Toast.makeText(
                    requireContext(), R.string.spotify_no_playlists, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val labels = playlists.map {
                getString(R.string.spotify_playlist_label, it.name, it.trackCount)
            }.toTypedArray()
            val checked = BooleanArray(playlists.size)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.spotify_import)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.spotify_import_action) { _, _ ->
                    val selected = playlists.filterIndexed { index, _ -> checked[index] }
                    if (selected.isNotEmpty()) importPlaylists(selected)
                }
                .show()
        }
    }

    private fun importPlaylists(selected: List<SpotifyClient.Playlist>) {
        Toast.makeText(requireContext(), R.string.spotify_importing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val library = libraryViewModel.mediaItemList.value.orEmpty()
            val results = withContext(Dispatchers.IO) {
                val store = SpotifyCredentialStore(requireContext())
                val client = SpotifyClient(store)
                selected.mapNotNull { playlist ->
                    try {
                        val tracks = client.playlistTracks(
                            requireContext(), playlist.id, System.currentTimeMillis()
                        )
                        SpotifyPlaylistImporter.import(
                            requireContext(), playlist.name, tracks, library
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            if (!isAdded) return@launch
            // The playlist list is held in the view model, so it has to be reloaded for the new
            // playlists to appear anywhere in the app.
            DatabaseUtils.getPrivatePlaylist(libraryViewModel, requireContext())
            val matched = results.sumOf { it.matched }
            val missing = results.sumOf { it.missing }
            Toast.makeText(
                requireContext(),
                getString(R.string.spotify_import_result, matched, missing),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
