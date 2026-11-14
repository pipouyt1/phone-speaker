package app.phonespeaker.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the speaker again after the device restarts, unless the user stopped it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = Config(context)
        if (cfg.paired && !cfg.userStopped) SpeakerService.start(context)
    }
}
