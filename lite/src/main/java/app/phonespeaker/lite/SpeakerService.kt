package app.phonespeaker.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Foreground service that holds the connection to Home Assistant and plays what it sends. Built to
 * sip battery: the CPU lock is the only thing keeping the device awake with the screen off, the
 * Wi-Fi lock is the low-power kind, and state is only sent when it changes (plus a slow heartbeat).
 */
class SpeakerService : Service(), HaClient.Listener {
    companion object {
        private const val TAG = "PhoneSpeakerLite"
        private const val CHANNEL = "speaker"
        private const val HEARTBEAT_MS = 5 * 60_000L

        @Volatile var instance: SpeakerService? = null
            private set

        val running: Boolean get() = instance != null
        val connected: Boolean get() = instance?.client?.connected == true
        val lastError: String? get() = instance?.client?.lastError

        fun start(ctx: Context) {
            val intent = Intent(ctx, SpeakerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SpeakerService::class.java))
        }
    }

    private lateinit var cfg: Config
    private val main = Handler(Looper.getMainLooper())
    private var client: HaClient? = null
    private var player: PlayerController? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            pushState()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (instance == null) {
            if (!cfg.paired) {
                stopSelf()
                return START_NOT_STICKY
            }
            begin()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    private fun begin() {
        player = PlayerController({ pushState() }) {
            // lets Home Assistant stop waiting for a sound that will never play
            client?.send(JSONObject().put("type", "play_error"))
        }
        val c = HaClient(cfg, this)
        client = c
        instance = this
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(Context.WIFI_SERVICE).let { it as WifiManager }
            .createWifiLock(WifiManager.WIFI_MODE_FULL, "phonespeakerlite")
            .apply { setReferenceCounted(false); acquire() }
        cpuLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonespeakerlite:service")
            .apply { setReferenceCounted(false); acquire() }
        c.start()
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        client?.stop()
        client = null
        wifiLock?.takeIf { it.isHeld }?.release()
        cpuLock?.takeIf { it.isHeld }?.release()
        player?.shutdown()
        super.onDestroy()
    }

    override fun onConnected() {
        pushState()
    }

    override fun onDisconnected() {}

    override fun onCommand(msg: JSONObject) {
        val p = player ?: return
        when (msg.optString("type")) {
            "play" -> msg.optString("url").takeIf { it.isNotEmpty() }?.let {
                p.play(HaClient.resolveMediaUrl(cfg.baseUrl, it))
            }
            "resume" -> p.resume()
            "pause" -> p.pause()
            "stop" -> p.stop()
            "volume" -> p.setVolume(msg.optDouble("level", p.volume.toDouble()).toFloat())
            "mute" -> p.setMuted(msg.optBoolean("muted"))
            // Home Assistant just created our device: it ignored everything sent until now
            "attached" -> main.post { pushState() }
            else -> Log.i(TAG, "ignored ${msg.optString("type")}")
        }
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
        c.send(JSONObject().put("type", "state").put("player", playerState))
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Speaker", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Phone Speaker Lite")
            .setContentText("Funcionando como parlante")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
