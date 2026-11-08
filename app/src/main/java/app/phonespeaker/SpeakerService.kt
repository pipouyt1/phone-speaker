package app.phonespeaker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service: holds the connection to Home Assistant, plays what it is told
 * to play, reports state back and, in voice mode, streams the microphone to Assist.
 */
class SpeakerService : Service(), HaClient.Listener {
    companion object {
        private const val TAG = "PhoneSpeaker"
        private const val CHANNEL = "speaker"
        private const val STATE_INTERVAL_MS = 60_000L
        private const val ANNOUNCE_TIMEOUT_MS = 120_000L

        @Volatile var running = false
            private set
        @Volatile var client: HaClient? = null
            private set
        @Volatile var instance: SpeakerService? = null
            private set
        @Volatile var voiceStatus: String = ""
            private set
        /** Microphone loudness, 0-100, for checking that the microphone really hears something. */
        @Volatile var micLevel: Int = 0
            private set
        /** What the assistant is doing, for the UI: idle, waiting, listening, thinking, speaking or error. */
        @Volatile var voicePhase: String = "idle"
            private set
        /** What Home Assistant asked the screen to show right now, if anything. */
        @Volatile var screenItem: DisplayItem? = null
            private set
        /** True while the app's screen is in the foreground (set by the activity). */
        @Volatile var uiVisible: Boolean = false

        fun dismissDisplay() { screenItem = null }

        /** The assistant's last answer, as text (shown while it speaks). */
        @Volatile var lastReply: String = ""
            private set

        /** The last thing Assist understood, as text. */
        @Volatile var lastHeard: String = ""
            private set

        fun hasMicPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        @Volatile private var assistantRequested = false

        /**
         * The phone's digital assistant (long-press Home or the assist gesture) was invoked:
         * make sure the speaker is running and start a conversation as soon as it can listen.
         */
        fun requestAssistant(ctx: Context) {
            val cfg = Config(ctx)
            if (!cfg.paired || !cfg.voice || !hasMicPermission(ctx)) return
            // an explicit invocation starts the speaker even if it was stopped by hand
            cfg.userStopped = false
            assistantRequested = true
            val service = instance
            when {
                service == null ->
                    ContextCompat.startForegroundService(ctx, Intent(ctx, SpeakerService::class.java))
                !service.micAllowed -> service.retryMicrophone()
                else -> service.main.post { service.takePendingAssistant() }
            }
        }
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

    // voice
    private var micAllowed = false
    private var capture: VoiceCapture? = null
    @Volatile private var streaming = false
    @Volatile private var announcePlaying = false
    private var sawAnnounceStart = false
    private var voiceErrors = 0
    private var runId = 0
    private var assistantSession = false
    private var suppressReplyUntil = 0L
    private var overlay: AssistantOverlay? = null
    private var overlayWantedAt = 0L
    private val overlayTick = object : Runnable {
        override fun run() {
            updateOverlay()
            main.postDelayed(this, 250L)
        }
    }
    @Volatile private var micMuteUntil = 0L
    private var lastVoiceStart = 0L
    private val restartListening = Runnable {
        if (cfg.listen && !streaming && !announcePlaying && voiceEnabled()) startVoice("wake_word")
    }
    private val announceTimeout = Runnable { clearAnnounce() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cfg = Config(this)
        if (!cfg.paired) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!running) start()
        return START_STICKY
    }

