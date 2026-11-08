package app.phonespeaker.ui

import app.phonespeaker.DisplayItem

/** Everything the screens show, read from the service and the saved settings. */
data class UiState(
    val paired: Boolean,
    val running: Boolean,
    val connected: Boolean,
    val error: String?,
    val phase: String,
    val lastHeard: String,
    val micLevel: Int,
    val mode: String,
    val chimeVolume: Int,
    val outputs: List<String>,
    val currentOutput: String,
    val baseUrl: String,
    val deviceName: String,
    val version: String,
    val display: DisplayItem?,
    val displayEnabled: Boolean,
    val overlayGranted: Boolean,
    val fullscreen: Boolean,
    val launcher: Boolean,
    val floating: Boolean,
    val room: Boolean,
    val roomUrl: String,
    val reply: String,
)

/** What the screens can ask the app to do. */
interface AppActions {
    /** Pair with Home Assistant; [done] gets null on success or the error text. */
    fun pair(url: String, code: String, mode: String, display: Boolean, done: (String?) -> Unit)
    fun toggleService()
    fun talk()
    fun setMode(mode: String)
    fun setChimeVolume(volume: Int)
    fun previewChime(volume: Int)
    fun chooseOutput(name: String)
    fun unpair()
    fun setDisplayEnabled(enabled: Boolean)
    fun dismissDisplay()
    fun requestOverlayPermission()
    fun setFullscreen(enabled: Boolean)
    fun setLauncher(enabled: Boolean)
    fun setFloating(enabled: Boolean)
    fun setRoom(enabled: Boolean)
    fun setRoomUrl(url: String)
    /** The room dashboard is on screen: hide the system bars and keep the screen on. */
    fun setRoomShowing(showing: Boolean)
    /** Open Android's "default home app" chooser. */
    fun openHomeSettings()
}
