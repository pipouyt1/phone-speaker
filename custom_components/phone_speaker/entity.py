"""Shared base entity."""

from __future__ import annotations

from homeassistant.helpers.entity import Entity

from .device import PhoneDevice


class PhoneEntity(Entity):
    """Entity backed by a PhoneDevice; unavailable while the phone is offline."""

    _attr_should_poll = False
    _attr_has_entity_name = True

    def __init__(self, device: PhoneDevice, key: str) -> None:
        self.device = device
        self._attr_unique_id = f"{device.device_id}_{key}"
        self._attr_device_info = device.device_info

    @property
    def available(self) -> bool:
        return self.device.connected

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(self.device.add_listener(self.async_write_ha_state))
