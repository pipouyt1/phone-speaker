"""Selects: audio output (smart and voice modes) and the Assist pipeline (voice mode)."""

from __future__ import annotations

from homeassistant.components.assist_pipeline.select import (
    AssistPipelineSelect,
    VadSensitivitySelect,
)
from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, CONF_MODE, DOMAIN, MODE_GENERIC, MODE_VOICE
from .device import PhoneDevice
from .entity import PhoneEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    mode = entry.data[CONF_MODE]
    if mode == MODE_GENERIC:
        return
    device = hass.data[DOMAIN]["devices"][entry.data[CONF_DEVICE_ID]]
    entities: list[SelectEntity] = [AudioOutputSelect(device)]
    if mode == MODE_VOICE:
        entities += [PhonePipelineSelect(hass, device), PhoneVadSensitivitySelect(hass, device)]
    async_add_entities(entities)


class AudioOutputSelect(PhoneEntity, SelectEntity):
    """Choose where the phone sends audio (speaker, Bluetooth, wired...)."""

    _attr_name = "Audio output"
    _attr_icon = "mdi:speaker-bluetooth"

    def __init__(self, device: PhoneDevice) -> None:
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


class PhonePipelineSelect(AssistPipelineSelect):
    """Which Assist pipeline this phone uses ("preferred" follows the system default).

    The unique id format is what the Assist satellite entity and Home Assistant's
    voice assistant setup wizard look up.
    """

    _attr_has_entity_name = True

    def __init__(self, hass: HomeAssistant, device: PhoneDevice) -> None:
        super().__init__(hass, DOMAIN, device.device_id)
        self._attr_device_info = device.device_info


class PhoneVadSensitivitySelect(VadSensitivitySelect):
    """How quickly the end of a spoken command is detected."""

    _attr_has_entity_name = True

    def __init__(self, hass: HomeAssistant, device: PhoneDevice) -> None:
        super().__init__(hass, device.device_id)
        self._attr_device_info = device.device_info
