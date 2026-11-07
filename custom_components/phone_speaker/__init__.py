"""Phone Speaker: use a phone as a Home Assistant speaker.

The phone app connects *to* Home Assistant, so this works even when Home Assistant
cannot open connections to the phone (NAT, VLANs, client isolation).
"""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.typing import ConfigType

from .const import CONF_DEVICE_ID, DOMAIN
from .device import PhoneDevice
from .http import async_ensure_http

PLATFORMS = [Platform.MEDIA_PLAYER, Platform.SELECT, Platform.SENSOR]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    await async_ensure_http(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    await async_ensure_http(hass)
    device = PhoneDevice(hass, entry)
    hass.data[DOMAIN]["devices"][device.device_id] = device
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_async_reload))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unloaded = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unloaded:
        device = hass.data[DOMAIN]["devices"].pop(entry.data[CONF_DEVICE_ID], None)
        if device is not None:
            await device.async_close()
    return unloaded


async def _async_reload(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)
