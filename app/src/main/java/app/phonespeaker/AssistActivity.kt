package app.phonespeaker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/**
 * Entry point when Phone Speaker is the phone's digital assistant (long-press Home, the assist
 * gesture, or the assistant button). It has no UI of its own: it starts the conversation and
 * lets the existing assistant pill appear over whatever is on screen.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config(this)
        when {
            !cfg.paired -> openApp()
            !cfg.voice -> {
                toast("Activa el modo Voz para usar Phone Speaker como asistente.")
                openApp()
            }
            !SpeakerService.hasMicPermission(this) -> openApp()
            !Settings.canDrawOverlays(this) -> {
                // without the overlay permission the pill cannot float: show the app itself
                toast("Da el permiso \"Mostrar sobre otras apps\" para ver el asistente flotando.")
                SpeakerService.requestAssistant(this)
                openApp()
            }
            else -> {
                SpeakerService.requestAssistant(this)
                // keep the transparent activity for a moment so the service can start the
                // microphone foreground type while the app is still in the foreground
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 800)
            }
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
