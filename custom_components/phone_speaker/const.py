"""Constants for the Phone Speaker integration."""

DOMAIN = "phone_speaker"

CONF_DEVICE_ID = "device_id"
CONF_TOKEN = "token"
CONF_MODE = "mode"

MODE_GENERIC = "generic"
MODE_SMART = "smart"
MODES = (MODE_GENERIC, MODE_SMART)

PAIR_URL = "/api/phone_speaker/pair"
WS_URL = "/api/phone_speaker/ws"

PAIR_CODE_TTL = 600  # seconds a pairing code stays valid
PAIR_FAIL_LIMIT = 10  # wrong codes tolerated per minute before answering 429
