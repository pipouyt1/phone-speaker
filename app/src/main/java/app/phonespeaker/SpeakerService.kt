package app.phonespeaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service: holds the connection to Home Assistant, plays what it is told
 * to play and reports state back.
 */
class SpeakerService : Service(), HaClient.Listener {
    companion object {
        private const val CHANNEL = "speaker"
        private const val STATE_INTERVAL_MS = 60_000L

        @Volatile var running = false
            private set
        @Volatile var client: HaClient? = null
            private set
    }

    private lateinit var cfg: Config
    private var player: PlayerController? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var selectedOutput = AudioOutputs.DEFAULT
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            pushState()
            main.postDelayed(this, STATE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cfg = Config(this)
        if (!cfg.paired) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, 1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (!running) start()
        return START_STICKY
    }

    private fun start() {
        player = PlayerController(this) { pushState() }
        val c = HaClient(cfg, this)
        client = c
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phone-speaker")
            .apply { setReferenceCounted(false); acquire() }
        c.start()
        main.postDelayed(ticker, STATE_INTERVAL_MS)
        running = true
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        client?.stop()
        client = null
        wifiLock?.takeIf { it.isHeld }?.release()
        player?.shutdown()
        super.onDestroy()
    }

    override fun onConnected() = pushState()

    override fun onDisconnected() {}

    override fun onCommand(msg: JSONObject) {
        val p = player ?: return
        when (msg.optString("type")) {
            "play" -> msg.optString("url").takeIf { it.isNotEmpty() }?.let { p.play(it) }
            "resume" -> p.resume()
            "pause" -> p.pause()
            "stop" -> p.stop()
            "volume" -> p.setVolume(msg.optDouble("level", p.volume.toDouble()).toFloat())
            "mute" -> p.setMuted(msg.optBoolean("muted"))
            "output" -> selectOutput(msg.optString("name"))
        }
    }

    private fun selectOutput(name: String) {
        val audio = getSystemService(AudioManager::class.java)
        val device = AudioOutputs.list(audio).firstOrNull { AudioOutputs.label(it) == name }
        if (name != AudioOutputs.DEFAULT && device == null) return
        selectedOutput = name
        player?.setOutput(device)
        pushState()
    }

    private fun pushState() {
        val c = client ?: return
        val p = player ?: return
        val playerState = JSONObject()
            .put("state", when (p.state) {
                PlayerController.PLAYING -> "playing"
                PlayerController.PAUSED -> "paused"
                else -> "idle"
            })
            .put("volume", p.volume.toDouble())
            .put("muted", p.muted)
        val msg = JSONObject().put("type", "state").put("player", playerState)
        if (cfg.smart) {
            val audio = getSystemService(AudioManager::class.java)
            val outputs = listOf(AudioOutputs.DEFAULT) + AudioOutputs.list(audio).map { AudioOutputs.label(it) }
            msg.put("battery", getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("outputs", JSONArray(outputs))
                .put("output", selectedOutput)
        }
        c.send(msg)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Speaker", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Phone Speaker")
            .setContentText("Conectado a Home Assistant")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .build()
    }
}
