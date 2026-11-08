"""Media player for the phone speaker."""

from __future__ import annotations

import mimetypes
from typing import Any

import voluptuous as vol

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
from homeassistant.core import HomeAssistant, SupportsResponse
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_platform
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import ASK_TIMEOUT, CONF_DEVICE_ID, DOMAIN
from .entity import PhoneEntity

_STATES = {
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
    "idle": MediaPlayerState.IDLE,
}


def _display_kind(content_type: str) -> str | None:
    """What to show on the phone's screen for this content type, or None for plain audio."""
    kind = content_type.lower()
    if kind.startswith("image"):
        return "image"
    if kind.startswith("video") or kind in ("application/vnd.apple.mpegurl", "application/x-mpegurl"):
        return "video"
    if kind in ("url", "web", "text/html"):
        return "web"
    return None


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    device = hass.data[DOMAIN]["devices"][entry.data[CONF_DEVICE_ID]]
    async_add_entities([PhoneSpeakerPlayer(device)])
    entity_platform.async_get_current_platform().async_register_entity_service(
        "ask",
        {
            vol.Required("question"): cv.string,
            vol.Required("options"): vol.All(
                cv.ensure_list, [cv.string], vol.Length(min=2, max=4)
            ),
            vol.Optional("timeout", default=ASK_TIMEOUT): vol.All(
                vol.Coerce(int), vol.Range(min=0, max=3600)
            ),
            vol.Optional("wait", default=True): cv.boolean,
            vol.Optional("camera"): cv.entity_domain("camera"),
            vol.Optional("media"): cv.string,
        },
        "async_ask",
        supports_response=SupportsResponse.OPTIONAL,
    )


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
        mime: str | None = None
        if media_source.is_media_source_id(media_id):
            sourced = await media_source.async_resolve_media(
                self.hass, media_id, self.entity_id
            )
            media_id = sourced.url
            mime = sourced.mime_type

        kind = _display_kind(mime or str(media_type))
        if kind is not None:
            if not self.device.display:
                raise HomeAssistantError(
                    "Turn on the screen option in the Phone Speaker app to show images, videos and web pages"
                )
            if kind != "web":  # a web page is opened as it is; Home Assistant's own files get signed
                media_id = async_process_play_media_url(self.hass, media_id, allow_relative_url=True)
            extra = kwargs.get("extra") or {}
            await self.device.async_show(
                kind, url=media_id, title=extra.get("title"), seconds=extra.get("duration")
            )
            return

        media_id = async_process_play_media_url(self.hass, media_id, allow_relative_url=True)
        await self.device.async_send(
            {
                "type": "play",
                "url": media_id,
                "announce": bool(kwargs.get(ATTR_MEDIA_ANNOUNCE)),
            }
        )

    async def async_ask(
        self,
        question: str,
        options: list[str],
        timeout: int,
        wait: bool,
        camera: str | None = None,
        media: str | None = None,
    ) -> dict[str, Any]:
        """phone_speaker.ask: show a question with buttons, return which one was pressed."""
        attachment: dict[str, Any] | None = None
        if camera:
            state = self.hass.states.get(camera)
            picture = state.attributes.get("entity_picture") if state else None
            if not picture:
                raise HomeAssistantError(f"{camera} has no picture available")
            # the phone asks for a new snapshot every couple of seconds
            attachment = {"kind": "image", "url": picture, "refresh": 2}
        elif media:
            mime: str | None = None
            if media_source.is_media_source_id(media):
                sourced = await media_source.async_resolve_media(self.hass, media, self.entity_id)
                media, mime = sourced.url, sourced.mime_type
            mime = mime or mimetypes.guess_type(media.split("?")[0])[0] or "image/jpeg"
            kind = "video" if _display_kind(mime) == "video" else "image"
            attachment = {
                "kind": kind,
                "url": async_process_play_media_url(self.hass, media, allow_relative_url=True),
                "refresh": 0,
            }
        return await self.device.async_ask(
            question, options, timeout=timeout, wait=wait, entity_id=self.entity_id, media=attachment
        )

    async def async_browse_media(
        self,
        media_content_type: MediaType | str | None = None,
        media_content_id: str | None = None,
    ) -> BrowseMedia:
        return await media_source.async_browse_media(
            self.hass,
            media_content_id,
            content_filter=lambda item: item.media_content_type.startswith(
                ("audio/", "image/", "video/") if self.device.display else ("audio/",)
            ),
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
