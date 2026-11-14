package app.phonespeaker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One screen per kind of failure: what is wrong, and what to check. */
private class Problem(
    val title: String,
    val text: String,
    val scene: Scene,
    /** The extra button above "Aplicaciones", if this failure has a fix to offer. */
    val fix: Fix? = null,
)

private enum class Fix(val label: String) {
    WifiSettings("Ajustes de WiFi"),
    AppSettings("Ir a los ajustes"),
    SignIn("Iniciar sesión nuevamente"),
}

private enum class Scene { ServerCut, Wifi, Lock, ServerWarning }

private fun problemFor(kind: String) = when (kind) {
    "network" -> Problem(
        "Sin red",
        "Este dispositivo no tiene internet, revisa que el WiFi esté encendido y conectado",
        Scene.Wifi,
        fix = Fix.WifiSettings,
    )
    "rejected" -> Problem(
        "Emparejamiento rechazado",
        "Home Assistant ya no reconoce este dispositivo, vuelve a emparejarlo desde los ajustes",
        Scene.Lock,
        fix = Fix.SignIn,
    )
    "integration" -> Problem(
        "Falta la integración",
        "Home Assistant responde, pero no encuentra Phone Speaker, revisa que la integración esté instalada y activa",
        Scene.ServerWarning,
        fix = Fix.AppSettings,
    )
    else -> Problem(
        "Sin conexión",
        "HA no tiene conexión, revisa si el computador está encendido y con fuente de electricidad",
        Scene.ServerCut,
    )
}

/**
 * Launcher mode, Home Assistant out of reach: a full-screen, landscape-style panel with a drawing and
 * advice that match the failure. A long press on the title opens the settings, so the device is never
 * locked out of them.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun OfflineScreen(
    kind: String,
    error: String?,
    onApps: () -> Unit,
    onSettings: () -> Unit,
    onWifiSettings: () -> Unit,
    onSignIn: () -> Unit,
) {
    val problem = problemFor(kind)
    val scheme = MaterialTheme.colorScheme
    // network and unreachable are reddish; a refused pairing or a missing integration are a warmer, calmer tone
    val warm = kind == "rejected" || kind == "integration"
    val background = if (warm) scheme.tertiaryContainer else scheme.errorContainer
    val content = if (warm) scheme.onTertiaryContainer else scheme.onErrorContainer
    CompositionLocalProvider(LocalContentColor provides content) {
        Row(
            Modifier
                .fillMaxSize()
                .background(background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 40.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Illustration(problem.scene, Modifier.weight(1f).aspectRatio(1.35f))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                Text(
                    problem.title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onSettings),
                )
                Text(problem.text, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "ERROR: ${error?.uppercase() ?: "TIMEOUT"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.8f),
                )
                problem.fix?.let { fix ->
                    val action = when (fix) {
                        Fix.WifiSettings -> onWifiSettings
                        Fix.AppSettings -> onSettings
                        Fix.SignIn -> onSignIn
                    }
                    Button(onClick = action, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Text(fix.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                }
                OutlinedButton(onClick = onApps, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("Aplicaciones", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The drawings, in Material 3 Expressive style: filled soft shapes and thick round strokes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Illustration(scene: Scene, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val cookie = remember { MaterialShapes.Cookie9Sided.normalized() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (scene) {
            Scene.ServerCut -> {
                computer(scheme, w, h)
                signal(scheme, Offset(w * 0.5f, h * 0.4f), h, arcs = 3)
                badge(scheme, cookie, Offset(w * 0.66f, h * 0.0f), h * 0.36f, error = true)
            }
            Scene.ServerWarning -> {
                computer(scheme, w, h)
                signal(scheme, Offset(w * 0.5f, h * 0.4f), h, arcs = 3)
                badge(scheme, cookie, Offset(w * 0.66f, h * 0.0f), h * 0.36f, error = false)
            }
            Scene.Wifi -> {
                signal(scheme, Offset(w * 0.5f, h * 0.78f), h * 1.55f, arcs = 3)
                badge(scheme, cookie, Offset(w * 0.6f, h * 0.0f), h * 0.4f, error = true)
            }
            Scene.Lock -> {
                lock(scheme, w, h)
                badge(scheme, cookie, Offset(w * 0.62f, h * 0.02f), h * 0.38f, error = true)
            }
        }
    }
}

private fun DrawScope.computer(scheme: ColorScheme, w: Float, h: Float) {
    val bodyTop = h * 0.46f
    val bodyHeight = h * 0.54f
    drawRoundRect(scheme.primaryContainer, Offset(0f, bodyTop), Size(w, bodyHeight), CornerRadius(h * 0.14f))
    drawRoundRect(
        scheme.secondaryContainer,
        Offset(w * 0.04f, bodyTop + bodyHeight * 0.58f), Size(w * 0.92f, bodyHeight * 0.34f),
        CornerRadius(h * 0.09f),
    )
    val portY = bodyTop + bodyHeight * 0.66f
    val portH = bodyHeight * 0.18f
    drawRoundRect(scheme.onSecondaryContainer.copy(alpha = 0.55f), Offset(w * 0.1f, portY), Size(w * 0.2f, portH), CornerRadius(portH))
    drawRoundRect(scheme.onSecondaryContainer.copy(alpha = 0.55f), Offset(w * 0.34f, portY), Size(w * 0.2f, portH), CornerRadius(portH))
    drawCircle(scheme.tertiary, portH * 0.95f, Offset(w * 0.86f, portY + portH / 2))
    drawCircle(scheme.onPrimaryContainer.copy(alpha = 0.7f), h * 0.035f, Offset(w * 0.1f, bodyTop + bodyHeight * 0.3f))
}

/** Thick arcs fanning out from [origin], fading with distance. [scale] is the height they are sized against. */
private fun DrawScope.signal(scheme: ColorScheme, origin: Offset, scale: Float, arcs: Int) {
    val thick = scale * 0.05f
    for (i in 0 until arcs) {
        val radius = scale * (0.13f + 0.11f * i)
        drawArc(
            scheme.outline.copy(alpha = 0.9f - i * 0.15f),
            startAngle = 225f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(origin.x - radius, origin.y - radius), size = Size(radius * 2, radius * 2),
            style = Stroke(width = thick, cap = StrokeCap.Round),
        )
    }
    drawCircle(scheme.outline, thick * 0.8f, origin)
}

