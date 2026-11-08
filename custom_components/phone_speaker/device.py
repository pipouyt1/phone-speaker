"""A paired phone and its live websocket connection."""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Callable
import logging
from typing import Any

from aiohttp import web

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo

from .const import (
    CONF_DEVICE_ID,
    CONF_DISPLAY,
    CONF_MODE,
    CONF_TOKEN,
    DOMAIN,
    EVENT_ANSWER,
    PLAYBACK_TIMEOUT,
)

_LOGGER = logging.getLogger(__name__)


class PhoneDevice:
    """State and command channel for one phone.

    The phone dials Home Assistant (see http.py), so this object only exists as a
    place for entities to read state from and to send commands through.
    """

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self.hass = hass
        self.entry = entry
        self.device_id: str = entry.data[CONF_DEVICE_ID]
        self.token: str = entry.data[CONF_TOKEN]
        self.mode: str = entry.data[CONF_MODE]
        self.display: bool = bool(entry.data.get(CONF_DISPLAY, False))
        self.name: str = entry.title
        self.manufacturer: str | None = None
        self.model: str | None = None
        self.ws: web.WebSocketResponse | None = None
        self.state: dict[str, Any] = {
            "player": {"state": "idle", "volume": 1.0, "muted": False},
            "battery": None,
            "outputs": [],
            "output": None,
        }
        self._listeners: set[Callable[[], None]] = set()

        # Voice: set by the Assist satellite entity when it exists.
        self.voice_listener: Callable[[dict[str, Any]], None] | None = None
        self.audio_sink: Callable[[bytes], None] | None = None

        self._playback_done: asyncio.Event | None = None
        self._saw_playing = False
        self._play_failed = False
        # questions on the phone waiting for an answer: id -> (future, question, options, entity_id)
        self._prompts: dict[str, tuple[asyncio.Future[dict[str, Any]], str, list[str], str | None]] = {}

    @property
    def connected(self) -> bool:
        return self.ws is not None and not self.ws.closed

    @property
    def device_info(self) -> DeviceInfo:
        return DeviceInfo(
            identifiers={(DOMAIN, self.device_id)},
            name=self.name,
            manufacturer=self.manufacturer,
            model=self.model,
        )

    @callback
    def add_listener(self, listener: Callable[[], None]) -> Callable[[], None]:
        self._listeners.add(listener)

        @callback
        def remove() -> None:
            self._listeners.discard(listener)

        return remove

    @callback
    def _notify(self) -> None:
        for listener in list(self._listeners):
            listener()

    async def async_attach(self, ws: web.WebSocketResponse, hello: dict[str, Any]) -> None:
        """Adopt a freshly authenticated connection, dropping any previous one."""
        old = self.ws
        self.ws = ws
        self.manufacturer = hello.get("manufacturer") or self.manufacturer
        self.model = hello.get("model") or self.model
        if old is not None and not old.closed:
            await old.close(code=4000, message=b"replaced")
        _LOGGER.info("Phone %s connected", self.name)
        self._notify()

    @callback
    def detach(self, ws: web.WebSocketResponse) -> None:
        if self.ws is ws:
            self.ws = None
            if self._playback_done is not None:
                self._playback_done.set()
            for future, *_ in self._prompts.values():
                if not future.done():
                    future.set_result({"answered": False, "index": None, "option": None, "reason": "disconnected"})
            _LOGGER.info("Phone %s disconnected", self.name)
            self._notify()

    @callback
    def handle_message(self, data: dict[str, Any]) -> None:
        kind = data.get("type")
        if kind == "state":
            self._handle_state(data)
        elif kind == "play_skipped":
            # the phone chose not to play it (for example, the command was handled on the phone):
            # nothing to wait for
            if self._playback_done is not None:
                self._playback_done.set()
        elif kind == "prompt_answer":
            self._handle_answer(data)
        elif kind == "play_error":
            self._play_failed = True
            if self._playback_done is not None:
                self._playback_done.set()
        elif kind in ("voice_start", "voice_stop") and self.voice_listener is not None:
            self.voice_listener(data)

    @callback
    def _handle_answer(self, data: dict[str, Any]) -> None:
        pending = self._prompts.get(str(data.get("id")))
        if pending is None:
            return
        future, question, options, entity_id = pending
        index = data.get("index")
        answered = isinstance(index, int) and 0 <= index < len(options)
        result = {
            "answered": answered,
            "index": index if answered else None,
            "option": options[index] if answered else None,
            "reason": None if answered else (data.get("reason") or "dismissed"),
        }
        if not future.done():
            future.set_result(result)
        self.hass.bus.async_fire(
            EVENT_ANSWER,
            {"entity_id": entity_id, "id": data.get("id"), "question": question, **result},
        )

    async def async_ask(
        self,
        question: str,
        options: list[str],
        *,
        timeout: int,
        wait: bool,
        entity_id: str | None = None,
        media: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Show a question with 2-4 buttons on the phone and, if asked, wait for the answer."""
        prompt_id = uuid.uuid4().hex[:12]
        future: asyncio.Future[dict[str, Any]] = self.hass.loop.create_future()
        self._prompts[prompt_id] = (future, question, options, entity_id)
        try:
            payload: dict[str, Any] = {
                "type": "prompt",
                "id": prompt_id,
                "question": question,
                "options": options,
                "timeout": timeout,
            }
            if media is not None:
                payload["media"] = media
            await self.async_send(payload)
            if not wait:
                # keep it registered so the answer still fires the event
                self.hass.loop.call_later(timeout + 10 if timeout else 3600, self._prompts.pop, prompt_id, None)
                return {"id": prompt_id}
            try:
                result = await asyncio.wait_for(future, timeout + 10 if timeout else None)
            except TimeoutError:
                result = {"answered": False, "index": None, "option": None, "reason": "timeout"}
            return {"id": prompt_id, **result}
        except BaseException:
            self._prompts.pop(prompt_id, None)
            raise
        finally:
            if wait:
                self._prompts.pop(prompt_id, None)

    @callback
    def handle_audio(self, chunk: bytes) -> None:
        """Microphone audio from the phone (16 kHz, mono, 16-bit PCM)."""
        if self.audio_sink is not None:
            self.audio_sink(chunk)

    @callback
    def _handle_state(self, data: dict[str, Any]) -> None:
        for key in ("player", "battery", "outputs", "output"):
            if key in data:
                self.state[key] = data[key]
        player = self.state.get("player")
        current = player.get("state") if isinstance(player, dict) else None
        if current == "playing":
            self._saw_playing = True
        elif self._saw_playing and self._playback_done is not None:
            self._playback_done.set()
        self._notify()

    async def async_send(self, payload: dict[str, Any]) -> None:
        if not self.connected:
            raise HomeAssistantError(f"{self.name} is not connected")
        assert self.ws is not None
        await self.ws.send_json(payload)

    @callback
    def send_soon(self, payload: dict[str, Any]) -> None:
        """Fire and forget, for use from synchronous callbacks."""

        async def _send() -> None:
            try:
                await self.async_send(payload)
            except (HomeAssistantError, ConnectionError):
                pass

        self.entry.async_create_background_task(self.hass, _send(), "phone_speaker_send")

    async def async_play_and_wait(self, url: str, *, announce: bool = False) -> None:
        """Play a URL and return once the phone reports that playback ended."""
        done = asyncio.Event()
        self._playback_done = done
        self._saw_playing = False
        self._play_failed = False
        await self.async_send({"type": "play", "url": url, "announce": announce})
        try:
            await asyncio.wait_for(done.wait(), PLAYBACK_TIMEOUT)
        except TimeoutError:
            _LOGGER.debug("Timed out waiting for %s to finish playing", self.name)
        finally:
            if self._playback_done is done:
                self._playback_done = None
        if self._play_failed:
            raise HomeAssistantError(f"{self.name} could not play {url}")

    async def async_show(
        self,
        kind: str,
        *,
        url: str | None = None,
        title: str | None = None,
        text: str | None = None,
        seconds: int | None = None,
    ) -> None:
        """Show something on the phone's screen: kind is image, video, web or text."""
        payload: dict[str, Any] = {"type": "display", "kind": kind}
        for key, value in (("url", url), ("title", title), ("text", text), ("seconds", seconds)):
            if value is not None:
                payload[key] = value
        await self.async_send(payload)

    async def async_clear_display(self) -> None:
        await self.async_send({"type": "display_clear"})

    async def async_close(self) -> None:
        if self.ws is not None and not self.ws.closed:
            await self.ws.close(code=4000, message=b"reloading")
        self.ws = None
