@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.phonespeaker.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.phonespeaker.Config
import kotlinx.coroutines.delay

/** "/lovelace/0" is relative to Home Assistant; a full address is used as it is. */
fun resolveRoomUrl(baseUrl: String, input: String): String {
    val text = input.trim()
    return when {
        text.startsWith("http://") || text.startsWith("https://") -> text
        text.startsWith("/") -> baseUrl + text
        else -> "$baseUrl/$text"
    }
}

/** Room mode: the Home Assistant dashboard fills the screen and the assistant floats over it. */
@Composable
fun RoomScreen(state: UiState, onSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RoomWeb(resolveRoomUrl(state.baseUrl, state.roomUrl))
        AssistantFloat(state)
        // a quiet way back to the settings, out of the way of the dashboard
        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(6.dp)
                .size(36.dp)
                .alpha(0.45f)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RoomWeb(url: String) {
    val context = LocalContext.current
    val web = remember(url) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    // Home Assistant restarting or the network dropping: try again in a bit
                    if (request.isForMainFrame) view.postDelayed({ view.loadUrl(url) }, 15_000L)
                }
            }
            loadUrl(url)
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    // back moves through the dashboard instead of leaving the room
    BackHandler { if (web.canGoBack()) web.goBack() }
    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
}

private val ActivePhases = setOf("listening", "thinking", "speaking", "error")

/** The assistant over the dashboard: appears when someone talks to it and leaves a moment after it answers. */
@Composable
private fun BoxScope.AssistantFloat(state: UiState) {
    val voice = state.mode == Config.MODE_VOICE && state.running && state.connected
    val active = voice && state.phase in ActivePhases
    var lingering by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf("listening") }
    LaunchedEffect(state.phase) { if (state.phase in ActivePhases) shown = state.phase }
    LaunchedEffect(active) {
        if (active) {
            lingering = true
        } else {
            delay(4_000)
            lingering = false
        }
    }
    val visible = active || lingering

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp, start = 16.dp, end = 16.dp),
        enter = slideInVertically { -it * 2 } + fadeIn(),
        exit = slideOutVertically { -it * 2 } + fadeOut(),
    ) {
        AssistantCard(shown, state.lastHeard, state.reply, state.micLevel)
    }
}

/** The assistant card: listening with microphone bars, what it understood, its answer. Used over the dashboard and over other apps. */
@Composable
fun AssistantCard(
    phase: String,
    heard: String,
    reply: String,
    micLevel: Int,
    modifier: Modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.82f),
) {
    val (title, subtitle) = when (phase) {
        "listening" -> "Te escucho" to "Di tu pedido"
        "thinking" -> (if (heard.isNotEmpty()) "“$heard”" else "Entendiendo") to "Un momento..."
        "speaking" -> (reply.ifEmpty { "Respondiendo..." }) to ""
        else -> "Algo falló" to "Vuelve a intentarlo"
    }
    // a long message stretches the pill to fit it, with the expressive spring
    val long = title.length > 44
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.animateContentSize(spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = if (long) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VoiceOrb(phase = phase, micLevel = if (phase == "listening") micLevel else 0, size = 52.dp)
            // the text cross-fades when it changes; the pill itself does the growing and shrinking
            AnimatedContent(
                targetState = title to subtitle,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) { _, _ -> snap() }
                },
                label = "assistant text",
            ) { (text, hint) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 14, overflow = TextOverflow.Ellipsis)
                    if (hint.isNotEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (phase == "listening") LevelBars(micLevel)
        }
    }
}

/** Five bars that move with the microphone, so it is clear the device is hearing. */
@Composable
private fun LevelBars(level: Int) {
    val shape = listOf(0.45f, 0.75f, 1f, 0.75f, 0.45f)
    val energy = 0.25f + level.coerceIn(0, 100) / 100f * 0.75f
    Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        shape.forEach { weight ->
            val height by animateDpAsState((32.dp * weight * energy).coerceAtLeast(6.dp), label = "bar")
            Box(Modifier.size(width = 5.dp, height = height).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
        }
    }
}
