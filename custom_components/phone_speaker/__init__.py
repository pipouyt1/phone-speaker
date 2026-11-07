"""Phone Speaker: use a phone as a Home Assistant speaker.

The phone app connects *to* Home Assistant, so this works even when Home Assistant
cannot open connections to the phone (NAT, VLANs, client isolation).
"""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers.typing import ConfigType

from .const import CONF_DEVICE_ID, CONF_DISPLAY, CONF_MODE, DOMAIN, MODE_GENERIC, MODE_VOICE
from .device import PhoneDevice
from .http import async_ensure_http


def _platforms(entry: ConfigEntry) -> list[Platform]:
    """Entity platforms for the phone's mode (a mode change reloads the entry)."""
    mode = entry.data[CONF_MODE]
    platforms = [Platform.MEDIA_PLAYER]
    if mode != MODE_GENERIC:
        platforms += [Platform.SELECT, Platform.SENSOR]
    if mode == MODE_VOICE:
        platforms.append(Platform.ASSIST_SATELLITE)
    if entry.data.get(CONF_DISPLAY):
        platforms += [Platform.NOTIFY, Platform.BUTTON]
    return platforms


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    await async_ensure_http(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    await async_ensure_http(hass)
    device = PhoneDevice(hass, entry)
    hass.data[DOMAIN]["devices"][device.device_id] = device
    # A phone that has just paired is already connected, waiting for this device to exist:
    # adopt that connection before the entities are created, so the Assist satellite is available
    # right away (Home Assistant only offers its voice assistant setup for an available satellite).
    slot = hass.data[DOMAIN].get("waiting", {}).pop(device.token, None)
    adopted = slot is not None and slot["ws"] is not None and not slot["ws"].closed
    if adopted:
        await device.async_attach(slot["ws"], slot["hello"] or {})
    await hass.config_entries.async_forward_entry_setups(entry, _platforms(entry))
    if adopted:
        # whatever the phone sent while it was waiting was dropped: tell it to send its state
        # and, in voice mode, start listening again now that the entities exist
        device.send_soon({"type": "attached"})
    entry.async_on_unload(entry.add_update_listener(_async_reload))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unloaded = await hass.config_entries.async_unload_platforms(entry, _platforms(entry))
    if unloaded:
        device = hass.data[DOMAIN]["devices"].pop(entry.data[CONF_DEVICE_ID], None)
        if device is not None:
            await device.async_close()
    return unloaded


async def _async_reload(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)
