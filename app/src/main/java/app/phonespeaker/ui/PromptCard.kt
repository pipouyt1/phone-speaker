package app.phonespeaker.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import app.phonespeaker.PromptMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phonespeaker.PromptItem

/** A question from Home Assistant in the same pill as the assistant, with a button per answer. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PromptCard(item: PromptItem, onAnswer: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.animateContentSize(spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item.media?.let { PromptMediaView(it) }
            Text(item.question, style = MaterialTheme.typography.titleLarge, maxLines = 10)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.options.forEachIndexed { index, label ->
                    if (index == 0) {
                        Button(onClick = { onAnswer(index) }, shapes = ButtonDefaults.shapes()) { Text(label) }
                    } else {
                        FilledTonalButton(onClick = { onAnswer(index) }, shapes = ButtonDefaults.shapes()) { Text(label) }
                    }
                }
            }
        }
    }
}

/** The picture, live camera snapshot or video above a question. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromptMediaView(media: PromptMedia) {
    val shape = RoundedCornerShape(20.dp)
    if (media.kind == "video") {
        var failed by remember(media.url) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(Uri.parse(media.url))
                        setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); it.start() }
                        setOnErrorListener { _, _, _ -> failed = true; true }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (failed) Text("No se pudo cargar el video", color = Color.White)
        }
        return
    }
    var failed by remember(media.url) { mutableStateOf(false) }
    // a camera is reloaded every few seconds, keeping the last picture until the next one arrives
    val image by produceState<ImageBitmap?>(null, media.url) {
        while (true) {
            val bitmap = withContext(Dispatchers.IO) { loadBitmap(media.url) }
            if (bitmap != null) { value = bitmap.asImageBitmap(); failed = false } else if (value == null) failed = true
            if (media.refresh <= 0) break
            delay(media.refresh * 1000L)
        }
    }
    val shown = image
    val ratio = shown?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    // as wide as the card, but never taller than 280 dp: a tall picture is narrower instead
    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(ratio).clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                shown != null -> Image(shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                failed -> Text("No se pudo cargar la imagen", color = Color.White)
                else -> LoadingIndicator(Modifier.size(48.dp))
            }
        }
    }
}
