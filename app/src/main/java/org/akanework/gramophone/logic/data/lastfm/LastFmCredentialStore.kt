package org.akanework.gramophone.logic.data.lastfm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.akanework.gramophone.BuildConfig

/**
 * Stores the Last.fm application credentials and the user's session key.
 *
 * A Last.fm session key never expires and authorises writes to the account, so it is kept in
 * [EncryptedSharedPreferences] next to the Jellyfin token, with the same plaintext fallback for
 * devices whose keystore refuses to open - refusing to scrobble is better than refusing to start.
 *
 * The API key and secret identify the *application*, not the user. Accord ships without a pair,
 * because a key checked into an open-source client is a key anyone can extract and get rate-limited
 * or banned on behalf of every user. They can be supplied at build time through
 * `package.properties`, or pasted into the settings screen at runtime, which is the path most people
 * will take.
 */
class LastFmCredentialStore(context: Context) {

    private val prefs: SharedPreferences = openPreferences(context)

    /** Runtime override; falls back to whatever the build was configured with. */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.LASTFM_API_KEY
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var apiSecret: String
        get() = prefs.getString(KEY_API_SECRET, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.LASTFM_API_SECRET
        set(value) = prefs.edit().putString(KEY_API_SECRET, value.trim()).apply()

    var sessionKey: String?
        get() = prefs.getString(KEY_SESSION_KEY, null)
        private set(value) = prefs.edit().putString(KEY_SESSION_KEY, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        private set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    /** True once the app can act on the user's behalf. */
    fun isLinked(): Boolean = !sessionKey.isNullOrBlank() && hasApplicationCredentials()

    /** True once an API key/secret pair exists, whether from the build or the settings screen. */
    fun hasApplicationCredentials(): Boolean = apiKey.isNotBlank() && apiSecret.isNotBlank()

    /**
     * Persists a session obtained from `auth.getMobileSession`.
     *
     * Uses commit() for the same reason the Jellyfin store does: apply() flushes asynchronously, and
     * this app's crash handler calls exitProcess(), which would silently discard the write and leave
     * the user apparently unlinked.
     */
    fun saveSession(context: Context, username: String, sessionKey: String) {
        @Suppress("ApplySharedPref")
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_SESSION_KEY, sessionKey)
            .commit()
        publishLinkFlag(context)
    }

    fun clearSession(context: Context) {
        @Suppress("ApplySharedPref")
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_SESSION_KEY)
            .commit()
        publishLinkFlag(context)
    }

    /**
     * Mirrors "linked" into ordinary preferences.
     *
     * The playback service consults this on every track change. Opening keystore-backed preferences
     * there would be disk I/O on the main thread, which debug builds kill the process for.
     */
    fun publishLinkFlag(context: Context) {
        @Suppress("ApplySharedPref")
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putBoolean(KEY_IS_LINKED, isLinked())
            .commit()
    }

    private fun openPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted preferences unavailable, falling back to plaintext", e)
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "LastFmCredentialStore"
        private const val ENCRYPTED_PREFS_NAME = "lastfm_credentials"
        private const val FALLBACK_PREFS_NAME = "lastfm_credentials_plain"

        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_SECRET = "api_secret"
        private const val KEY_SESSION_KEY = "session_key"
        private const val KEY_USERNAME = "username"

        /** Lives in the default (unencrypted) preferences - it is only a boolean. */
        private const val KEY_IS_LINKED = "lastfm_is_linked"

        /** The user's on/off switch, independent of whether an account is linked. */
        const val KEY_SCROBBLING_ENABLED = "lastfm_scrobbling_enabled"

        /**
         * Whether an account is linked, cheap enough for the playback service's hot path.
         */
        fun isLinked(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(KEY_IS_LINKED, false)

        /** Whether scrobbling should actually happen right now. */
        fun isScrobblingEnabled(context: Context): Boolean =
            isLinked(context) && PreferenceManager
                .getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(KEY_SCROBBLING_ENABLED, true)
    }
}
