package app.phonespeaker.lite

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Pairing result, persisted in SharedPreferences. */
class Config(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("phone_speaker_lite", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    /** The user pressed stop: do not start again on boot. */
    var userStopped: Boolean
        get() = prefs.getBoolean("user_stopped", false)
        set(v) = prefs.edit().putBoolean("user_stopped", v).apply()

    val paired: Boolean get() = token.isNotEmpty() && baseUrl.isNotEmpty()

    val deviceName: String get() = Build.MODEL

    val deviceId: String
        get() = "android-lite-" + (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    fun unpair() {
        prefs.edit().remove("token").remove("user_stopped").apply()
    }
}