    private fun startInForeground() {
        val playback = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (cfg.voice && hasMicPermission(this)) {
            try {
                ServiceCompat.startForeground(
                    this, 1, notification(), playback or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                micAllowed = true
                return
            } catch (e: Exception) {
                // Android refuses microphone services started while the app is in the background
                Log.w(TAG, "microphone foreground service not allowed: $e")
            }
        }
        micAllowed = false
        ServiceCompat.startForeground(this, 1, notification(), playback)
    }

    private fun start() {
        player = PlayerController(this, { pushState() }) {
            // lets Home Assistant stop waiting for a sound that will never play
            client?.send(JSONObject().put("type", "play_error"))
            main.post { clearAnnounce() }
        }
        val c = HaClient(cfg, this)
        client = c
        instance = this
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phone-speaker")
            .apply { setReferenceCounted(false); acquire() }
        c.start()
        main.postDelayed(ticker, STATE_INTERVAL_MS)
        main.post(overlayTick)
        running = true
    }

    override fun onDestroy() {
        running = false
        instance = null
        assistantRequested = false
        voiceStatus = ""
        voicePhase = "idle"
        screenItem = null
        main.removeCallbacksAndMessages(null)
        overlay?.hide()
        overlay = null
        capture?.stop()
        client?.stop()
        client = null
        wifiLock?.takeIf { it.isHeld }?.release()
        player?.shutdown()
        super.onDestroy()
    }

    // ---- connection ------------------------------------------------------------

    override fun onConnected() {
        pushState()
        main.post {
            if (!takePendingAssistant() && cfg.voice && cfg.listen) maybeListen()
        }
    }

    override fun onDisconnected() {
        main.post {
            streaming = false
            capture?.stop()
        }
    }

    // ---- commands from Home Assistant ------------------------------------------

    override fun onCommand(msg: JSONObject) {
        Log.i(TAG, "command ${msg.optString("type")}")
        val p = player ?: return
        when (msg.optString("type")) {
            "play" -> {
                if (msg.optBoolean("announce") && SystemClock.elapsedRealtime() < suppressReplyUntil) {
                    // the sentence was "abre <app>": Home Assistant does not know that app and its answer
                    // ("no entendí") would be wrong, so do not play it
                    suppressReplyUntil = 0L
                    client?.send(JSONObject().put("type", "play_skipped"))
                } else {
                    msg.optString("url").takeIf { it.isNotEmpty() }?.let {
                        main.post { if (msg.optBoolean("announce")) beginAnnounce() }
                        p.play(HaClient.resolveMediaUrl(cfg.baseUrl, it))
                    }
                }
            }
            "resume" -> p.resume()
            "pause" -> p.pause()
            "stop" -> { main.post { clearAnnounce() }; p.stop() }
            "volume" -> p.setVolume(msg.optDouble("level", p.volume.toDouble()).toFloat())
            "mute" -> p.setMuted(msg.optBoolean("muted"))
            "output" -> selectOutput(msg.optString("name"))
            "display" -> showDisplay(msg)
            "display_clear" -> screenItem = null
            "attached" -> main.post {
                // Home Assistant just created our device: it ignored everything sent until now
                streaming = false
                pushState()
                maybeListen()
            }
            "voice_end" -> {
                val run = msg.optInt("run", -1)
                main.post {
                    // a late "end" for a run we already replaced would cut off the new one
                    if (run == -1 || run == runId) {
                        streaming = false
                        maybeListen()
                    }
                }
            }
            "voice_event" -> main.post { handleVoiceEvent(msg) }
        }
    }

    /** Audio outputs the user can pick, "Default" first. */
    fun outputNames(): List<String> =
        listOf(AudioOutputs.DEFAULT) + AudioOutputs.list(getSystemService(AudioManager::class.java)).map { AudioOutputs.label(it) }

    fun currentOutput(): String = selectedOutput

    fun chooseOutput(name: String) = selectOutput(name)

    // ---- opening apps and the floating assistant ------------------------------------------

    /** "Abre Spotify": open the app and show it on the assistant card instead of Home Assistant's reply. */
    private fun openApp(app: AppLauncher.Entry) {
        suppressReplyUntil = SystemClock.elapsedRealtime() + 20_000L
        lastReply = "Abriendo ${app.label}"
        voicePhase = "speaking"
        val intent = AppLauncher.launchIntent(this, app) ?: return
        if (uiVisible || Settings.canDrawOverlays(this)) {
            startActivity(intent)
            return
        }
        // Android does not let an app in the background open another one without that permission
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("display", "Contenido en pantalla", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(
            3,
            NotificationCompat.Builder(this, "display")
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle("Abrir ${app.label}")
                .setContentText("Toca para abrirla")
                .setContentIntent(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    /** Light the screen when the wake word is heard, so the floating assistant can be seen. */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "phonespeaker:wake")
            .acquire(8_000L)
    }

    /** Show the assistant over other apps while someone talks to it, and a few seconds after it answers. */
    private fun updateOverlay() {
        val active = voicePhase == "listening" || voicePhase == "thinking" || voicePhase == "speaking" || voicePhase == "error"
        // an explicit invocation from the phone's assistant always shows the pill, even when the
        // wake-word overlay is turned off
        val wanted = cfg.voice && !uiVisible && Settings.canDrawOverlays(this) && active &&
            (cfg.floating || assistantSession)
        val now = SystemClock.elapsedRealtime()
        val window = overlay ?: AssistantOverlay(this).also { overlay = it }
        if (wanted) {
            overlayWantedAt = now
            if (!window.showing) window.show()
        } else if (window.showing && (uiVisible || now - overlayWantedAt > 4_500L)) {
            window.hide()
        }
    }

    // ---- screen --------------------------------------------------------------------------

    private fun showDisplay(msg: JSONObject) {
        val kind = msg.optString("kind")
        if (kind !in setOf("image", "video", "web", "text")) return
        val defaultSeconds = if (kind == "image" || kind == "text") 30 else 0
        val item = DisplayItem(
            id = SystemClock.elapsedRealtime(),
            kind = kind,
            url = HaClient.resolveMediaUrl(cfg.baseUrl, msg.optString("url")),
            title = msg.optString("title"),
            text = msg.optString("text"),
            seconds = if (msg.has("seconds")) msg.optInt("seconds") else defaultSeconds,
        )
        screenItem = item
        main.post { bringToFront(item) }
    }

    /**
     * Put the app in front so the content is seen. Android only lets an app in the background open
     * its screen if it may draw over other apps; without that, a notification opens it instead.
     */
    private fun bringToFront(item: DisplayItem) {
        if (uiVisible) return
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (Settings.canDrawOverlays(this)) {
            startActivity(open)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("display", "Contenido en pantalla", NotificationManager.IMPORTANCE_HIGH))
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            2,
            NotificationCompat.Builder(this, "display")
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(item.title.ifEmpty { "Contenido de Home Assistant" })
                .setContentText(if (item.kind == "text") item.text else "Toca para verlo")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun selectOutput(name: String) {
        val audio = getSystemService(AudioManager::class.java)
        val device = AudioOutputs.list(audio).firstOrNull { AudioOutputs.label(it) == name }
        if (name != AudioOutputs.DEFAULT && device == null) return
        selectedOutput = name
        player?.setOutput(device)
        pushState()
    }

    // ---- voice -----------------------------------------------------------------

    private fun voiceEnabled() = cfg.voice && micAllowed && client?.connected == true

    /**
     * Start the conversation requested by the phone's assistant, once the microphone and the
     * connection are ready. Returns true when there is a request, even if it has to wait.
     */
    private fun takePendingAssistant(): Boolean {
        if (!assistantRequested) return false
        if (!cfg.voice || !micAllowed || !hasMicPermission(this)) {
            assistantRequested = false
            return false
        }
        if (client?.connected != true) return true // onConnected will try again
        assistantRequested = false
        assistantSession = true
        chime()
        lastReply = ""
        startVoice("stt")
        return true
    }

    /**
     * The service may have been restarted by Android in the background, when the microphone
     * foreground type was not allowed. The assistant activity is in front now: try again.
     */
    private fun retryMicrophone() {
        startInForeground()
        if (micAllowed) takePendingAssistant() else assistantRequested = false
    }

    /** Push to talk: start a conversation right now (Home Assistant detects the end of speech). */
    fun talk() {
        main.post {
            if (!voiceEnabled()) {
                voiceStatus = when {
                    !cfg.voice -> "Activa el modo asistente de voz."
                    !micAllowed -> "Falta el permiso del micrófono. Dalo y reinicia."
                    else -> "Sin conexión con Home Assistant."
                }
                return@post
            }
            chime()
            lastReply = ""
            startVoice("stt")
        }
    }

    private fun startVoice(stage: String) {
        if (!voiceEnabled()) return
        // continuous listening goes back to the wake word: the explicit assistant session is over
        if (stage == "wake_word") assistantSession = false
        if (streaming) client?.send(JSONObject().put("type", "voice_stop"))
        if (capture?.active != true) {
            capture = VoiceCapture(::onMicChunk, ::onMicError).also { it.start() }
        }
        streaming = true
        runId++
        lastVoiceStart = SystemClock.elapsedRealtime()
        voicePhase = if (stage == "wake_word") "waiting" else "listening"
        voiceStatus = if (stage == "wake_word") "Esperando la palabra de activación..." else "Escuchando..."
        client?.send(JSONObject().put("type", "voice_start").put("stage", stage).put("run", runId))
    }

    private fun stopVoice() {
        streaming = false
        client?.send(JSONObject().put("type", "voice_stop"))
        if (!cfg.listen) capture?.stop()
    }

    private fun maybeListen() {
        if (!voiceEnabled()) return
        main.removeCallbacks(restartListening)
        if (cfg.listen && !streaming && !announcePlaying) {
            // back off when Home Assistant keeps ending the run right away, instead of looping
            val shortRun = SystemClock.elapsedRealtime() - lastVoiceStart < 1_500L
            val delay = when {
                voiceErrors > 0 -> minOf(30_000L, 5_000L * voiceErrors)
                shortRun -> 3_000L
                else -> 300L
            }
            main.postDelayed(restartListening, delay)
        } else if (!cfg.listen && !streaming) {
            capture?.stop()
        }
    }

    private fun onMicChunk(buffer: ByteArray, length: Int) {
        micLevel = loudness(buffer, length)
        // Mute the upload while a reply is playing so the assistant does not hear itself.
        if (streaming && !announcePlaying && SystemClock.elapsedRealtime() >= micMuteUntil) {
            client?.sendAudio(buffer, length)
        }
    }

    private fun loudness(buffer: ByteArray, length: Int): Int {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0
        // speech is a few percent of full scale: stretch it so the number is readable
        return (Math.sqrt(sum / samples) / 32768.0 * 400).toInt().coerceIn(0, 100)
    }

    /** The "pim": confirms the assistant is listening. The microphone is muted briefly so it is not heard. */
    private fun chime() {
        if (cfg.chimeVolume <= 0) return
        micMuteUntil = SystemClock.elapsedRealtime() + 250
        Chime.play(cfg.chimeVolume)
    }

    private fun onMicError(message: String) {
        main.post {
            streaming = false
            voiceStatus = message
        }
    }

    private fun handleVoiceEvent(msg: JSONObject) {
        when (msg.optString("event")) {
            "wake_word" -> {
                voiceErrors = 0; lastReply = ""; suppressReplyUntil = 0L
                voiceStatus = "Te escuché..."; voicePhase = "listening"
                if (cfg.floating && !uiVisible) wakeScreen()
                chime()
            }
            "reply" -> if (SystemClock.elapsedRealtime() >= suppressReplyUntil) lastReply = msg.optString("text")
            "listening" -> { voiceErrors = 0; voiceStatus = "Escuchando..."; voicePhase = "listening" }
            "heard" -> {
                voiceStatus = "Escuché: \"${msg.optString("text")}\""
                lastHeard = msg.optString("text")
                voicePhase = "thinking"
                AppLauncher.find(this, lastHeard)?.let { openApp(it) }
            }
            "thinking" -> { voiceStatus = "Pensando..."; voicePhase = "thinking" }
            "speaking" -> { voiceStatus = "Respondiendo..."; voicePhase = "speaking" }
            "error" -> {
                voiceErrors++
                val detail = msg.optString("message").ifEmpty { msg.optString("code") }
                // keep listening and retry with a growing delay: the cause (e.g. a wake word
                // engine that is still starting) usually fixes itself
                voiceStatus = "Error de voz: $detail. Reintentando..."
                voicePhase = "error"
            }
        }
    }

    private fun beginAnnounce() {
        announcePlaying = true
        sawAnnounceStart = false
        main.removeCallbacks(announceTimeout)
        main.postDelayed(announceTimeout, ANNOUNCE_TIMEOUT_MS)
    }

    private fun clearAnnounce() {
        main.removeCallbacks(announceTimeout)
        if (announcePlaying) {
            announcePlaying = false
            sawAnnounceStart = false
            if (!cfg.listen) { voiceStatus = ""; voicePhase = "idle" }
            maybeListen()
        }
    }

    // ---- state -----------------------------------------------------------------

    private fun pushState() {
        val c = client ?: return
        val p = player ?: return
        if (announcePlaying) {
            if (p.state == PlayerController.PLAYING) sawAnnounceStart = true
            else if (sawAnnounceStart) main.post { clearAnnounce() }
        }
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
