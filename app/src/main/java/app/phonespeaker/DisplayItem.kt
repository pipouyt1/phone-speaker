package app.phonespeaker

/** Something Home Assistant asked this phone's screen to show. [kind] is image, video, web or text. */
data class DisplayItem(
    val id: Long,
    val kind: String,
    val url: String,
    val title: String,
    val text: String,
    /** Close by itself after this many seconds; 0 keeps it until closed (or the video ends). */
    val seconds: Int,
)
