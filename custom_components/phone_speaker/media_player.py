"""Media player for the phone speaker."""

from __future__ import annotations

from typing import Any

from homeassistant.components import media_source
from homeassistant.components.media_player import (
    ATTR_MEDIA_ANNOUNCE,
    BrowseMedia,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
    async_process_play_media_url,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import CONF_DEVICE_ID, DOMAIN
from .entity import PhoneEntity

_STATES = {
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
    "idle": MediaPlayerState.IDLE,
}


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    device = hass.data[DOMAIN]["devices"][entry.data[CONF_DEVICE_ID]]
    async_add_entities([PhoneSpeakerPlayer(device)])


class PhoneSpeakerPlayer(PhoneEntity, MediaPlayerEntity):
    """The phone as a media player."""

    _attr_name = None  # use the device name
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.STOP
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.VOLUME_MUTE
        | MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.BROWSE_MEDIA
        | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
    )

    def __init__(self, device) -> None:
        super().__init__(device, "speaker")

    @property
    def _player(self) -> dict[str, Any]:
        player = self.device.state.get("player")
        return player if isinstance(player, dict) else {}

    @property
    def state(self) -> MediaPlayerState:
        return _STATES.get(self._player.get("state"), MediaPlayerState.IDLE)

    @property
    def volume_level(self) -> float | None:
        level = self._player.get("volume")
        return float(level) if isinstance(level, (int, float)) else None

    @property
    def is_volume_muted(self) -> bool:
        return bool(self._player.get("muted"))

    async def async_play_media(
        self, media_type: MediaType | str, media_id: str, **kwargs: Any
    ) -> None:
        if media_source.is_media_source_id(media_id):
            sourced = await media_source.async_resolve_media(
                self.hass, media_id, self.entity_id
            )
            media_id = sourced.url
        media_id = async_process_play_media_url(self.hass, media_id, allow_relative_url=True)
        await self.device.async_send(
            {
                "type": "play",
                "url": media_id,
                "announce": bool(kwargs.get(ATTR_MEDIA_ANNOUNCE)),
            }
        )

    async def async_browse_media(
        self,
        media_content_type: MediaType | str | None = None,
        media_content_id: str | None = None,
    ) -> BrowseMedia:
        return await media_source.async_browse_media(
            self.hass,
            media_content_id,
            content_filter=lambda item: item.media_content_type.startswith("audio/"),
        )

    async def async_media_play(self) -> None:
        await self.device.async_send({"type": "resume"})

    async def async_media_pause(self) -> None:
        await self.device.async_send({"type": "pause"})

    async def async_media_stop(self) -> None:
        await self.device.async_send({"type": "stop"})

    async def async_set_volume_level(self, volume: float) -> None:
        await self.device.async_send({"type": "volume", "level": volume})

    async def async_mute_volume(self, mute: bool) -> None:
        await self.device.async_send({"type": "mute", "muted": mute})
