package org.akanework.gramophone.ui.fragments.settings


import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.logic.data.jellyfin.JellyfinMediaCache
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class DownloadsSettingsFragment : BaseSettingFragment(
    R.string.settings_category_downloads,
    { DownloadsSettingsTopFragment() }
)

/**
 * Shows how much space offline music takes and offers to reclaim it.
 *
 * Two figures rather than one, because they answer different questions: downloads are what the user
 * deliberately stored, while the cache total also includes whatever streaming happened to leave
 * behind. Clearing downloads only removes the former.
 */
class DownloadsSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_downloads, rootKey)
    }

    override fun onResume() {
        super.onResume()
        refreshSizes()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "downloads_clear") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.downloads_clear)
                .setMessage(R.string.downloads_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.downloads_clear) { _, _ -> clearDownloads() }
                .show()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSizes() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Both figures read the media3 index and the cache directory, which is disk I/O.
            val sizes = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.downloadedBytes(requireContext()) to
                        JellyfinMediaCache.currentSizeBytes(requireContext())
            }
            if (!isAdded) return@launch
            findPreference<Preference>("downloads_size")?.summary = getString(
                R.string.downloads_size_summary,
                Formatter.formatFileSize(requireContext(), sizes.first)
            )
            findPreference<Preference>("downloads_cache_size")?.summary = getString(
                R.string.downloads_cache_size_summary,
                Formatter.formatFileSize(requireContext(), sizes.second)
            )
        }
    }

    private fun clearDownloads() {
        JellyfinDownloadManager.removeAll(requireContext())
        Toast.makeText(requireContext(), R.string.downloads_cleared, Toast.LENGTH_SHORT).show()
        // Removal runs in the download service, so the figures only settle a moment later. Refresh
        // on the next resume rather than reporting a stale number now.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            if (isAdded) refreshSizes()
        }
    }
}
