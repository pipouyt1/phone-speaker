package app.phonespeaker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phonespeaker.SpeakerService
import kotlinx.coroutines.delay

private val Active = setOf("listening", "thinking", "speaking", "error")

/** The assistant card for the window over other apps: reads what the service is doing a few times a second. */
@Composable
fun OverlayContent() {
    var phase by remember { mutableStateOf(SpeakerService.voicePhase) }
    var heard by remember { mutableStateOf(SpeakerService.lastHeard) }
    var reply by remember { mutableStateOf(SpeakerService.lastReply) }
    var level by remember { mutableStateOf(SpeakerService.micLevel) }
    var shown by remember { mutableStateOf("listening") }
    var prompt by remember { mutableStateOf(SpeakerService.prompt) }
    LaunchedEffect(Unit) {
        while (true) {
            phase = SpeakerService.voicePhase
            heard = SpeakerService.lastHeard
            reply = SpeakerService.lastReply
            level = SpeakerService.micLevel
            prompt = SpeakerService.prompt
            if (phase in Active) shown = phase
            delay(120)
        }
    }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    PhoneSpeakerTheme {
        AnimatedVisibility(visibleState = visible, enter = slideInVertically { -it } + fadeIn()) {
            Box(Modifier.padding(12.dp)) {
                val asking = prompt
                if (asking != null) PromptCard(asking, SpeakerService::answerPrompt, Modifier.fillMaxWidth())
                else AssistantCard(shown, heard, reply, level, Modifier.fillMaxWidth())
            }
        }
    }
}
