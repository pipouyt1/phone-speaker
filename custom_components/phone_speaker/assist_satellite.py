"""Assist satellite: the phone's microphone and speaker as a voice assistant (voice mode)."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
import logging
import time
from typing import Any

from homeassistant.components import wake_word
from homeassistant.components.assist_pipeline import (
    PipelineEvent,
    PipelineEventType,
    PipelineStage,
)
from homeassistant.components.assist_satellite import (
    AssistSatelliteAnnouncement,
    AssistSatelliteConfiguration,
    AssistSatelliteEntity,
    AssistSatelliteEntityDescription,
    AssistSatelliteEntityFeature,
    AssistSatelliteWakeWord,
)
from homeassistant.components.media_player import async_process_play_media_url
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.storage import Store

from .const import CONF_DEVICE_ID, CONF_MODE, DOMAIN, MODE_VOICE, VOICE_QUEUE_CHUNKS
from .device import PhoneDevice
from .entity import PhoneEntity

_LOGGER = logging.getLogger(__name__)

# pipeline event -> short name shown on the phone
_EVENTS = {
    PipelineEventType.STT_START: "listening",
    PipelineEventType.STT_END: "heard",
    PipelineEventType.INTENT_START: "thinking",
    PipelineEventType.TTS_START: "speaking",
    PipelineEventType.ERROR: "error",
}


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    if entry.data[CONF_MODE] != MODE_VOICE:
        return
    device = hass.data[DOMAIN]["devices"][entry.data[CONF_DEVICE_ID]]
    async_add_entities([PhoneAssistSatellite(device)])


class PhoneAssistSatellite(PhoneEntity, AssistSatelliteEntity):
    """Streams the phone's microphone into an Assist pipeline and plays the reply.

    The phone decides when to start: on a button press (the pipeline starts at the
    speech-to-text stage) or continuously (the phone streams the microphone and this
    entity looks for the wake word with the wake word engine installed in Home Assistant,
    for example openWakeWord, before handing the rest of the audio to the pipeline).
    """

    _unrecorded_attributes = frozenset(
        {"audio_chunks", "audio_level", "wake_word_engine", "wake_word", "last_wake_word"}
    )
    entity_description = AssistSatelliteEntityDescription(key="assist_satellite")
    _attr_name = "Assistant"
    _attr_icon = "mdi:microphone-message"
    _attr_supported_features = AssistSatelliteEntityFeature.ANNOUNCE

    def __init__(self, device: PhoneDevice) -> None:
        super().__init__(device, "assistant")
        self._queue: asyncio.Queue[bytes | None] | None = None
        self._run_task: asyncio.Task | None = None
        self._store = Store[dict[str, Any]](device.hass, 1, f"{DOMAIN}_{device.device_id}")
        self._active_wake_word: str | None = None
        self._available_wake_words: list[wake_word.WakeWord] = []
        self._audio_chunks = 0
        self._audio_level = 0.0
        self._last_state_write = 0.0
        self._last_wake_word: str | None = None

    # --- Assist pipeline / VAD selects live next to this entity ------------------

    def _select_entity_id(self, suffix: str) -> str | None:
        return er.async_get(self.hass).async_get_entity_id(
            Platform.SELECT, DOMAIN, f"{self.device.device_id}-{suffix}"
        )

    @property
    def pipeline_entity_id(self) -> str | None:
        return self._select_entity_id("pipeline")

    @property
    def vad_sensitivity_entity_id(self) -> str | None:
        return self._select_entity_id("vad_sensitivity")

    # --- lifecycle ---------------------------------------------------------------

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.device.voice_listener = self._on_voice_message
        self.device.audio_sink = self._on_audio
        stored = await self._store.async_load()
        if isinstance(stored, dict):
            self._active_wake_word = stored.get("wake_word")
        await self._async_refresh_wake_words()

    async def async_will_remove_from_hass(self) -> None:
        self.device.voice_listener = None
        self.device.audio_sink = None
        self._end_stream()
        await super().async_will_remove_from_hass()

    # --- wake word ---------------------------------------------------------------

    def _wake_word_entity(self) -> wake_word.WakeWordDetectionEntity | None:
        entity_id = wake_word.async_default_entity(self.hass)
        if entity_id is None:
            return None
        return wake_word.async_get_wake_word_detection_entity(self.hass, entity_id)

    async def _async_refresh_wake_words(self) -> None:
        engine = self._wake_word_entity()
        if engine is None:
            self._available_wake_words = []
            return
        try:
            self._available_wake_words = await engine.get_supported_wake_words()
        except Exception:  # the engine (e.g. an add-on) may be starting up
            _LOGGER.debug("Could not list wake words", exc_info=True)

    @property
    def _wake_word_choice(self) -> wake_word.WakeWord | None:
        """The wake word to listen for: the chosen one, else the first the engine offers."""
        for candidate in self._available_wake_words:
            if candidate.id == self._active_wake_word:
                return candidate
        return self._available_wake_words[0] if self._available_wake_words else None

    @callback
    def async_get_configuration(self) -> AssistSatelliteConfiguration:
        languages = [self.hass.config.language]
        choice = self._wake_word_choice
        return AssistSatelliteConfiguration(
            available_wake_words=[
                AssistSatelliteWakeWord(id=w.id, wake_word=w.phrase or w.name, trained_languages=languages)
                for w in self._available_wake_words
            ],
            active_wake_words=[choice.id] if choice else [],
            max_active_wake_words=1,
        )

    async def async_set_configuration(self, config: AssistSatelliteConfiguration) -> None:
        self._active_wake_word = config.active_wake_words[0] if config.active_wake_words else None
        await self._store.async_save({"wake_word": self._active_wake_word})

    async def _async_wait_for_wake_word(self, stream: AsyncIterator[bytes]) -> str | None:
        """Consume audio until the wake word is heard; returns its phrase, or None if the stream ended."""
        await self._async_refresh_wake_words()
        engine = self._wake_word_entity()
        choice = self._wake_word_choice
        if engine is None or choice is None:
            raise LookupError("no wake word engine")

        started = time.monotonic()

        async def timed() -> AsyncIterator[tuple[bytes, int]]:
            async for chunk in stream:
                yield chunk, int((time.monotonic() - started) * 1000)

        result = await engine.async_process_audio_stream(timed(), choice.id)
        if result is None:
            return None
        return choice.phrase or choice.name

    # --- phone -> Home Assistant -------------------------------------------------

    @callback
    def _on_voice_message(self, data: dict[str, Any]) -> None:
        if data.get("type") == "voice_start":
            self._start_run(wake_word_stage=data.get("stage") == "wake_word", run=data.get("run"))
        else:  # voice_stop
            self._end_stream()

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """What Home Assistant is receiving from the phone, to debug wake word problems."""
        choice = self._wake_word_choice
        return {
            "audio_chunks": self._audio_chunks,
            "audio_level": round(self._audio_level, 1),
            "wake_word_engine": wake_word.async_default_entity(self.hass),
            "wake_word": choice.id if choice else None,
            "last_wake_word": self._last_wake_word,
        }

    @callback
    def _on_audio(self, chunk: bytes) -> None:
        self._audio_chunks += 1
        samples = memoryview(chunk[: len(chunk) // 2 * 2]).cast("h")
        if len(samples):
            rms = (sum(s * s for s in samples) / len(samples)) ** 0.5
            self._audio_level = rms / 327.68  # percent of full scale
        now = time.monotonic()
        if self.entity_id and now - self._last_state_write > 10:
            self._last_state_write = now
            self.async_write_ha_state()
        if self._queue is None:
            return
        try:
            self._queue.put_nowait(chunk)
        except asyncio.QueueFull:
            pass  # the pipeline fell behind: drop audio rather than grow without bound

    @callback
    def _start_run(self, *, wake_word_stage: bool, run: int | None) -> None:
        self._end_stream()
        queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=VOICE_QUEUE_CHUNKS)
        self._queue = queue
        self._run_task = self.device.entry.async_create_background_task(
            self.hass, self._run(queue, wake_word_stage, run), "phone_speaker_voice"
        )

    @callback
    def _end_stream(self) -> None:
        """Stop feeding the running pipeline; it finishes on its own."""
        queue, self._queue = self._queue, None
        if queue is not None:
            try:
                queue.put_nowait(None)
            except asyncio.QueueFull:
                if self._run_task is not None:
                    self._run_task.cancel()

    async def _run(
        self, queue: asyncio.Queue[bytes | None], wake_word_stage: bool, run: int | None
    ) -> None:
        async def stream() -> AsyncIterator[bytes]:
            while (chunk := await queue.get()) is not None:
                yield chunk

        audio = stream()
        try:
            phrase: str | None = None
            if wake_word_stage:
                try:
                    phrase = await self._async_wait_for_wake_word(audio)
                except LookupError:
                    # No engine of our own: let the pipeline's own wake word settings decide.
                    await self.async_accept_pipeline_from_satellite(
                        audio, start_stage=PipelineStage.WAKE_WORD
                    )
                    return
                if phrase is None:
                    return  # stream ended without a wake word
                waiting = self._wake_word_intercept_future
                if waiting is not None and not waiting.done():
                    # Setup wizard: "say the wake word" step, no conversation wanted.
                    waiting.set_result(phrase)
                    return
                self._last_wake_word = phrase
                self.device.send_soon({"type": "voice_event", "event": "wake_word"})
            await self.async_accept_pipeline_from_satellite(
                audio, start_stage=PipelineStage.STT, wake_word_phrase=phrase
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            _LOGGER.exception("Assist pipeline failed for %s", self.device.name)
            self.device.send_soon(
                {"type": "voice_event", "event": "error", "message": "pipeline failed"}
            )
        finally:
            if self._queue is queue:
                self._queue = None
            self.device.send_soon({"type": "voice_end", "run": run})

    # --- Home Assistant -> phone -------------------------------------------------

    @callback
    def on_pipeline_event(self, event: PipelineEvent) -> None:
        if event.type == PipelineEventType.TTS_END:
            tts_output = (event.data or {}).get("tts_output")
            if tts_output:
                url = async_process_play_media_url(
                    self.hass, tts_output["url"], allow_relative_url=True
                )
                self.device.entry.async_create_background_task(
                    self.hass, self._play_reply(url), "phone_speaker_tts"
                )
            return

        name = _EVENTS.get(event.type)
        if name is None:
            return
        payload: dict[str, Any] = {"type": "voice_event", "event": name}
        data = event.data or {}
        if event.type == PipelineEventType.STT_END:
            payload["text"] = data.get("stt_output", {}).get("text", "")
        elif event.type == PipelineEventType.ERROR:
            payload["message"] = data.get("message", "")
            payload["code"] = data.get("code", "")
        self.device.send_soon(payload)

    async def _play_reply(self, url: str) -> None:
        try:
            await self.device.async_play_and_wait(url, announce=True)
        except Exception:
            _LOGGER.warning("Could not play the reply on %s", self.device.name, exc_info=True)
        finally:
            self.tts_response_finished()

    async def async_announce(self, announcement: AssistSatelliteAnnouncement) -> None:
        """Play an announcement, blocking until it has finished."""
        if announcement.preannounce_media_id:
            await self.device.async_play_and_wait(
                async_process_play_media_url(
                    self.hass, announcement.preannounce_media_id, allow_relative_url=True
                ),
                announce=True,
            )
        await self.device.async_play_and_wait(
            async_process_play_media_url(
                self.hass, announcement.media_id, allow_relative_url=True
            ),
            announce=True,
        )
