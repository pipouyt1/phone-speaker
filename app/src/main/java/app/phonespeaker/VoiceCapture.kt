package app.phonespeaker

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread

/**
 * Records the microphone as 16 kHz mono 16-bit PCM (what Assist expects) and hands out
 * 100 ms chunks. Echo cancellation and noise suppression are enabled when the device has them.
 */
class VoiceCapture(
    private val onChunk: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "PhoneSpeaker"
        private const val RATE = 16000
        private const val CHUNK_BYTES = RATE / 10 * 2 // 100 ms
    }

    @Volatile private var running = false
    private var worker: Thread? = null

    val active: Boolean get() = running

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "voice-capture", isDaemon = true) {
            val minBuffer = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, CHUNK_BYTES * 2)
                )
            } catch (e: Exception) {
                running = false
                onError("No se pudo abrir el micrófono: ${e.message}")
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                running = false
                onError("No se pudo abrir el micrófono")
                return@thread
            }
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
            aec?.enabled = true
            ns?.enabled = true
            try {
                record.startRecording()
                val buffer = ByteArray(CHUNK_BYTES)
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        onChunk(buffer, read)
                    } else if (read < 0) {
                        Log.w(TAG, "microphone read failed: $read")
                        onError("Se perdió el micrófono")
                        break
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                aec?.release()
                ns?.release()
                running = false
            }
        }
    }

    fun stop() {
        running = false
    }
}
