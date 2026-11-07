package app.phonespeaker

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.phonespeaker.ui.AppActions
import app.phonespeaker.ui.PhoneSpeakerApp
import app.phonespeaker.ui.PhoneSpeakerTheme
import app.phonespeaker.ui.UiState
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), AppActions {
    private lateinit var cfg: Config
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cfg = Config(this)
        requestPermissions(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO,
            ), 1
        )
        setContent {
            PhoneSpeakerTheme {
                // re-read the service state a few times a second; actions bump [tick] to refresh at once
                val state by produceState(snapshot(), tick) {
                    while (true) {
                        value = snapshot()
                        delay(400)
                    }
                }
                PhoneSpeakerApp(state, this@MainActivity)
            }
        }
    }

    private fun snapshot(): UiState {
        val client = SpeakerService.client
        val service = SpeakerService.instance
        return UiState(
            paired = cfg.paired,
            running = SpeakerService.running,
            connected = client?.connected == true,
            error = client?.lastError,
            phase = SpeakerService.voicePhase,
            lastHeard = SpeakerService.lastHeard,
            micLevel = SpeakerService.micLevel,
            mode = cfg.mode,
            chimeVolume = cfg.chimeVolume,
            outputs = service?.outputNames() ?: listOf(AudioOutputs.DEFAULT),
            currentOutput = service?.currentOutput() ?: AudioOutputs.DEFAULT,
            baseUrl = cfg.baseUrl,
            deviceName = cfg.deviceName,
            version = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // the microphone service type can only be declared once the permission exists
        if (cfg.voice && SpeakerService.running && SpeakerService.hasMicPermission(this)) restartSpeaker()
    }

    // ---- AppActions ----------------------------------------------------------------------

    override fun pair(url: String, code: String, mode: String, done: (String?) -> Unit) {
        val baseUrl = HaClient.normalizeUrl(url)
        cfg.mode = mode
        thread {
            val result = HaClient.pair(cfg, baseUrl, code.trim())
            runOnUiThread {
                result
                    .onSuccess { token ->
                        cfg.baseUrl = baseUrl
                        cfg.token = token
                        startSpeaker()
                        done(null)
                    }
                    .onFailure { done(it.message) }
                tick++
            }
        }
    }

    override fun toggleService() {
        if (SpeakerService.running) stopService(Intent(this, SpeakerService::class.java)) else startSpeaker()
        tick++
    }

    override fun talk() {
        SpeakerService.instance?.talk()
    }

    override fun setMode(mode: String) {
        if (mode == cfg.mode) return
        cfg.mode = mode
        // the mode is sent when connecting: reconnect so Home Assistant updates its entities
        if (SpeakerService.running) restartSpeaker()
        tick++
    }

    override fun setChimeVolume(volume: Int) {
        cfg.chimeVolume = volume
    }

    override fun previewChime(volume: Int) = Chime.play(volume)

    override fun chooseOutput(name: String) {
        SpeakerService.instance?.chooseOutput(name)
        tick++
    }

    override fun unpair() {
        stopService(Intent(this, SpeakerService::class.java))
        cfg.unpair()
        tick++
    }

    private fun startSpeaker() {
        ContextCompat.startForegroundService(this, Intent(this, SpeakerService::class.java))
    }

    private fun restartSpeaker() {
        stopService(Intent(this, SpeakerService::class.java))
        startSpeaker()
    }
}
