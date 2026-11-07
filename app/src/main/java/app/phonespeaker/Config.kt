package app.phonespeaker

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Pairing result and user choices, persisted in SharedPreferences. */
class Config(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("phone_speaker", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    /** Smart mode adds the audio output selector and battery sensor in Home Assistant. */
    var smart: Boolean
        get() = prefs.getBoolean("smart", true)
        set(v) = prefs.edit().putBoolean("smart", v).apply()

    val paired: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    val mode: String get() = if (smart) "smart" else "generic"

    val deviceName: String get() = Build.MODEL

    val deviceId: String
        get() = "android-" + (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    fun unpair() {
        prefs.edit().remove("token").apply()
    }
}
