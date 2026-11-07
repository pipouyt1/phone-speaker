"""Audio output selector (smart mode only)."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, CONF_MODE, DOMAIN, MODE_SMART
from .entity import PhoneEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    if entry.data[CONF_MODE] != MODE_SMART:
        return
    device = hass.data[DOMAIN]["devices"][entry.data[CONF_DEVICE_ID]]
    async_add_entities([AudioOutputSelect(device)])


class AudioOutputSelect(PhoneEntity, SelectEntity):
    """Choose where the phone sends audio (speaker, Bluetooth, wired...)."""

    _attr_name = "Audio output"
    _attr_icon = "mdi:speaker-bluetooth"

    def __init__(self, device) -> None:
        super().__init__(device, "audio_output")

    @property
    def options(self) -> list[str]:
        outputs = self.device.state.get("outputs")
        if isinstance(outputs, list) and outputs:
            return [str(o) for o in outputs]
        return ["Default"]

    @property
    def current_option(self) -> str | None:
        current = self.device.state.get("output")
        return current if current in self.options else None

    async def async_select_option(self, option: str) -> None:
        await self.device.async_send({"type": "output", "name": option})
