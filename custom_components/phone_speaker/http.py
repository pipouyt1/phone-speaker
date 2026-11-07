"""HTTP endpoints the phone talks to: one-time pairing, then a long-lived websocket."""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import secrets
import time
from typing import Any

from aiohttp import WSMsgType, web

from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from .const import (
    CONF_MODE,
    DOMAIN,
    MODES,
    PAIR_FAIL_LIMIT,
    PAIR_URL,
    WS_URL,
)

try:
    from homeassistant.helpers.http import KEY_HASS
except ImportError:  # older Home Assistant
    KEY_HASS = "hass"

_LOGGER = logging.getLogger(__name__)


async def async_ensure_http(hass: HomeAssistant) -> None:
    """Register the views once. Also called from the config flow, which runs before setup."""
    data = hass.data.setdefault(DOMAIN, {})
    data.setdefault("devices", {})
    data.setdefault("pending", {})
    if data.get("views"):
        return
    data["views"] = True
    hass.http.register_view(PairView())
    hass.http.register_view(WsView())


def _clean_str(value: Any, limit: int) -> str | None:
    if isinstance(value, str) and 0 < len(value) <= limit:
        return value
    return None


class PairView(HomeAssistantView):
    """Exchange the code shown in the config flow for a device token."""

    url = PAIR_URL
    name = "api:phone_speaker:pair"
    requires_auth = False

    async def post(self, request: web.Request) -> web.Response:
        hass: HomeAssistant = request.app[KEY_HASS]
        data = hass.data[DOMAIN]
        now = time.monotonic()

        fails = [t for t in data.setdefault("pair_fails", []) if now - t < 60]
        data["pair_fails"] = fails
        if len(fails) >= PAIR_FAIL_LIMIT:
            return self.json_message("Too many attempts", 429)

        try:
            body = await request.json()
        except ValueError:
            return self.json_message("Invalid JSON", 400)
        if not isinstance(body, dict):
            return self.json_message("Invalid body", 400)

        code = _clean_str(body.get("code"), 16)
        device_id = _clean_str(body.get("device_id"), 64)
        name = _clean_str(body.get("name"), 64)
        mode = body.get("mode")
        if not (code and device_id and name and mode in MODES):
            return self.json_message("Missing or invalid fields", 400)

        pending: dict[str, dict[str, Any]] = data["pending"]
        for stale in [c for c, p in pending.items() if p["expires"] < now]:
            pending.pop(stale)

        match = next(
            (p for c, p in pending.items() if hmac.compare_digest(c, code)), None
        )
        if match is None or match["result"] is not None:
            fails.append(now)
            return self.json_message("Invalid or expired code", 403)

        token = secrets.token_urlsafe(32)
        match["result"] = {
            "device_id": device_id,
            "name": name,
            "mode": mode,
            "token": token,
        }
        return self.json({"token": token})


class WsView(HomeAssistantView):
    """Long-lived connection initiated by the phone."""

    url = WS_URL
    name = "api:phone_speaker:ws"
    requires_auth = False

    async def get(self, request: web.Request) -> web.WebSocketResponse:
        hass: HomeAssistant = request.app[KEY_HASS]
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)

        device = None
        try:
            msg = await ws.receive(timeout=10)
            if msg.type != WSMsgType.TEXT:
                return ws
            hello = json.loads(msg.data)
            if not isinstance(hello, dict) or hello.get("type") != "hello":
                await ws.close(code=4400, message=b"bad hello")
                return ws

            device = _find_device(hass, hello.get("token"))
            if device is None:
                await asyncio.sleep(1)  # slow down token guessing
                await ws.close(code=4401, message=b"unauthorized")
                return ws

            mode = hello.get("mode")
            if mode in MODES and mode != device.entry.data.get(CONF_MODE):
                # Entity set depends on the mode: update the entry, which reloads it,
                # and let the phone reconnect into the new device object.
                hass.config_entries.async_update_entry(
                    device.entry, data={**device.entry.data, CONF_MODE: mode}
                )
                await ws.close(code=4000, message=b"mode changed")
                device = None
                return ws

            await device.async_attach(ws, hello)
            await ws.send_json({"type": "hello_ok"})

            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                try:
                    data = json.loads(msg.data)
                except ValueError:
                    continue
                if isinstance(data, dict):
                    device.handle_message(data)
        except (TimeoutError, ValueError):
            pass
        finally:
            if device is not None:
                device.detach(ws)
        return ws


def _find_device(hass: HomeAssistant, token: Any):
    if not isinstance(token, str):
        return None
    for device in hass.data[DOMAIN]["devices"].values():
        if hmac.compare_digest(device.token, token):
            return device
    return None