private fun DrawScope.lock(scheme: ColorScheme, w: Float, h: Float) {
    val bodyW = w * 0.5f
    val left = (w - bodyW) / 2
    val top = h * 0.4f
    val thick = h * 0.07f
    // the shackle, a little open
    drawArc(
        scheme.outline, startAngle = 180f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(left + bodyW * 0.2f, top - bodyW * 0.3f), size = Size(bodyW * 0.6f, bodyW * 0.6f),
        style = Stroke(width = thick, cap = StrokeCap.Round),
    )
    drawRoundRect(scheme.primaryContainer, Offset(left, top), Size(bodyW, h * 0.6f), CornerRadius(h * 0.14f))
    drawCircle(scheme.onPrimaryContainer.copy(alpha = 0.7f), h * 0.07f, Offset(w / 2, top + h * 0.22f))
    drawRoundRect(
        scheme.onPrimaryContainer.copy(alpha = 0.7f),
        Offset(w / 2 - h * 0.025f, top + h * 0.22f), Size(h * 0.05f, h * 0.14f), CornerRadius(h * 0.025f),
    )
}

/** A cookie-shaped badge: red with a cross for an error, or an exclamation mark for a warning. */
private fun DrawScope.badge(scheme: ColorScheme, shape: androidx.graphics.shapes.RoundedPolygon, at: Offset, size: Float, error: Boolean) {
    val path = Path()
    var first = true
    for (cubic in shape.cubics) {
        if (first) { path.moveTo(cubic.anchor0X, cubic.anchor0Y); first = false }
        path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
    }
    path.close()
    val fill: Color = if (error) scheme.error else scheme.tertiary
    val ink: Color = if (error) scheme.onError else scheme.onTertiary
    withTransform({ translate(at.x, at.y); scale(size, size, Offset.Zero) }) { drawPath(path, fill) }
    val c = Offset(at.x + size / 2, at.y + size / 2)
    val stroke = size * 0.07f
    if (error) {
        val d = size * 0.17f
        drawLine(ink, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ink, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), strokeWidth = stroke, cap = StrokeCap.Round)
    } else {
        drawLine(ink, Offset(c.x, c.y - size * 0.2f), Offset(c.x, c.y + size * 0.05f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(ink, stroke * 0.7f, Offset(c.x, c.y + size * 0.19f))
    }
}
