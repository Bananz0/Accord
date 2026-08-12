package uk.akane.accord.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import org.akanework.gramophone.ui.JellyfinLoginActivity
import org.akanework.gramophone.ui.fragments.settings.AppearanceSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.AudioSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.BehaviorSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.BlacklistSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.DownloadsSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.ExperimentalSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.LidarrSettingsFragment
import org.akanework.gramophone.ui.fragments.settings.ScrobblingSettingsFragment
import org.akanework.gramophone.logic.data.lyrics.LyricsIndexWorker
import org.akanework.gramophone.logic.data.lyrics.LyricsIndexer
import uk.akane.accord.ui.fragments.settings.PlayCountImportFragment
import org.akanework.gramophone.ui.fragments.settings.SpotifySettingsFragment
import uk.akane.accord.BuildConfig
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.SettingsListBuilder
import java.io.ByteArrayOutputStream

/**
 * Settings, in the shape the 1.0-stable build uses - sectioned cards rather than the old preference
 * screens - and reachable again.
 *
 * The old shell navigated settings by adding fragments to a container that only existed in that
 * activity, so once the Accord shell became the entry point every settings screen was stranded. The
 * rows here are declared rather than laid out, so the sections this fork adds - Jellyfin,
 * scrobbling, Spotify, Lidarr, downloads - sit alongside the rest instead of in a separate world.
 */
class SettingsFragment : Fragment() {

    private val mainActivity
        get() = requireActivity() as MainActivity

