package app.phonespeaker.esphome

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import app.phonespeaker.PlayerController
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Who we say we are to Home Assistant. */
data class Identity(val name: String, val friendlyName: String, val mac: String)

/**
 * Plaintext ESPHome native API server (no encryption, no password: both are optional,
 * and password auth was removed upstream). Home Assistant's `esphome` integration
 * discovers it via mDNS and treats it as an ESPHome device.
 *
 * generic mode: one media_player.
 * smart mode:   media_player + audio output select + battery sensor.
 */
class EspHomeServer(
    private val ctx: Context,
    private val identity: Identity,
    private val player: PlayerController,
    private val smart: Boolean,
) {
    companion object {
        const val PORT = 6053
        private const val TAG = "PhoneSpeaker"
        const val DEFAULT_OUTPUT = "Default"

        // message ids, from esphome/components/api/api.proto
        const val HELLO_REQ = 1; const val HELLO_RESP = 2
        const val DISCONNECT_REQ = 5; const val DISCONNECT_RESP = 6
        const val PING_REQ = 7; const val PING_RESP = 8
        const val DEVICE_INFO_REQ = 9; const val DEVICE_INFO_RESP = 10
        const val LIST_ENTITIES_REQ = 11
        const val LIST_SENSOR = 16; const val SENSOR_STATE = 25
        const val LIST_DONE = 19
        const val SUBSCRIBE_STATES = 20
        const val LIST_SELECT = 52; const val SELECT_STATE = 53; const val SELECT_CMD = 54
        const val LIST_MEDIA_PLAYER = 63; const val MEDIA_STATE = 64; const val MEDIA_CMD = 65

        // entity keys
        const val KEY_MEDIA = 1; const val KEY_OUTPUT = 2; const val KEY_BATTERY = 3

        // Home Assistant MediaPlayerEntityFeature bits
        const val FEATURES = 1 or 4 or 8 or 512 or 4096 or 16384 // pause, volume set/mute, play media, stop, play

        // MediaPlayerCommand
        const val CMD_PLAY = 0; const val CMD_PAUSE = 1; const val CMD_STOP = 2
        const val CMD_MUTE = 3; const val CMD_UNMUTE = 4; const val CMD_TOGGLE = 5
        const val CMD_VOLUME_UP = 6; const val CMD_VOLUME_DOWN = 7
        const val CMD_TURN_ON = 12; const val CMD_TURN_OFF = 13

        private val OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val clients = CopyOnWriteArrayList<Client>()
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var selectedOutput = DEFAULT_OUTPUT
    private var serverSocket: ServerSocket? = null

    fun start() {
        running = true
        thread(name = "esphome-accept", isDaemon = true) {
            try {
                val s = ServerSocket(PORT)
                serverSocket = s
                while (running) {
                    val sock = s.accept()
                    thread(name = "esphome-client", isDaemon = true) { Client(sock).run() }
                }
            } catch (_: IOException) {
                // socket closed by stop()
            }
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()
        clients.forEach { it.close() }
        sender.shutdown()
    }

    /**
     * Push current state to every client that subscribed. Called from the main thread
     * (player callbacks), where socket writes would throw NetworkOnMainThreadException.
     */
    fun pushState() {
        if (!sender.isShutdown) sender.execute { clients.forEach { if (it.subscribed) it.sendStates() } }
    }

    private fun outputDevices(): List<AudioDeviceInfo> =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in OUTPUT_TYPES }

    private fun label(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
            else -> "USB"
        }
        val product = d.productName?.toString().orEmpty()
        return if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || product.isEmpty()) kind else "$kind: $product"
    }

    private fun battery(): Float =
        ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()

    private inner class Client(private val sock: Socket) {
        private val input = sock.getInputStream()
        private val output = sock.getOutputStream()
        @Volatile var subscribed = false

        fun close() = try { sock.close() } catch (_: IOException) {}

        private fun send(type: Int, payload: ByteArray = ByteArray(0)) = synchronized(output) {
            output.write(Frames.encode(type, payload))
            output.flush()
        }

        fun run() {
            clients.add(this)
            Log.i(TAG, "client connected: ${sock.remoteSocketAddress}")
            // HA pings about every 20 s; drop connections that went silent
            sock.soTimeout = 90_000
            try {
                while (true) {
                    val preamble = input.read()
                    if (preamble < 0) break
                    if (preamble != 0) {
                        // 0x01 = noise encryption requested; not supported
                        Log.w(TAG, "unsupported preamble $preamble (encrypted client?)")
                        break
                    }
                    val len = Frames.readVarint(input)
                    val type = Frames.readVarint(input)
                    val payload = ByteArray(len)
                    var off = 0
                    while (off < len) {
                        val n = input.read(payload, off, len - off)
                        if (n < 0) throw java.io.EOFException()
                        off += n
                    }
                    if (!handle(type, payload)) break
                }
            } catch (e: IOException) {
                Log.i(TAG, "client connection ended: $e")
            } finally {
                clients.remove(this)
                close()
            }
        }

        /** Returns false when the connection should close. */
        private fun handle(type: Int, payload: ByteArray): Boolean {
            Log.d(TAG, "rx type=$type len=${payload.size}")
            when (type) {
                HELLO_REQ -> send(
                    HELLO_RESP,
                    PbWriter().uint(1, 1).uint(2, 10).string(3, "phone-speaker 0.1").string(4, identity.name).bytes()
                )
                DEVICE_INFO_REQ -> send(
                    DEVICE_INFO_RESP,
                    PbWriter()
                        .string(2, identity.name)
                        .string(3, identity.mac)
                        .string(4, "2026.1.0")
                        .string(6, android.os.Build.MODEL)
                        .string(12, android.os.Build.MANUFACTURER.take(20))
                        .string(13, identity.friendlyName)
                        .bytes()
                )
                LIST_ENTITIES_REQ -> listEntities()
                SUBSCRIBE_STATES -> { subscribed = true; sendStates() }
                PING_REQ -> send(PING_RESP)
                DISCONNECT_REQ -> { send(DISCONNECT_RESP); return false }
                MEDIA_CMD -> mediaCommand(PbReader(payload))
                SELECT_CMD -> selectCommand(PbReader(payload))
                // everything else (logs, HA state/service subscriptions, ...) needs no reply
            }
            return true
        }

        private fun listEntities() {
            send(
                LIST_MEDIA_PLAYER,
                PbWriter()
                    .string(1, "speaker")
                    .fixed32(2, KEY_MEDIA)
                    .string(3, "Speaker")
                    .bool(8, true) // supports_pause: older clients ignore feature_flags
                    .uint(11, FEATURES.toLong())
                    .bytes()
            )
            if (smart) {
                val sel = PbWriter()
                    .string(1, "audio_output")
                    .fixed32(2, KEY_OUTPUT)
                    .string(3, "Audio output")
                    .string(5, "mdi:speaker-bluetooth")
                (listOf(DEFAULT_OUTPUT) + outputDevices().map { label(it) }).forEach { sel.string(6, it) }
                send(LIST_SELECT, sel.bytes())

                send(
                    LIST_SENSOR,
                    PbWriter()
                        .string(1, "battery")
                        .fixed32(2, KEY_BATTERY)
                        .string(3, "Battery")
                        .string(6, "%")
                        .string(9, "battery")
                        .uint(10, 1) // measurement
                        .uint(13, 2) // diagnostic
                        .bytes()
                )
            }
            send(LIST_DONE)
        }

        fun sendStates() {
            try {
                send(
                    MEDIA_STATE,
                    PbWriter()
                        .fixed32(1, KEY_MEDIA)
                        .uint(2, player.state.toLong())
                        .float(3, player.volume)
                        .bool(4, player.muted)
                        .bytes()
                )
                if (smart) {
                    send(SELECT_STATE, PbWriter().fixed32(1, KEY_OUTPUT).string(2, selectedOutput).bytes())
                    send(SENSOR_STATE, PbWriter().fixed32(1, KEY_BATTERY).float(2, battery()).bytes())
                }
            } catch (_: IOException) {
                close()
            }
        }

        private fun mediaCommand(r: PbReader) {
            // announcements (fields 8/9) are treated like normal playback for now
            if (r.bool(4)) player.setVolume(r.float(5))
            if (r.bool(6)) r.strings[7]?.let { player.play(it) }
            if (r.bool(2)) {
                when ((r.varints[3] ?: 0L).toInt()) {
                    CMD_PLAY -> player.resume()
                    CMD_PAUSE -> player.pause()
                    CMD_STOP, CMD_TURN_OFF -> player.stop()
                    CMD_MUTE -> player.setMuted(true)
                    CMD_UNMUTE -> player.setMuted(false)
                    CMD_TOGGLE -> if (player.state == PlayerController.PLAYING) player.pause() else player.resume()
                    CMD_VOLUME_UP -> player.setVolume(player.volume + 0.1f)
                    CMD_VOLUME_DOWN -> player.setVolume(player.volume - 0.1f)
                }
            }
        }

        private fun selectCommand(r: PbReader) {
            if (!smart) return
            val wanted = r.strings[2] ?: return
            val device = outputDevices().firstOrNull { label(it) == wanted }
            if (wanted != DEFAULT_OUTPUT && device == null) return
            selectedOutput = wanted
            player.setOutput(device)
            pushState()
        }
    }
}
