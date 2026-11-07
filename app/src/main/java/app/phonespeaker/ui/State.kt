package app.phonespeaker.ui

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
)

/** What the screens can ask the app to do. */
interface AppActions {
    /** Pair with Home Assistant; [done] gets null on success or the error text. */
    fun pair(url: String, code: String, mode: String, done: (String?) -> Unit)
    fun toggleService()
    fun talk()
    fun setMode(mode: String)
    fun setChimeVolume(volume: Int)
    fun previewChime(volume: Int)
    fun chooseOutput(name: String)
    fun unpair()
}
