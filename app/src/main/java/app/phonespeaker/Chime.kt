package app.phonespeaker

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** The short "pim" that confirms the assistant is listening. */
object Chime {
    private const val TAG = "PhoneSpeaker"
    const val DURATION_MS = 120
    private val main = Handler(Looper.getMainLooper())

    /** [volume] is 0-100; 0 is silent. */
    fun play(volume: Int) {
        if (volume <= 0) return
        try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume.coerceAtMost(100))
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
            // a ToneGenerator holds a native resource: free it once the tone is over
            main.postDelayed({ generator.release() }, DURATION_MS + 150L)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not play the chime: $e")
        }
    }
}
