"""A notify entity that shows a message on the phone's screen (display option)."""

from __future__ import annotations

from homeassistant.components.notify import NotifyEntity
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
    async_add_entities([ScreenNotify(device)])


class ScreenNotify(PhoneEntity, NotifyEntity):
    """`notify.send_message` on this entity puts the message on the phone's screen."""

    _attr_name = "Screen"
    _attr_icon = "mdi:cellphone-message"

    def __init__(self, device: PhoneDevice) -> None:
        super().__init__(device, "screen")

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        await self.device.async_show("text", title=title, text=message)
