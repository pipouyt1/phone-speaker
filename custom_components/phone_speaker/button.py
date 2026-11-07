"""A button that closes whatever the phone's screen is showing (display option)."""

from __future__ import annotations

from homeassistant.components.button import ButtonEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, DOMAIN
from .device import PhoneDevice
from .entity import PhoneEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    device = hass.data[DOMAIN]["devices"][entry.data[CONF_DEVICE_ID]]
    async_add_entities([CloseScreenButton(device)])


class CloseScreenButton(PhoneEntity, ButtonEntity):
    """Close the content shown on the phone's screen."""

    _attr_name = "Close screen"
    _attr_icon = "mdi:close-box"

    def __init__(self, device: PhoneDevice) -> None:
        super().__init__(device, "close_screen")

    async def async_press(self) -> None:
        await self.device.async_clear_display()
