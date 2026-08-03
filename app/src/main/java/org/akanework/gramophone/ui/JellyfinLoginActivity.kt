package org.akanework.gramophone.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.userApi

/**
 * First-run sign in. Stores the resulting token in the credential store and hands control back to
 * [MainActivity], which then syncs the library.
 */
class JellyfinLoginActivity : AppCompatActivity() {

    private lateinit var serverUrlField: TextInputEditText
    private lateinit var usernameField: TextInputEditText
    private lateinit var passwordField: TextInputEditText
    private lateinit var signInButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin_login)

        serverUrlField = findViewById(R.id.server_url)
        usernameField = findViewById(R.id.username)
        passwordField = findViewById(R.id.password)
        signInButton = findViewById(R.id.sign_in)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        signInButton.setOnClickListener { signIn() }
    }

    private fun signIn() {
        val rawUrl = serverUrlField.text?.toString()?.trim().orEmpty()
        val username = usernameField.text?.toString()?.trim().orEmpty()
        val password = passwordField.text?.toString().orEmpty()

        if (rawUrl.isEmpty()) {
            showError(getString(R.string.jellyfin_error_no_server))
            return
        }
        if (username.isEmpty()) {
            showError(getString(R.string.jellyfin_error_no_username))
            return
        }
        val serverUrl = normaliseUrl(rawUrl)
        if (!hasHost(serverUrl)) {
            showError(getString(R.string.jellyfin_error_bad_address))
            return
        }

        setBusy(true)
        status.visibility = View.VISIBLE
        status.text = getString(R.string.jellyfin_signing_in)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                authenticate(serverUrl, username, password)
            }
            when (result) {
                is LoginResult.Success -> {
                    startActivity(Intent(this@JellyfinLoginActivity, MainActivity::class.java))
                    finish()
                }

                is LoginResult.Failure -> {
                    setBusy(false)
                    showError(result.message)
                }
            }
        }
    }

    private suspend fun authenticate(
        serverUrl: String,
        username: String,
        password: String
    ): LoginResult = try {
        val api = JellyfinClientHolder.createUnauthenticatedApi(serverUrl)
        val result by api.userApi.authenticateUserByName(username, password)
        val token = result.accessToken
        val userId = result.user?.id?.toString()
        if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
            LoginResult.Failure(getString(R.string.jellyfin_error_credentials))
        } else {
            JellyfinClientHolder.credentials.saveSession(
                context = this,
                serverUrl = serverUrl,
                accessToken = token,
                userId = userId,
                serverName = result.serverId,
            )
            // Drop any client built against the previous session.
            JellyfinClientHolder.invalidate()
            LoginResult.Success
        }
    } catch (e: TimeoutException) {
        Log.w(TAG, "Timed out reaching $serverUrl", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_unreachable))
    } catch (e: InvalidStatusException) {
        Log.w(TAG, "Server rejected sign in with HTTP ${e.status}", e)
        // Only an auth status actually means the credentials were wrong. Everything else (a
        // reverse proxy 502, a wrong path, a server still starting up) used to be reported as a
        // bad password, which sends people off checking the one thing that was fine.
        if (e.status == 401 || e.status == 403) {
            LoginResult.Failure(getString(R.string.jellyfin_error_credentials))
        } else {
            LoginResult.Failure(getString(R.string.jellyfin_error_http_status, e.status))
        }
    } catch (e: SecureConnectionException) {
        Log.w(TAG, "TLS problem talking to $serverUrl", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_tls))
    } catch (e: ApiClientException) {
        Log.w(TAG, "Could not sign in to $serverUrl", e)
        // Surface what actually went wrong rather than guessing; the cause carries the useful
        // detail (unknown host, connection refused, cleartext blocked).
        val detail = (e.cause ?: e).let { it.message ?: it::class.java.simpleName }
        LoginResult.Failure(getString(R.string.jellyfin_error_detail, detail))
    } catch (e: Exception) {
        // The SDK builds the URL before it does any I/O and throws plain IllegalArgumentException
        // for a malformed address ("Invalid URL host"). That is not an ApiClientException, so
        // without this catch a typo in the server field takes the whole process down.
        Log.w(TAG, "Unexpected failure signing in to $serverUrl", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_bad_address))
    }

    /**
     * Accepts what people actually type - "jellyfin.example.com", "192.168.1.5:8096" - and turns it
     * into something the SDK can use.
     */
    private fun normaliseUrl(input: String): String {
        val withScheme =
            if (input.startsWith("http://") || input.startsWith("https://")) input
            else "http://$input"
        return withScheme.trimEnd('/')
    }

    /** True if [url] actually has a host, so the SDK will not throw building a request from it. */
    private fun hasHost(url: String): Boolean =
        runCatching { url.toUri().host?.isNotBlank() == true }.getOrDefault(false)

    private fun setBusy(busy: Boolean) {
        signInButton.isEnabled = !busy
        serverUrlField.isEnabled = !busy
        usernameField.isEnabled = !busy
        passwordField.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        status.visibility = View.VISIBLE
        status.text = message
    }

    private sealed interface LoginResult {
        data object Success : LoginResult
        data class Failure(val message: String) : LoginResult
    }

    private companion object {
        const val TAG = "JellyfinLogin"
    }
}
