package app.phonespeaker

/** A question Home Assistant asked, with 2-4 buttons to answer it. [until] is an elapsedRealtime deadline, 0 for none. */
data class PromptItem(
    val id: String,
    val question: String,
    val options: List<String>,
    val until: Long,
    val media: PromptMedia? = null,
)

/** A picture or video shown above the question. [refresh] > 0 reloads an image every that many seconds (cameras). */
data class PromptMedia(val kind: String, val url: String, val refresh: Int)
