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

    /**
     * In voice mode the microphone is always streaming to Home Assistant, whose wake word
     * engine decides when someone is talking to the assistant: nothing to configure here.
     */
    val listen: Boolean get() = voice

    /**
     * Volume of the short beep when the assistant starts listening (wake word heard or Talk
     * pressed), 0-100. 0 turns it off.
     */
    var chimeVolume: Int
        get() = prefs.getInt("chime_volume", 80)
        set(v) = prefs.edit().putInt("chime_volume", v.coerceIn(0, 100)).apply()

    /** Show images, videos, web pages and messages that Home Assistant sends to this screen. */
    var display: Boolean
        get() = prefs.getBoolean("display", false)
        set(v) = prefs.edit().putBoolean("display", v).apply()

    val smart: Boolean get() = mode != MODE_GENERIC
    val voice: Boolean get() = mode == MODE_VOICE

    /** True once the user stopped the speaker on purpose: opening the app then does not restart it. */
    var userStopped: Boolean
        get() = prefs.getBoolean("user_stopped", false)
        set(v) = prefs.edit().putBoolean("user_stopped", v).apply()

    val paired: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    val deviceName: String get() = Build.MODEL

    val deviceId: String
        get() = "android-" + (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    fun unpair() {
        prefs.edit().remove("token").remove("user_stopped").apply()
    }
}
