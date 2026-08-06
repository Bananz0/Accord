package org.akanework.gramophone.logic.data.jellyfin

import android.util.Log
import androidx.annotation.WorkerThread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * What the Jellyfin server has installed, so the app can avoid duplicating it.
 *
 * The only question asked so far is whether the server scrobbles to Last.fm itself. If it does, the
 * app reporting playback to the server is already enough to get a scrobble, and signing in to
 * Last.fm here as well means every track is scrobbled twice - which is not something the user finds
 * out until their profile is full of duplicates.
 *
 * No server-side plugin of our own is involved: `/Plugins` is a plain authenticated read that the
 * server already answers.
 */
object JellyfinPlugins {

    /**
     * Whether the server's Last.fm plugin is installed and running.
     *
     * Three-valued on purpose. Null means "could not tell" - signed out, offline, or a server that
     * refuses the plugin list to non-administrators - and a warning must not be shown on a guess.
     */
    @WorkerThread
    fun isLastFmScrobblingActive(): Boolean? {
        cached?.let { return it }
        val credentials = JellyfinClientHolder.credentials
        val server = credentials.serverUrl?.trimEnd('/') ?: return null
        val token = credentials.accessToken ?: return null

        val request = Request.Builder()
            .url("$server/Plugins")
            .header("Authorization", "MediaBrowser Token=\"$token\"")
            .build()
        val body = runCatching {
            CLIENT.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else {
                    // 403 on a non-administrator is the expected answer on some servers, not a bug.
                    Log.d(TAG, "Plugin list unavailable: HTTP ${response.code}")
                    null
                }
            }
        }.getOrElse {
            Log.d(TAG, "Plugin list unavailable: $it")
            null
        } ?: return null

        return runCatching {
            val plugins = JSONArray(body)
            (0 until plugins.length()).any { index ->
                val plugin = plugins.optJSONObject(index) ?: return@any false
                val name = plugin.optString("Name").lowercase()
                if (LASTFM_NAMES.none { name.contains(it) }) return@any false
                // A plugin can be present but switched off, or superseded by a newer copy of
                // itself that has not been loaded yet; neither of those scrobbles anything.
                plugin.optString("Status").equals("Active", ignoreCase = true)
            }
        }.getOrElse {
            Log.d(TAG, "Could not read the plugin list: $it")
            null
        }?.also { cached = it }
    }

    /** Forget the answer, for when the user signs in to a different server. */
    fun invalidate() {
        cached = null
    }

    /**
     * Held for the process's lifetime. Plugins are not installed while someone is looking at a
     * settings screen, and the alternative is a network call every time the screen is opened.
     */
    @Volatile
    private var cached: Boolean? = null

    /** The plugin has been spelled both ways across its releases. */
    private val LASTFM_NAMES = listOf("last.fm", "lastfm")

    private const val TAG = "JellyfinPlugins"

    private val CLIENT: OkHttpClient get() = JellyfinClientHolder.apiHttpClient()
}
