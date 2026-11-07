"""A paired phone and its live websocket connection."""

from __future__ import annotations

from collections.abc import Callable
import logging
from typing import Any

from aiohttp import web

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo

from .const import CONF_DEVICE_ID, CONF_MODE, CONF_TOKEN, DOMAIN

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
            _LOGGER.info("Phone %s disconnected", self.name)
            self._notify()

    @callback
    def handle_message(self, data: dict[str, Any]) -> None:
        if data.get("type") != "state":
            return
        for key in ("player", "battery", "outputs", "output"):
            if key in data:
                self.state[key] = data[key]
        self._notify()

    async def async_send(self, payload: dict[str, Any]) -> None:
        if not self.connected:
            raise HomeAssistantError(f"{self.name} is not connected")
        assert self.ws is not None
        await self.ws.send_json(payload)

    async def async_close(self) -> None:
        if self.ws is not None and not self.ws.closed:
            await self.ws.close(code=4000, message=b"reloading")
        self.ws = None
