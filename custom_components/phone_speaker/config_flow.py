"""Config flow: show a pairing code, the phone enters it, then the user confirms."""

from __future__ import annotations

import secrets
import time
from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .const import (
    CONF_DEVICE_ID,
    CONF_DISPLAY,
    CONF_MODE,
    CONF_TOKEN,
    DOMAIN,
    PAIR_CODE_TTL,
)
from .http import async_ensure_http


class PhoneSpeakerConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle pairing a phone."""

    VERSION = 1

    _code: str | None = None

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        await async_ensure_http(self.hass)
        pending: dict[str, dict[str, Any]] = self.hass.data[DOMAIN]["pending"]

        if self._code is None:
            self._code = f"{secrets.randbelow(10**6):06d}"
            pending[self._code] = {
                "expires": time.monotonic() + PAIR_CODE_TTL,
                "result": None,
            }

        errors: dict[str, str] = {}
        if user_input is not None:
            entry = pending.get(self._code)
            if entry is not None and entry["result"] is not None:
                result = entry["result"]
                pending.pop(self._code, None)
                data = {
                    CONF_DEVICE_ID: result["device_id"],
                    CONF_TOKEN: result["token"],
                    CONF_MODE: result["mode"],
                    CONF_DISPLAY: result.get("display", False),
                }
                await self.async_set_unique_id(result["device_id"])
                # Pairing the same phone again just replaces its token and mode.
                self._abort_if_unique_id_configured(updates=data)
                return self.async_create_entry(title=result["name"], data=data)
            errors["base"] = "not_paired"
            if entry is None:  # expired: issue a fresh code
                self._code = None
                return await self.async_step_user()

        try:
            address = get_url(self.hass, prefer_external=False)
        except NoURLAvailableError:
            address = "http://<IP de Home Assistant>"

        return self.async_show_form(
            step_id="user",
            data_schema=vol.Schema({}),
            description_placeholders={"code": self._code, "address": address},
            errors=errors,
        )

    def async_remove(self) -> None:
        """Forget the code when the flow is closed."""
        if self._code is not None:
            self.hass.data.get(DOMAIN, {}).get("pending", {}).pop(self._code, None)
