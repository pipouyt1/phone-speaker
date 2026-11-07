@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.phonespeaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phonespeaker.AudioOutputs
import app.phonespeaker.Config

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Filled.Home),
    Sound("Sonido", AppIcons.VolumeUp),
    Connection("Conexión", AppIcons.Link),
}

@Composable
fun PhoneSpeakerApp(state: UiState, actions: AppActions) {
    Box(Modifier.fillMaxSize()) {
        if (state.paired) {
            MainTabs(state, actions)
        } else {
            CenteredContent { OnboardingScreen(state, actions) }
        }
        // content sent from Home Assistant covers everything until it is closed
        state.display?.let { DisplayOverlay(it, actions::dismissDisplay) }
    }
}

/** On wide screens (tablets, landscape) keep the content to a comfortable width, centered. */
@Composable
private fun CenteredContent(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) { content() }
    }
}

@Composable
private fun MainTabs(state: UiState, actions: AppActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Home -> HomeScreen(state, actions)
                Tab.Sound -> CenteredContent { SoundScreen(state, actions) }
                Tab.Connection -> CenteredContent { ConnectionScreen(state, actions) }
            }
        }
    }
}

// ---- home --------------------------------------------------------------------------------

@Composable
private fun HomeScreen(state: UiState, actions: AppActions) {
    var showApps by rememberSaveable { mutableStateOf(false) }
    val voice = state.mode == Config.MODE_VOICE
    val connecting = state.running && !state.connected
    val phase = if (state.running && state.connected && voice) state.phase else "idle"

    val (title, subtitle) = when {
        !state.running -> "Detenido" to "Toca Iniciar para conectar con Home Assistant"
        connecting -> "Conectando" to (state.error ?: "Buscando Home Assistant")
        !voice -> "Parlante listo" to "Home Assistant puede reproducir audio aquí"
        else -> when (state.phase) {
            "listening" -> "Escuchando" to "Te estoy oyendo"
            "thinking" -> "Pensando" to "Un momento"
            "speaking" -> "Respondiendo" to "Hablando por el parlante"
            "error" -> "Algo falló" to "Reintentando en unos segundos"
            else -> "Te escucho" to "Di la palabra de activación"
        }
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // a tablet, or any screen that is wide and short: orb on one side, text and buttons on the other
            val wide = maxWidth > maxHeight && maxWidth >= 600.dp
            val orbSize = if (wide) minOf(maxHeight - 96.dp, 440.dp) else minOf(maxWidth * 0.62f, 320.dp)
            val align = if (wide) Alignment.Start else Alignment.CenterHorizontally
            val textAlign = if (wide) TextAlign.Start else TextAlign.Center

            val orb: @Composable () -> Unit = {
                if (connecting) {
                    Box(Modifier.size(orbSize), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(96.dp)) }
                } else {
                    VoiceOrb(phase = phase, micLevel = if (voice && state.running) state.micLevel else 0, size = orbSize)
                }
            }
            val info: @Composable ColumnScope.() -> Unit = {
                HomeHeader(state, compact = wide)
                Text(title, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = textAlign)
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign)
                if (voice && state.lastHeard.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Último comando", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("\"${state.lastHeard}\"", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                val buttonHeight = if (wide) 80.dp else 64.dp
                when {
                    !state.running -> BigButton("Iniciar", height = buttonHeight) { actions.toggleService() }
                    voice -> BigButton("Hablar ahora", enabled = state.connected, height = buttonHeight) { actions.talk() }
                }
                if (state.launcher) {
                    FilledTonalButton(
                        onClick = { showApps = true },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(if (wide) 64.dp else 56.dp),
                    ) { Text("Aplicaciones", style = MaterialTheme.typography.titleMedium) }
                }
            }

            if (wide) {
                Row(
                    Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 40.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { orb() }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = align,
                        content = info,
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    orb()
                    info()
                }
            }
        }
        if (showApps) AppsOverlay { showApps = false }
    }
}

