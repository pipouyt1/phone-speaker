package app.phonespeaker

import android.app.role.RoleManager
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
     * Assistant mode: the microphone stays off until the phone's assistant is invoked (power
     * button long-press, assist gesture) or Talk is pressed, like the assistant on any phone.
     */
    var assistantMode: Boolean
        get() = prefs.getBoolean("assistant_mode", false)
        set(v) = prefs.edit().putBoolean("assistant_mode", v).apply()

    /**
     * Unless assistant mode is on, in voice mode the microphone is always streaming to Home
     * Assistant, whose wake word engine decides when someone is talking to the assistant.
     */
    val listen: Boolean get() = voice && !assistantMode

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

    /** Hide the status and navigation bars (swipe from the edge to bring them back). */
    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", false)
        set(v) = prefs.edit().putBoolean("fullscreen", v).apply()

    /** Home screen with a big clock and an app list, for using the device as a launcher or wall display. */
    var launcher: Boolean
        get() = prefs.getBoolean("launcher", false)
        set(v) = prefs.edit().putBoolean("launcher", v).apply()

    /** Room mode: a Home Assistant dashboard fills the screen, and the assistant floats over it. */
    var room: Boolean
        get() = prefs.getBoolean("room", false)
        set(v) = prefs.edit().putBoolean("room", v).apply()

    /** The dashboard to show in room mode: a path such as /lovelace/0, or a full address. */
    var roomUrl: String
        get() = prefs.getString("room_url", "") ?: ""
        set(v) = prefs.edit().putString("room_url", v.trim()).apply()

    /** Show the assistant over other apps when the wake word is heard (needs "display over other apps"). */
    var floating: Boolean
        get() = prefs.getBoolean("floating", true)
        set(v) = prefs.edit().putBoolean("floating", v).apply()

    /** Performance mode: no animations and no assistant pill, for slow or old devices. */
    var performance: Boolean
        get() = prefs.getBoolean("performance", false)
        set(v) {
            prefs.edit().putBoolean("performance", v).apply()
            app.phonespeaker.ui.Perf.on = v
        }

    init {
        app.phonespeaker.ui.Perf.on = performance
    }

    val smart: Boolean get() = mode != MODE_GENERIC
    val voice: Boolean get() = mode == MODE_VOICE

    /** True once the user stopped the speaker on purpose: opening the app then does not restart it. */
    var userStopped: Boolean
        get() = prefs.getBoolean("user_stopped", false)
        set(v) = prefs.edit().putBoolean("user_stopped", v).apply()

    val paired: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    /** True when Android uses this app as the device's digital assistant. */
    val defaultAssistant: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = ctx.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) return true
            }
            // some devices keep the assistant in the secure setting instead of the role
            val current = runCatching {
                Settings.Secure.getString(ctx.contentResolver, "assistant")
            }.getOrNull()
            return current?.startsWith(ctx.packageName) == true
        }

    val deviceName: String get() = Build.MODEL

    val deviceId: String
        get() = "android-" + (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    fun unpair() {
        prefs.edit().remove("token").remove("user_stopped").apply()
    }
}
