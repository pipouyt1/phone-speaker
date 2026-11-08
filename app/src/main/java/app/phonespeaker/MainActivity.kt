package app.phonespeaker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
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
        // after an update or a reboot the service is not running: bring the speaker back unless
        // the user stopped it on purpose
        if (cfg.paired && !SpeakerService.running && !cfg.userStopped) startSpeaker()
        setContent {
            PhoneSpeakerTheme {
                // re-read the service state a few times a second; actions bump [tick] to refresh at once
                val state by produceState(snapshot(), tick) {
                    while (true) {
                        value = snapshot()
                        delay(400)
                    }
                }
                // content from Home Assistant: show over the lock screen and keep the screen on
                LaunchedEffect(state.display != null) { applyDisplayWindow(state.display != null) }
                PhoneSpeakerApp(state, this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SpeakerService.uiVisible = true
        updateWindow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the system brings the bars back after some interactions: hide them again
        if (hasFocus) updateWindow()
    }

    private fun applyFullscreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        SpeakerService.uiVisible = false
        super.onStop()
    }

    private var displayActive = false
    private var roomShowing = false

    private fun applyDisplayWindow(active: Boolean) {
        displayActive = active
        setShowWhenLocked(active)
        setTurnScreenOn(active)
        updateWindow()
    }

    /** Fullscreen and keep-screen-on follow the settings, the room dashboard and shown content. */
    private fun updateWindow() {
        if (displayActive || roomShowing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyFullscreen(cfg.fullscreen || roomShowing)
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
            display = SpeakerService.screenItem,
            displayEnabled = cfg.display,
            overlayGranted = Settings.canDrawOverlays(this),
            fullscreen = cfg.fullscreen,
            launcher = cfg.launcher,
            room = cfg.room,
            roomUrl = cfg.roomUrl,
            reply = SpeakerService.lastReply,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // the microphone service type can only be declared once the permission exists
        if (cfg.voice && SpeakerService.running && SpeakerService.hasMicPermission(this)) restartSpeaker()
    }

    // ---- AppActions ----------------------------------------------------------------------

    override fun pair(url: String, code: String, mode: String, display: Boolean, done: (String?) -> Unit) {
        val baseUrl = HaClient.normalizeUrl(url)
        cfg.mode = mode
        cfg.display = display
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
        if (SpeakerService.running) {
            cfg.userStopped = true
            stopService(Intent(this, SpeakerService::class.java))
        } else {
            startSpeaker()
        }
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

    override fun setDisplayEnabled(enabled: Boolean) {
        if (enabled == cfg.display) return
        cfg.display = enabled
        // the option is sent when connecting: reconnect so Home Assistant adds or removes the entities
        if (SpeakerService.running) restartSpeaker()
        tick++
    }

    override fun dismissDisplay() {
        SpeakerService.dismissDisplay()
        tick++
    }

    override fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun setFullscreen(enabled: Boolean) {
        cfg.fullscreen = enabled
        updateWindow()
        tick++
    }

    override fun setRoom(enabled: Boolean) {
        cfg.room = enabled
        tick++
    }

    override fun setRoomUrl(url: String) {
        cfg.roomUrl = url
    }

    override fun setRoomShowing(showing: Boolean) {
        roomShowing = showing
        updateWindow()
    }

    override fun setLauncher(enabled: Boolean) {
        cfg.launcher = enabled
        tick++
    }

    override fun openHomeSettings() {
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun unpair() {
        stopService(Intent(this, SpeakerService::class.java))
        cfg.unpair()
        tick++
    }

    private fun startSpeaker() {
        cfg.userStopped = false
        ContextCompat.startForegroundService(this, Intent(this, SpeakerService::class.java))
    }

    private fun restartSpeaker() {
        stopService(Intent(this, SpeakerService::class.java))
        startSpeaker()
    }
}
