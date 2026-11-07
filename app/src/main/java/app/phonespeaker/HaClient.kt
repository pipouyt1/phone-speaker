package app.phonespeaker

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps a websocket open to the phone_speaker integration in Home Assistant.
 * The phone dials out, so this works behind NAT where Home Assistant could not
 * reach the phone. Reconnects with backoff until stop() is called.
 */
class HaClient(private val cfg: Config, private val listener: Listener) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onCommand(msg: JSONObject)
    }

    companion object {
        private const val TAG = "PhoneSpeaker"
        private val JSON = "application/json".toMediaType()

        /** Shared HTTP client (also used to download images for the screen). */
        val httpClient: OkHttpClient get() = http

        private val http = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        /** "192.168.1.50", "ha.local:8123" or a full URL, normalized to scheme://host[:port]. */
        fun normalizeUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isNotEmpty() && "://" !in s) s = "http://$s"
            return s
        }

        /**
         * Home Assistant sends its own files (TTS replies, local media, test sounds) as paths like
         * "/api/tts_proxy/...". Resolve them against the address this phone already reaches Home
         * Assistant at, so playback does not depend on the URL Home Assistant believes it has.
         */
        fun resolveMediaUrl(baseUrl: String, url: String): String =
            if (url.startsWith("/")) baseUrl + url else url

        private fun wsUrl(baseUrl: String) = baseUrl.replaceFirst("http", "ws") + "/api/phone_speaker/ws"

        /** Exchange the pairing code for a device token. Blocking: call off the main thread. */
        fun pair(cfg: Config, baseUrl: String, code: String): Result<String> {
            val body = JSONObject()
                .put("code", code)
                .put("device_id", cfg.deviceId)
                .put("name", cfg.deviceName)
                .put("mode", cfg.mode)
                .put("display", cfg.display)
                .toString()
                .toRequestBody(JSON)
            return try {
                val req = Request.Builder().url("$baseUrl/api/phone_speaker/pair").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> Result.success(JSONObject(text).getString("token"))
                        resp.code == 403 -> Result.failure(Exception("Código inválido o vencido"))
                        resp.code == 429 -> Result.failure(Exception("Demasiados intentos, espera un minuto"))
                        resp.code == 404 -> Result.failure(Exception("La integración Phone Speaker no está instalada en esa dirección"))
                        else -> Result.failure(Exception("Error ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("No se pudo conectar: ${e.message}"))
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    private var attempt = 0

    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
        setConnected(false)
    }

    fun send(msg: JSONObject): Boolean = connected && ws?.send(msg.toString()) == true

    /** Binary frame with microphone audio (16 kHz mono 16-bit PCM). */
    fun sendAudio(chunk: ByteArray, length: Int): Boolean =
        connected && ws?.send(chunk.toByteString(0, length)) == true

    private fun connect() {
        if (!running) return
        val request = Request.Builder().url(wsUrl(cfg.baseUrl)).build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("token", cfg.token)
                    .put("mode", cfg.mode)
                    .put("display", cfg.display)
                    .put("manufacturer", android.os.Build.MANUFACTURER)
                    .put("model", android.os.Build.MODEL)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                val msg = try { JSONObject(text) } catch (_: Exception) { return }
                if (msg.optString("type") == "hello_ok") {
                    attempt = 0
                    lastError = null
                    setConnected(true)
                } else {
                    listener.onCommand(msg)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4401) lastError = "Home Assistant rechazó el emparejamiento: vuelve a emparejar"
                dropped(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "websocket failure: $t")
                lastError = "Sin conexión con Home Assistant (${t.message})"
                dropped(webSocket)
            }
        })
    }

    private fun dropped(webSocket: WebSocket) {
        if (webSocket !== ws) return
        setConnected(false)
        if (!running) return
        // retry quickly for the first minute (right after pairing, Home Assistant is still
        // creating the device), then back off
        val delay = if (attempt < 20) minOf(3_000L, 1_000L shl minOf(attempt, 5)) else 30_000L
        attempt++
        main.postDelayed({ connect() }, delay)
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        if (value) listener.onConnected() else listener.onDisconnected()
    }
}
