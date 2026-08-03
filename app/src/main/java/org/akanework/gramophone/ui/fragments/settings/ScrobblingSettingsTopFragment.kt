package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import org.akanework.gramophone.logic.data.lastfm.LastFmScrobbler
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class ScrobblingSettingsFragment : BaseSettingFragment(
    R.string.settings_category_scrobbling,
    { ScrobblingSettingsTopFragment() }
)

/**
 * Connects the app to Last.fm and shows the state of the scrobble queue.
 *
 * Every credential-store read touches keystore-backed preferences, which is disk I/O, so all of it
 * happens off the main thread and the summaries are filled in when it returns. That is also why the
 * screen refreshes its own summaries rather than binding them declaratively.
 */
class ScrobblingSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_scrobbling, rootKey)
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "lastfm_account" -> onAccountClicked()
            "lastfm_api_keys" -> showApiKeyDialog()
            "lastfm_pending" -> submitPendingNow()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSummaries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val store = LastFmCredentialStore(requireContext())
                AccountState(
                    username = store.username,
                    isLinked = store.isLinked(),
                    hasKeys = store.hasApplicationCredentials(),
                    pending = LastFmScrobbler(requireContext()).pendingCount(),
                )
            }
            if (!isAdded) return@launch
            findPreference<Preference>("lastfm_account")?.summary = when {
                !state.hasKeys -> getString(R.string.lastfm_account_needs_keys)
                state.isLinked -> getString(R.string.lastfm_account_linked, state.username ?: "")
                else -> getString(R.string.lastfm_account_not_linked)
            }
            findPreference<Preference>("lastfm_pending")?.summary =
                resources.getQuantityString(
                    R.plurals.lastfm_pending_count, state.pending, state.pending
                )
        }
    }

    private fun onAccountClicked() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = withContext(Dispatchers.IO) { LastFmCredentialStore(requireContext()) }
            val linked = withContext(Dispatchers.IO) { store.isLinked() }
            val hasKeys = withContext(Dispatchers.IO) { store.hasApplicationCredentials() }
            if (!isAdded) return@launch
            when {
                // Signing in is impossible without an application key pair, so send the user
                // straight to where they can add one instead of failing at the password step.
                !hasKeys -> showApiKeyDialog()
                linked -> showDisconnectDialog()
                else -> showLoginDialog()
            }
        }
    }

    private fun showLoginDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lastfm_login, null)
        val usernameField = view.findViewById<TextInputEditText>(R.id.username)
        val passwordField = view.findViewById<TextInputEditText>(R.id.password)
        val status = view.findViewById<TextView>(R.id.status)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lastfm_connect_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            // Set with a null listener and rebound below, so a failed attempt keeps the dialog open
            // with the typed username still in place instead of dismissing on every tap.
            .setPositiveButton(R.string.lastfm_connect, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val username = usernameField.text?.toString()?.trim().orEmpty()
                    val password = passwordField.text?.toString().orEmpty()
                    if (username.isEmpty() || password.isEmpty()) {
                        status.visibility = View.VISIBLE
                        status.setText(R.string.lastfm_error_empty)
                        return@setOnClickListener
                    }
                    status.visibility = View.VISIBLE
                    status.setText(R.string.lastfm_connecting)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) { link(username, password) }
                        if (!isAdded) return@launch
                        if (result == null) {
                            dialog.dismiss()
                            refreshSummaries()
                            Toast.makeText(
                                requireContext(), R.string.lastfm_connected, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            status.text = result
                        }
                    }
                }
        }
        dialog.show()
    }

    /** Returns null on success, or a message to show in the dialog. */
    private suspend fun link(username: String, password: String): String? = try {
        val store = LastFmCredentialStore(requireContext())
        val client = LastFmClient(store.apiKey, store.apiSecret)
        val session = client.getMobileSession(username, password)
        store.saveSession(requireContext(), session.name, session.key)
        // A queue left behind by a previous session - or by an outage before the account was
        // unlinked - can be submitted now that there is a session key again.
        LastFmScrobbler(requireContext()).flushAsync()
        null
    } catch (e: LastFmClient.LastFmException) {
        e.message ?: getString(R.string.lastfm_error_generic)
    } catch (e: Exception) {
        getString(R.string.lastfm_error_generic)
    }

    private fun showDisconnectDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val username = withContext(Dispatchers.IO) {
                LastFmCredentialStore(requireContext()).username
            }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lastfm_account)
                .setMessage(getString(R.string.lastfm_disconnect_message, username ?: ""))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lastfm_disconnect) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            LastFmCredentialStore(requireContext()).clearSession(requireContext())
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun showApiKeyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lastfm_api_keys, null)
        val keyField = view.findViewById<TextInputEditText>(R.id.api_key)
        val secretField = view.findViewById<TextInputEditText>(R.id.api_secret)

        viewLifecycleOwner.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                val store = LastFmCredentialStore(requireContext())
                store.apiKey to store.apiSecret
            }
            if (!isAdded) return@launch
            keyField.setText(existing.first)
            secretField.setText(existing.second)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lastfm_api_keys)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val key = keyField.text?.toString()?.trim().orEmpty()
                    val secret = secretField.text?.toString()?.trim().orEmpty()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val store = LastFmCredentialStore(requireContext())
                            store.apiKey = key
                            store.apiSecret = secret
                            // The "linked" flag depends on the keys being usable, so it has to be
                            // recomputed whenever they change.
                            store.publishLinkFlag(requireContext())
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun submitPendingNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            val submitted = withContext(Dispatchers.IO) {
                LastFmScrobbler(requireContext()).flush()
            }
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(
                    R.plurals.lastfm_submitted_count, submitted, submitted
                ),
                Toast.LENGTH_SHORT
            ).show()
            refreshSummaries()
        }
    }

    private data class AccountState(
        val username: String?,
        val isLinked: Boolean,
        val hasKeys: Boolean,
        val pending: Int,
    )
}