/** The device name and connection status, or the big clock in launcher mode. */
@Composable
private fun ColumnScope.HomeHeader(state: UiState, compact: Boolean) {
    if (state.launcher) {
        val now = rememberClock()
        val clockSize = if (compact) 72.sp else 88.sp
        Text(timeText(now), style = MaterialTheme.typography.displayLarge.copy(fontSize = clockSize, lineHeight = clockSize * 1.1f))
        Text(dateText(now), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(state.deviceName, style = MaterialTheme.typography.titleLarge)
    }
    ConnectionChip(state)
}

@Composable
private fun ConnectionChip(state: UiState) {
    val ok = state.running && state.connected
    AssistChip(
        onClick = {},
        label = { Text(if (ok) "Conectado" else if (state.running) "Conectando" else "Detenido") },
        leadingIcon = {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
    )
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, height: Dp = 64.dp, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().height(height),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

// ---- sound -------------------------------------------------------------------------------

@Composable
private fun SoundScreen(state: UiState, actions: AppActions) {
    var chime by rememberSaveable(state.chimeVolume) { mutableStateOf(state.chimeVolume.toFloat()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Sonido", style = MaterialTheme.typography.headlineMediumEmphasized)

        SectionCard("Salida de audio") {
            state.outputs.forEach { name ->
                ListItem(
                    headlineContent = { Text(if (name == AudioOutputs.DEFAULT) "Predeterminada" else name) },
                    trailingContent = { RadioButton(selected = name == state.currentOutput, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { actions.chooseOutput(name) },
                )
            }
            if (!state.running) {
                Text(
                    "Inicia la app para ver las salidas disponibles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (state.mode == Config.MODE_VOICE) {
            SectionCard("Volumen del pitido") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Slider(
                        value = chime,
                        onValueChange = { chime = it; actions.setChimeVolume(it.toInt()) },
                        onValueChangeFinished = { actions.previewChime(chime.toInt()) },
                        valueRange = 0f..100f,
                    )
                    Text(
                        if (chime.toInt() == 0) "Apagado" else "${chime.toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard("Modo") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModePicker(state.mode, actions::setMode)
                Text(
                    modeDescription(state.mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("Pantalla") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow(
                    "Mostrar contenido de Home Assistant",
                    "Imágenes, videos, páginas web y mensajes que mandes a este dispositivo.",
                    state.displayEnabled, actions::setDisplayEnabled,
                )
                if (state.displayEnabled && !state.overlayGranted) {
                    Text(
                        "Para que el contenido se abra solo cuando la app está cerrada, permite \"Mostrar sobre otras apps\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = actions::requestOverlayPermission, shapes = ButtonDefaults.shapes()) {
                        Text("Dar permiso")
                    }
                }
                SwitchRow(
                    "Pantalla completa",
                    "Oculta la barra de estado y los botones de navegación. Desliza desde el borde para verlos.",
                    state.fullscreen, actions::setFullscreen,
                )
                SwitchRow(
                    "Modo launcher",
                    "La pantalla de inicio muestra un reloj grande y la lista de aplicaciones.",
                    state.launcher, actions::setLauncher,
                )
                if (state.launcher) {
                    FilledTonalButton(onClick = actions::openHomeSettings, shapes = ButtonDefaults.shapes()) {
                        Text("Usar como pantalla de inicio")
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            content()
        }
    }
}

private fun modeDescription(mode: String) = when (mode) {
    Config.MODE_GENERIC -> "Solo un parlante: Home Assistant reproduce audio aquí."
    Config.MODE_SMART -> "Parlante con selector de salida y sensor de batería en Home Assistant."
    else -> "Parlante inteligente: el micrófono siempre escucha y Home Assistant detecta la palabra de activación."
}

@Composable
private fun ModePicker(mode: String, onChange: (String) -> Unit) {
    val options = listOf(Config.MODE_GENERIC to "Simple", Config.MODE_SMART to "Inteligente", Config.MODE_VOICE to "Voz")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (value, label) ->
            ToggleButton(
                checked = mode == value,
                onCheckedChange = { onChange(value) },
                modifier = Modifier.weight(1f),
                // the card is surfaceContainer: give unselected buttons their own fill so they read as buttons
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label) }
        }
    }
}

// ---- connection --------------------------------------------------------------------------

@Composable
private fun ConnectionScreen(state: UiState, actions: AppActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Conexión", style = MaterialTheme.typography.headlineMediumEmphasized)
        SectionCard("Home Assistant") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.baseUrl, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        !state.running -> "Detenido"
                        state.connected -> "Conectado"
                        else -> state.error ?: "Conectando..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(
            onClick = actions::toggleService,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (state.running) "Detener" else "Iniciar") }
        OutlinedButton(
            onClick = actions::unpair,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Desemparejar") }
        Text(
            "Phone Speaker ${state.version}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- first use ---------------------------------------------------------------------------

@Composable
private fun OnboardingScreen(state: UiState, actions: AppActions) {
    var mode by rememberSaveable { mutableStateOf(state.mode) }
    var display by rememberSaveable { mutableStateOf(state.displayEnabled) }
    var address by rememberSaveable { mutableStateOf(state.baseUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Conectar con Home Assistant", style = MaterialTheme.typography.headlineMediumEmphasized)
        Text(
            "En Home Assistant: Ajustes > Dispositivos y servicios > Añadir integración > Phone Speaker. Te mostrará un código.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard("1. Elige el modo") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModePicker(mode) { mode = it }
                Text(modeDescription(mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow(
                    "Mostrar contenido de Home Assistant",
                    "Imágenes, videos, páginas web y mensajes que mandes a este dispositivo.",
                    display,
                ) { display = it }
            }
        }
        SectionCard("2. Dirección de Home Assistant") {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("192.168.1.50") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        SectionCard("3. Código") {
            CodeField(code, Modifier.padding(horizontal = 16.dp)) { code = it }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                busy = true
                error = null
                actions.pair(address, code, mode, display) { problem -> busy = false; error = problem }
            },
            enabled = !busy && address.isNotBlank() && code.length == 6,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            if (busy) LoadingIndicator(Modifier.size(32.dp)) else Text("Emparejar", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Six boxes for the pairing code, backed by one text field so the number keyboard just works. */
@Composable
private fun CodeField(value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) onChange(it) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier,
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { index ->
                    val current = index == value.length
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .border(
                                if (current) 2.dp else 1.dp,
                                if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.shapes.medium,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(value.getOrNull(index)?.toString() ?: "", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
    )
}
