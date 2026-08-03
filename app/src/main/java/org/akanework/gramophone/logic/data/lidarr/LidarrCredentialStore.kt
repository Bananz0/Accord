package org.akanework.gramophone.logic.data.lidarr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the address and API key of the user's Lidarr instance.
 *
 * A Lidarr API key is full control of the instance - it can add, delete and re-download anything -
 * so it lives in [EncryptedSharedPreferences] alongside the other tokens, with the same plaintext
 * fallback for devices whose keystore will not open.
 *
 * Unlike Last.fm and Spotify, there is nothing to register: Lidarr is the user's own server, and
 * every user supplies their own address. There is no shared credential and no quota to apply for.
 */
class LidarrCredentialStore(context: Context) {

    private val prefs: SharedPreferences = openPreferences(context)

    /** Base address, without a trailing slash. */
    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit()
            .putString(KEY_SERVER_URL, value?.trim()?.trimEnd('/'))
            .apply()

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_API_KEY, value?.trim()).apply()

    /**
     * Where Lidarr should put new music, and how it should grade it.
     *
     * Lidarr rejects an add that does not name all three, and the valid values differ per instance,
     * so they are fetched from the server and chosen once rather than guessed.
     */
    var rootFolderPath: String?
        get() = prefs.getString(KEY_ROOT_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_ROOT_FOLDER, value).apply()

    var qualityProfileId: Int
        get() = prefs.getInt(KEY_QUALITY_PROFILE, 0)
        set(value) = prefs.edit().putInt(KEY_QUALITY_PROFILE, value).apply()

    var metadataProfileId: Int
        get() = prefs.getInt(KEY_METADATA_PROFILE, 0)
        set(value) = prefs.edit().putInt(KEY_METADATA_PROFILE, value).apply()

    /**
     * The chosen profiles' names, kept only so settings can say "Standard" rather than "3". Lidarr
     * identifies profiles by id, and an id on its own tells the user nothing about what they picked.
     */
    var qualityProfileName: String?
        get() = prefs.getString(KEY_QUALITY_PROFILE_NAME, null)
        set(value) = prefs.edit().putString(KEY_QUALITY_PROFILE_NAME, value).apply()

    var metadataProfileName: String?
        get() = prefs.getString(KEY_METADATA_PROFILE_NAME, null)
        set(value) = prefs.edit().putString(KEY_METADATA_PROFILE_NAME, value).apply()

    fun isConfigured(): Boolean =
        !serverUrl.isNullOrBlank() && !apiKey.isNullOrBlank() &&
                !rootFolderPath.isNullOrBlank() && qualityProfileId > 0 && metadataProfileId > 0

    fun clear(context: Context) {
        @Suppress("ApplySharedPref")
        prefs.edit().clear().commit()
        publishConfiguredFlag(context)
    }

    /**
     * Mirrors "configured" into ordinary preferences so menus can decide whether to offer a request
     * without opening keystore-backed preferences on the main thread.
     */
    fun publishConfiguredFlag(context: Context) {
        @Suppress("ApplySharedPref")
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putBoolean(KEY_IS_CONFIGURED, isConfigured())
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
        private const val TAG = "LidarrCredentialStore"
        private const val ENCRYPTED_PREFS_NAME = "lidarr_credentials"
        private const val FALLBACK_PREFS_NAME = "lidarr_credentials_plain"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ROOT_FOLDER = "root_folder"
        private const val KEY_QUALITY_PROFILE = "quality_profile"
        private const val KEY_METADATA_PROFILE = "metadata_profile"
        private const val KEY_QUALITY_PROFILE_NAME = "quality_profile_name"
        private const val KEY_METADATA_PROFILE_NAME = "metadata_profile_name"

        private const val KEY_IS_CONFIGURED = "lidarr_is_configured"

        fun isConfigured(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(KEY_IS_CONFIGURED, false)
    }
}
