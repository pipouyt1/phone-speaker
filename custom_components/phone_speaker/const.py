"""Constants for the Phone Speaker integration."""

DOMAIN = "phone_speaker"

CONF_DEVICE_ID = "device_id"
CONF_TOKEN = "token"
CONF_MODE = "mode"
CONF_DISPLAY = "display"  # the phone's screen can show content sent from Home Assistant

MODE_GENERIC = "generic"  # media_player only
MODE_SMART = "smart"  # + audio output select and battery sensor
MODE_VOICE = "voice"  # + Assist satellite (microphone, wake word, voice replies)
MODES = (MODE_GENERIC, MODE_SMART, MODE_VOICE)

EVENT_ANSWER = "phone_speaker_answer"  # fired when a question asked with phone_speaker.ask is answered
ASK_TIMEOUT = 60  # default seconds a question stays on the phone

PAIR_URL = "/api/phone_speaker/pair"
WS_URL = "/api/phone_speaker/ws"

PAIR_CODE_TTL = 600  # seconds a pairing code stays valid
WAITING_TTL = 1800  # seconds a paired phone may wait, connected, for its device to be created
PAIR_FAIL_LIMIT = 10  # wrong codes tolerated per minute before answering 429

VOICE_QUEUE_CHUNKS = 200  # ~20 s of 100 ms audio chunks buffered per voice run
PLAYBACK_TIMEOUT = 120  # seconds to wait for the phone to finish playing something
