@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.phonespeaker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.phonespeaker.DisplayItem
import app.phonespeaker.HaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Full-screen content sent from Home Assistant: an image, a video, a web page or a message. */
@Composable
fun DisplayOverlay(item: DisplayItem, onClose: () -> Unit) {
    LaunchedEffect(item.id) {
        if (item.seconds > 0) {
            delay(item.seconds * 1000L)
            onClose()
        }
    }
    Box(Modifier.fillMaxSize().background(if (item.kind == "text") MaterialTheme.colorScheme.surface else Color.Black)) {
        when (item.kind) {
            "image" -> RemoteImage(item.url)
            "video" -> RemoteVideo(item.url, onEnded = onClose)
            "web" -> RemoteWeb(item.url)
            else -> MessageCard(item)
        }
        if (item.title.isNotEmpty() && item.kind != "text") {
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(16.dp),
            )
        }
        FilledTonalIconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
        ) { Icon(Icons.Filled.Close, contentDescription = "Cerrar") }
    }
}

@Composable
private fun MessageCard(item: DisplayItem) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (item.title.isNotEmpty()) {
            Text(item.title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        }
        Text(item.text, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RemoteImage(url: String) {
    var failed by remember(url) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, url) {
        val bitmap = withContext(Dispatchers.IO) { loadBitmap(url) }
        if (bitmap == null) failed = true
        value = bitmap?.asImageBitmap()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = image
        when {
            shown != null -> Image(shown, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            failed -> Text("No se pudo cargar la imagen", color = Color.White)
            else -> LoadingIndicator(Modifier.size(64.dp))
        }
    }
}

/** Download an image, scaled down so a large camera snapshot does not exhaust memory. */
internal fun loadBitmap(url: String): Bitmap? = try {
    HaClient.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        val bytes = response.body?.bytes()
        if (!response.isSuccessful || bytes == null) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
} catch (e: Exception) {
    null
}

@Composable
private fun RemoteVideo(url: String, onEnded: () -> Unit) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(Uri.parse(url))
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { onEnded() }
                    setOnErrorListener { _, _, _ -> failed = true; true }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) Text("No se pudo reproducir el video", color = Color.White)
    }
}

@Composable
private fun RemoteWeb(url: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        // keep the page clear of the status and navigation bars
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        onRelease = { it.destroy() },
    )
}
