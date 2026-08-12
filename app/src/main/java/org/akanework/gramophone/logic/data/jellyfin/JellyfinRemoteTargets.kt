package org.akanework.gramophone.logic.data.jellyfin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlaystateCommand
import java.util.UUID

/**
 * The other Jellyfin clients this one can play to.
 *
 * The sending half of remote control, and the mirror of [JellyfinRemoteControl]: that one lets
 * other clients drive Accord, this one lets Accord drive them. Both sides are the server's, so a
 * device only has to be signed in to the same Jellyfin to appear here - the web client on a
 * desktop, another phone, a Jellyfin Media Player on a TV.
 *
 * Deliberately a small piece of state rather than a mode. Handing playback to another device does
 * not change what this app is: the queue, the library and the now-playing screen are unchanged, and
 * only where the sound comes out moves. That is also why the player shows it in the same place it
 * shows a pair of headphones.
 */
object JellyfinRemoteTargets {

    private const val TAG = "JellyfinRemoteTargets"

    /**
     * How stale a session may be before it is not worth offering.
     *
     * Jellyfin keeps sessions around after a client has gone quiet, so listing everything shows
     * devices that stopped existing hours ago - and a "Play on" that reaches nothing is worse than
     * a short list.
     */
    private const val MAX_IDLE_MINUTES = 10L

    data class Target(
        val sessionId: String,
        /** The client application - "Jellyfin Web", "Findroid", "Accord". */
        val client: String,
        /** The machine it is running on, which is what tells two of the same client apart. */
        val deviceName: String,
        val nowPlaying: String?,
    )

    private val _active = MutableStateFlow<Target?>(null)

    /** The device currently being played to, or null when playback is local. */
    val active: StateFlow<Target?> = _active.asStateFlow()

    /**
     * Controllable sessions, this one excluded.
     *
     * Filtered to those that said they support media control, because the rest cannot be played to
     * and listing them would offer a choice that fails.
     */
    suspend fun available(): List<Target> = withContext(Dispatchers.IO) {
        val api = JellyfinClientHolder.api() ?: return@withContext emptyList()
        try {
            val ownDeviceId = api.deviceInfo.id
            // controllableByUserId is not optional in practice. Without it the server answers with
            // this client's own session and nothing else - a non-administrator is not allowed to
            // enumerate sessions, only to ask which ones they may control. That is why the picker
            // listed every device when queried with an admin key and none from inside the app.
            val userId = JellyfinClientHolder.credentials.userId
                ?.let { runCatching { UUID.fromString(it.toDashedUuid()) }.getOrNull() }
            api.sessionApi.getSessions(
                controllableByUserId = userId,
                activeWithinSeconds = (MAX_IDLE_MINUTES * 60).toInt(),
            )
                .content
                .filter { it.supportsRemoteControl == true }
                .filter { it.deviceId != ownDeviceId }
                .filter { !it.client.isNullOrBlank() }
                .map { session ->
                    Target(
                        sessionId = session.id.orEmpty(),
                        client = session.client.orEmpty(),
                        deviceName = session.deviceName.orEmpty(),
                        nowPlaying = session.nowPlayingItem?.name,
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list sessions", e)
            emptyList()
        }
    }

    /**
     * Sends [remoteIds] to [target] and makes it the active device.
     *
     * The ids go over as Jellyfin's own, not this app's local ones: the receiving client will look
     * them up against the same server, and has never heard of our row numbers.
     */
    suspend fun playOn(
        target: Target,
        remoteIds: List<String>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        val api = JellyfinClientHolder.api() ?: return@withContext false
        val ids = remoteIds.mapNotNull { runCatching { UUID.fromString(it.toDashedUuid()) }.getOrNull() }
        if (ids.isEmpty()) return@withContext false
        try {
            api.sessionApi.play(
                sessionId = target.sessionId,
                playCommand = PlayCommand.PLAY_NOW,
                itemIds = ids,
                startIndex = startIndex,
                startPositionTicks = startPositionMs * TICKS_PER_MILLISECOND,
            )
            _active.value = target
            Log.d(TAG, "Handed ${ids.size} items to ${target.client} on ${target.deviceName}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not play on ${target.client}", e)
            false
        }
    }

    /** Transport for the device being played to. Silently does nothing when playback is local. */
    suspend fun sendTransport(command: PlaystateCommand, seekPositionMs: Long? = null) {
        val target = _active.value ?: return
        val api = JellyfinClientHolder.api() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                api.sessionApi.sendPlaystateCommand(
                    sessionId = target.sessionId,
                    command = command,
                    seekPositionTicks = seekPositionMs?.times(TICKS_PER_MILLISECOND),
                )
            }.onFailure { Log.w(TAG, "Transport $command failed", it) }
        }
    }

    suspend fun sendVolume(percent: Int) {
        val target = _active.value ?: return
        val api = JellyfinClientHolder.api() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                api.sessionApi.sendFullGeneralCommand(
                    sessionId = target.sessionId,
                    data = org.jellyfin.sdk.model.api.GeneralCommand(
                        name = GeneralCommandType.SET_VOLUME,
                        // The server fills this in from the token; sending a zero UUID is
                        // how the SDK's own callers say "whoever I am".
                        controllingUserId = UUID(0, 0),
                        arguments = mapOf("Volume" to percent.coerceIn(0, 100).toString()),
                    ),
                )
            }.onFailure { Log.w(TAG, "Volume failed", it) }
        }
    }

    /**
     * Brings playback back to this device.
     *
     * Only forgets the target; it does not stop the other device. Whether handing playback back
     * should also silence what is playing over there is a decision for the screen that offers it,
     * not for this.
     */
    fun playLocally() {
        _active.value = null
    }

    private fun String.toDashedUuid(): String {
        if (length != 32) return this
        return buildString(36) {
            append(this@toDashedUuid, 0, 8).append('-')
            append(this@toDashedUuid, 8, 12).append('-')
            append(this@toDashedUuid, 12, 16).append('-')
            append(this@toDashedUuid, 16, 20).append('-')
            append(this@toDashedUuid, 20, 32)
        }
    }

    private const val TICKS_PER_MILLISECOND = 10_000L
}
