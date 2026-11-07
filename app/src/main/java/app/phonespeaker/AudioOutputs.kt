package app.phonespeaker

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Audio outputs the user can pick from Home Assistant. */
object AudioOutputs {
    const val DEFAULT = "Default"

    private val TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    fun list(audio: AudioManager): List<AudioDeviceInfo> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in TYPES }

    fun label(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
            else -> "USB"
        }
        val product = d.productName?.toString().orEmpty()
        return if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || product.isEmpty()) kind else "$kind: $product"
    }
}
