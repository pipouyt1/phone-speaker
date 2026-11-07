package app.phonespeaker.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The current time, refreshed on every minute change (and kept exact to the second for the seconds-free clock). */
@Composable
fun rememberClock(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            // wake up right after the next minute starts
            delay(60_000L - (now.second * 1000L + now.nano / 1_000_000L) + 50L)
        }
    }
    return now
}

@Composable
fun timeText(now: LocalDateTime): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

fun dateText(now: LocalDateTime): String {
    val text = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale.getDefault()))
    return text.replaceFirstChar { it.uppercase() }
}