    private lateinit var builder: SettingsListBuilder

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_accord_settings, container, false)

        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            mainActivity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        // The scroll view is already constrained below the bar in the layout. Padding it by
        // the bar's height as well counted the same space twice, which is the blank band at
        // the top of this screen.
        navigationBar.attach(
            rootView.findViewById<NestedScrollView>(R.id.scrollContainer),
            applyTopPadding = false,
        )

        builder = SettingsListBuilder(rootView.findViewById<LinearLayout>(R.id.settings_rows))
        return rootView
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt here rather than once, so a row's summary - signed in or not - is right again
        // after coming back from the screen that changed it.
        builder.build(sections())
        refreshLyricIndexSummary()
    }

    /**
     * How much of the library has searchable lyrics.
     *
     * Held as a field and refreshed off the main thread, because the row is rebuilt on every
     * onResume and counting rows in the index is a database read.
     */
    private var lyricIndexSummary: CharSequence = ""

    private fun refreshLyricIndexSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val indexer = LyricsIndexer(requireContext().applicationContext)
            val indexed = indexer.indexedCount()
            val remaining = indexer.remainingCount()
            lyricIndexSummary = when {
                indexed == 0 && remaining == 0 -> getString(R.string.lyric_index_empty)
                remaining == 0 -> getString(R.string.lyric_index_complete, indexed)
                else -> getString(R.string.lyric_index_partial, indexed, remaining)
            }
            if (isAdded) builder.build(sections())
        }
    }

    /**
     * Explains when indexing happens, and offers to do it now.
     *
     * The default is to wait for a charger and wifi, which means most people will find this already
     * done and never open this dialog - but somebody who has just discovered lyric search and wants
     * it working this minute should not have to go and find a cable.
     */
    private fun showLyricIndexOptions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_lyric_search)
            .setMessage(getString(R.string.lyric_index_explain, lyricIndexSummary))
            .setPositiveButton(R.string.lyric_index_now) { _, _ ->
                LyricsIndexWorker.runNow(requireContext().applicationContext)
                toast(getString(R.string.lyric_index_started))
            }
            .setNeutralButton(R.string.lyric_index_rebuild) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    LyricsIndexer(requireContext().applicationContext).reset()
                    LyricsIndexWorker.runNow(requireContext().applicationContext)
                    toast(getString(R.string.lyric_index_started))
                    refreshLyricIndexSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** What this app is built on, and under what terms. */
    private fun showAttribution() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_open_source)
            .setMessage(
                getString(R.string.settings_attribution_body, BuildConfig.JELLYFIN_SDK_VERSION)
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun push(fragment: Fragment) {
        mainActivity.fragmentSwitcherView.addFragmentToCurrentStack(fragment)
    }

    /**
     * The photo picker. Registered as a field because a launcher has to exist before the fragment
     * reaches RESUMED, which rules out creating one when the row is tapped.
     *
     * PickVisualMedia rather than an open-document intent: it needs no storage permission at all, so
     * choosing a profile picture never asks for access to the whole gallery.
     */
    private val profilePicturePicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) uploadProfilePicture(uri) }

    private fun showProfilePictureOptions() {
        val context = requireContext()
        if (!JellyfinCredentialStore.hasStoredSession(context)) {
            toast(getString(R.string.settings_profile_picture_signed_out))
            return
        }
        val hasPicture = JellyfinUserImage.urlFlow.value != null
        val options = buildList {
            add(getString(R.string.settings_profile_picture_choose))
            if (hasPicture) add(getString(R.string.settings_profile_picture_remove))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_profile_picture)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    profilePicturePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } else {
                    removeProfilePicture()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Scales the chosen image down and sends it.
     *
     * The scaling is not cosmetic. Jellyfin stores the picture as given and hands it back unchanged
     * whatever size a client asks for, so a full-resolution photo becomes a multi-megabyte download
     * for every client that shows an avatar, forever.
     */
    private fun uploadProfilePicture(uri: Uri) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { scaleForUpload(context, uri) }
            if (bytes == null) {
                toast(getString(R.string.settings_profile_picture_unreadable))
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                JellyfinUserImage.upload(bytes, JellyfinUserImage.UPLOAD_MEDIA_TYPE)
            }
            toast(
                getString(
                    if (ok) R.string.settings_profile_picture_updated
                    else R.string.settings_profile_picture_failed
                )
            )
            if (ok && isAdded) builder.build(sections())
        }
    }

    private fun removeProfilePicture() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { JellyfinUserImage.remove() }
            toast(
                getString(
                    if (ok) R.string.settings_profile_picture_removed
                    else R.string.settings_profile_picture_failed
                )
            )
            if (ok && isAdded) builder.build(sections())
        }
    }

    /** Decodes [uri] no larger than needed and re-encodes it as JPEG. Null if it is not an image. */
    private fun scaleForUpload(context: android.content.Context, uri: Uri): ByteArray? =
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longestEdge = maxOf(info.size.width, info.size.height)
                if (longestEdge > JellyfinUserImage.MAX_EDGE_PX) {
                    val scale = JellyfinUserImage.MAX_EDGE_PX.toFloat() / longestEdge
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
                // The upload is re-encoded, so a software bitmap is required - a hardware one has no
                // pixels this process can read.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JellyfinUserImage.UPLOAD_QUALITY, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }.getOrNull()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun sections(): List<SettingsListBuilder.Section> {
        val context = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val jellyfinSignedIn = JellyfinCredentialStore.hasStoredSession(context)

        return listOf(
            SettingsListBuilder.Section(
                title = getString(R.string.settings_section_library),
                rows = listOf(
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.jellyfin_login_title),
                        summary = getString(
                            if (jellyfinSignedIn) R.string.setup_jellyfin_server_connected
                            else R.string.setup_jellyfin_account_desc
                        )
                    ) {
                        startActivity(Intent(context, JellyfinLoginActivity::class.java))
                    },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_profile_picture),
                        summary = getString(
                            if (JellyfinUserImage.urlFlow.value != null) {
                                R.string.settings_profile_picture_set
                            } else {
                                R.string.settings_profile_picture_none
                            }
                        )
                    ) { showProfilePictureOptions() },
                    SettingsListBuilder.Row.Toggle(
                        title = getString(R.string.settings_sync_on_startup),
                        checked = prefs.getBoolean("sync_on_startup", false)
                    ) { prefs.edit().putBoolean("sync_on_startup", it).apply() },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_blacklist)
                    ) { push(BlacklistSettingsFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_category_downloads)
                    ) { push(DownloadsSettingsFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_import_play_counts),
                        summary = getString(R.string.settings_import_play_counts_summary)
                    ) { push(PlayCountImportFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_lyric_search),
                        summary = lyricIndexSummary
                    ) { showLyricIndexOptions() },
                ),
                footer = getString(R.string.settings_sync_on_startup_summary)
            ),
            SettingsListBuilder.Section(
                title = getString(R.string.settings_section_services),
                rows = listOf(
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_category_scrobbling)
                    ) { push(ScrobblingSettingsFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_category_spotify)
                    ) { push(SpotifySettingsFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_category_lidarr)
                    ) { push(LidarrSettingsFragment()) },
                )
            ),
            SettingsListBuilder.Section(
                title = getString(R.string.settings_section_appearance),
                rows = listOf(
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_category_appearance)
                    ) { push(AppearanceSettingsFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_category_behavior)
                    ) { push(BehaviorSettingsFragment()) },
                )
            ),
            SettingsListBuilder.Section(
                title = getString(R.string.settings_section_audio),
                rows = listOf(
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_audio)
                    ) { push(AudioSettingsFragment()) },
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_experimental_settings)
                    ) { push(ExperimentalSettingsFragment()) },
                )
            ),
            SettingsListBuilder.Section(
                title = getString(R.string.settings_section_about),
                rows = listOf(
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_version),
                        summary = BuildConfig.MY_VERSION_NAME
                    ) { /* Nothing to open yet. */ },
                    // Credited because the app is built on it, and because the SDK's licence and
                    // the project's own ask for it. A version is included: "which Jellyfin SDK"
                    // is the first question any bug report against this app has to answer.
                    SettingsListBuilder.Row.Navigation(
                        title = getString(R.string.settings_open_source),
                        summary = getString(
                            R.string.settings_open_source_summary,
                            BuildConfig.JELLYFIN_SDK_VERSION,
                        )
                    ) { showAttribution() },
                )
            ),
        )
    }
}
