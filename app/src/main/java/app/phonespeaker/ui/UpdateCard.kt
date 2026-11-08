@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.phonespeaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.phonespeaker.Release
import app.phonespeaker.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
    data object NeedsPermission : UpdateState
}

/** Checks the project's GitHub releases and installs a newer APK. */
@Composable
fun UpdateCard(currentVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { Updater.check(currentVersion) }
            state = result.fold(
                onSuccess = { it?.let(UpdateState::Available) ?: UpdateState.UpToDate },
                onFailure = { UpdateState.Failed(it.message ?: "Error") },
            )
        }
    }

    fun install(release: Release) {
        if (!Updater.ensureCanInstall(context)) {
            state = UpdateState.NeedsPermission
            return
        }
        state = UpdateState.Downloading(0f)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                Updater.download(context, release) { p -> state = UpdateState.Downloading(p) }
            }
            result.fold(
                onSuccess = { Updater.install(context, it); state = UpdateState.Available(release) },
                onFailure = { state = UpdateState.Failed(it.message ?: "Error") },
            )
        }
    }

    SectionCard("Actualizaciones") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when (val s = state) {
                UpdateState.Idle -> Text("Versión instalada: $currentVersion", style = MaterialTheme.typography.bodyMedium, color = muted)
                UpdateState.Checking -> Text("Buscando...", style = MaterialTheme.typography.bodyMedium, color = muted)
                UpdateState.UpToDate -> Text("Ya tienes la última versión ($currentVersion).", style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Available -> {
                    Text("Hay una versión nueva: ${s.release.version}", style = MaterialTheme.typography.titleSmall)
                    if (s.release.notes.isNotEmpty()) {
                        Text(s.release.notes, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 6)
                    }
                }
                is UpdateState.Downloading -> {
                    Text("Descargando...", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                UpdateState.NeedsPermission -> Text(
                    "Permite que Phone Speaker instale apps en la pantalla que se abrió, vuelve y pulsa de nuevo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val available = (state as? UpdateState.Available)?.release
            if (available != null) {
                Button(onClick = { install(available) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Descargar e instalar")
                }
            } else if (state is UpdateState.NeedsPermission) {
                Button(onClick = { state = UpdateState.Idle; check() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Ya lo permití")
                }
            } else if (state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                FilledTonalButton(onClick = { check() }, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Buscar actualizaciones")
                }
            }
        }
    }
}
