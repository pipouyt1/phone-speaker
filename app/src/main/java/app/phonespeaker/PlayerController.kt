package app.phonespeaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** Wraps MediaPlayer. States use the numeric values of ESPHome's MediaPlayerState. */
class PlayerController(
    @Suppress("unused") private val ctx: Context,
    private val onChange: () -> Unit,
) {
    companion object {
        const val IDLE = 1
        const val PLAYING = 2
        const val PAUSED = 3
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var output: AudioDeviceInfo? = null

    @Volatile var state = IDLE
        private set
    @Volatile var volume = 1f
        private set
    @Volatile var muted = false
        private set

    private fun setState(s: Int) { state = s; onChange() }

    private fun applyVolume() {
        val v = if (muted) 0f else volume
        player?.setVolume(v, v)
    }

    fun play(url: String) = main.post {
        release()
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            output?.let { p.setPreferredDevice(it) }
            p.setDataSource(url)
            p.setOnPreparedListener { applyVolume(); it.start(); setState(PLAYING) }
            p.setOnCompletionListener { setState(IDLE) }
            p.setOnErrorListener { _, _, _ -> release(); setState(IDLE); true }
            p.prepareAsync()
        } catch (_: Exception) {
            release(); setState(IDLE)
        }
    }

    fun resume() = main.post {
        player?.let { if (state == PAUSED) { it.start(); setState(PLAYING) } }
    }

    fun pause() = main.post {
        player?.let { if (state == PLAYING) { it.pause(); setState(PAUSED) } }
    }

    fun stop() = main.post { release(); setState(IDLE) }

    fun setVolume(v: Float) = main.post { volume = v.coerceIn(0f, 1f); applyVolume(); onChange() }

    fun setMuted(m: Boolean) = main.post { muted = m; applyVolume(); onChange() }

    /** null = system default routing. */
    fun setOutput(device: AudioDeviceInfo?) = main.post {
        output = device
        player?.setPreferredDevice(device)
    }

    private fun release() {
        player?.release()
        player = null
    }

    fun shutdown() = main.post { release() }
}
