package app.phonespeaker

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Pairing result and user choices, persisted in SharedPreferences. */
class Config(private val ctx: Context) {
    companion object {
        const val MODE_GENERIC = "generic"
        const val MODE_SMART = "smart"
        const val MODE_VOICE = "voice"
    }

    private val prefs = ctx.getSharedPreferences("phone_speaker", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    /**
     * generic: media player only. smart: + audio output selector and battery sensor.
     * voice: smart + microphone and voice assistant (Assist satellite).
     */
    var mode: String
        get() = prefs.getString("mode", null) ?: when {
            // installs from before the voice mode existed keep their choice
            prefs.contains("smart") -> if (prefs.getBoolean("smart", true)) MODE_SMART else MODE_GENERIC
            else -> MODE_VOICE
        }
        set(v) = prefs.edit().putString("mode", v).apply()

    /** Keep the microphone open and let Home Assistant's wake word engine listen. */
    var listen: Boolean
        get() = prefs.getBoolean("listen", true)
        set(v) = prefs.edit().putBoolean("listen", v).apply()

    val smart: Boolean get() = mode != MODE_GENERIC
    val voice: Boolean get() = mode == MODE_VOICE

    val paired: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    val deviceName: String get() = Build.MODEL

    val deviceId: String
        get() = "android-" + (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    fun unpair() {
        prefs.edit().remove("token").apply()
    }
}
