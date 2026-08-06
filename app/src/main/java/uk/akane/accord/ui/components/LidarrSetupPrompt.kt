package uk.akane.accord.ui.components

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import uk.akane.accord.R

/**
 * Asks for the Lidarr settings a request needs, at the moment it needs them.
 *
 * Lidarr will not accept an album without a root folder, a quality profile and a metadata profile.
 * Being told to go to the settings screen and find three fields is a worse answer than being asked
 * for them, particularly when Lidarr can say what the choices are.
 *
 * Everything offered here is read from the server, and where there is only one sensible answer it is
 * taken without asking - a single root folder is not a decision.
 */
object LidarrSetupPrompt {

    /**
     * Makes sure the defaults are set, prompting if they are not.
     *
     * @param onReady run once everything needed is stored - the request that triggered this can then
     *   go ahead. Not called if the user backs out.
     */
    fun ensureConfigured(
        context: Context,
        owner: LifecycleOwner,
        onReady: () -> Unit,
    ) {
        val store = LidarrCredentialStore(context)
        if (store.isConfigured()) {
            onReady()
            return
        }
        if (store.serverUrl.isNullOrBlank() || store.apiKey.isNullOrBlank()) {
            // Nothing can be fetched without an address and a key, and those cannot be guessed.
            Toast.makeText(context, R.string.requests_no_lidarr, Toast.LENGTH_LONG).show()
            return
        }

        owner.lifecycleScope.launch {
            val client = LidarrClient(store)
            val options = withContext(Dispatchers.IO) {
                runCatching {
                    Options(
                        rootFolders = client.rootFolders(),
                        quality = client.qualityProfiles(),
                        metadata = client.metadataProfiles(),
                    )
                }.getOrNull()
            }
            if (options == null) {
                Toast.makeText(context, R.string.lidarr_setup_unreachable, Toast.LENGTH_LONG).show()
                return@launch
            }
            askRootFolder(context, store, options, onReady)
        }
    }

    private class Options(
        val rootFolders: List<LidarrClient.RootFolder>,
        val quality: List<LidarrClient.Profile>,
        val metadata: List<LidarrClient.Profile>,
    )

    private fun askRootFolder(
        context: Context,
        store: LidarrCredentialStore,
        options: Options,
        onReady: () -> Unit,
    ) {
        val current = store.rootFolderPath
        if (!current.isNullOrBlank()) {
            askQuality(context, store, options, onReady)
            return
        }
        val folders = options.rootFolders
        if (folders.isEmpty()) {
            Toast.makeText(context, R.string.lidarr_setup_no_root, Toast.LENGTH_LONG).show()
            return
        }
        if (folders.size == 1) {
            // One option is not a choice; taking it silently is what the user would have done.
            store.rootFolderPath = folders.first().path
            askQuality(context, store, options, onReady)
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lidarr_setup_root_title)
            .setItems(folders.map { it.path }.toTypedArray()) { _, which ->
                store.rootFolderPath = folders[which].path
                askQuality(context, store, options, onReady)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askQuality(
        context: Context,
        store: LidarrCredentialStore,
        options: Options,
        onReady: () -> Unit,
    ) {
        if (store.qualityProfileId > 0) {
            askMetadata(context, store, options, onReady)
            return
        }
        pickProfile(
            context,
            R.string.lidarr_setup_quality_title,
            options.quality,
        ) { profile ->
            store.qualityProfileId = profile.id
            store.qualityProfileName = profile.name
            askMetadata(context, store, options, onReady)
        }
    }

    private fun askMetadata(
        context: Context,
        store: LidarrCredentialStore,
        options: Options,
        onReady: () -> Unit,
    ) {
        if (store.metadataProfileId > 0) {
            finish(context, store, onReady)
            return
        }
        pickProfile(
            context,
            R.string.lidarr_setup_metadata_title,
            options.metadata,
        ) { profile ->
            store.metadataProfileId = profile.id
            store.metadataProfileName = profile.name
            finish(context, store, onReady)
        }
    }

    private fun pickProfile(
        context: Context,
        titleRes: Int,
        profiles: List<LidarrClient.Profile>,
        onPicked: (LidarrClient.Profile) -> Unit,
    ) {
        if (profiles.isEmpty()) {
            Toast.makeText(context, R.string.lidarr_setup_no_profiles, Toast.LENGTH_LONG).show()
            return
        }
        if (profiles.size == 1) {
            onPicked(profiles.first())
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
                onPicked(profiles[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finish(context: Context, store: LidarrCredentialStore, onReady: () -> Unit) {
        if (!store.isConfigured()) {
            Toast.makeText(context, R.string.lidarr_setup_incomplete, Toast.LENGTH_LONG).show()
            return
        }
        onReady()
    }
}
