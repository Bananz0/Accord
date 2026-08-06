package org.akanework.gramophone.logic.utils

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import uk.akane.accord.R

/**
 * What the music is coming out of, for the line under the player's controls.
 *
 * Upstream draws a fixed AirPlay glyph there whatever is connected, so a pair of earbuds and the
 * phone's own speaker look identical. This reports the actual output and names it.
 *
 * Android has no public "which device is this stream routed to" call, so the answer is inferred from
 * what is connected, in the order the system itself prefers: a Bluetooth headset wins over a wired
 * one, a wired one wins over the speaker. That matches the routing in every case a music app meets;
 * it would only be wrong for an app that has explicitly overridden routing, which this one never
 * does.
 */
object AudioOutput {

    data class Device(
        /** What to show under the icon: "Galaxy Buds3 Pro". Null for the phone's own speaker. */
        val name: String?,
        @param:DrawableRes val icon: Int,
        /** False for the built-in speaker, where naming the device adds nothing. */
        val isExternal: Boolean,
    )

    /** The output the next note will come out of. */
    fun current(context: Context): Device {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Highest priority first. getDevices() returns them in no meaningful order, so the choice
        // has to be made here rather than by taking the first entry.
        val device = PRIORITY.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        } ?: return Device(null, R.drawable.ic_airplay_radio, isExternal = false)

        val name = device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return Device(name, iconFor(device, name), isExternal = device.type !in BUILT_IN)
    }

    /**
     * Calls [onChanged] whenever something is plugged in or unplugged. Returns a handle to pass to
     * [unregister] - the callback outlives the view otherwise and keeps it alive.
     */
    fun register(context: Context, onChanged: () -> Unit): AudioDeviceCallback {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChanged()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unregister(context: Context, callback: AudioDeviceCallback) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    /**
     * Picks the glyph. Earbuds are told apart from over-ear headphones by name, because Android
     * reports both as BLUETOOTH_A2DP and nothing in [AudioDeviceInfo] distinguishes them.
     */
    @DrawableRes
    private fun iconFor(device: AudioDeviceInfo, name: String?): Int {
        val lowered = name?.lowercase().orEmpty()
        return when {
            EARBUD_NAMES.any { lowered.contains(it) } -> R.drawable.ic_output_earbuds
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.drawable.ic_headphones

            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET -> R.drawable.ic_headphones

            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> R.drawable.ic_airplay_radio

            else -> R.drawable.ic_airplay_radio
        }
    }

    /**
     * Names that mean earbuds rather than headphones. Bluetooth gives a product name and nothing
     * else, so this is the only signal there is; anything unmatched falls back to the headphone
     * glyph, which is wrong-looking rather than wrong.
     */
    private val EARBUD_NAMES = listOf(
        "buds", "airpods", "earbuds", "earphone", "pods", "freebuds", "liberty", "elite",
    )

    private val PRIORITY = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    private val BUILT_IN = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )
}
