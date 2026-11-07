# Phone Speaker

Usa un teléfono Android como parlante, y como parlante inteligente (tipo Alexa), de Home Assistant.
Son dos piezas:

- `app/`: la app Android. **El teléfono se conecta a HA** (WebSocket), no al revés, así que
  funciona aunque HA no pueda abrir conexiones hacia el teléfono (doble NAT, VLANs, etc.).
- `custom_components/phone_speaker/`: la integración de HA.

## Modos (se elige en la app)

| Modo | Entidades en HA |
|---|---|
| **Simple** | `media_player` (reproducir URL/TTS/medios, pausa, stop, volumen, mute) |
| **Inteligente** | lo anterior + `select` "Audio output" (altavoz, Bluetooth, cable, USB) + sensor de batería |
| **Asistente de voz** | lo anterior + `assist_satellite` (micrófono, Assist, respuesta hablada) |

El audio sale por el ruteo normal de Android, así que un parlante Bluetooth emparejado al
teléfono funciona eligiéndolo en el selector.

## Asistente de voz

El teléfono transmite el micrófono (16 kHz, mono, con cancelación de eco) a un pipeline de
Assist de HA y reproduce la respuesta.

**Configuración guiada:** si el modo es *Asistente de voz* al emparejar, al terminar de agregar el
teléfono en HA se abre solo el asistente de configuración de voz de Home Assistant: prueba el
parlante y la conexión, te deja elegir y probar la palabra de activación, el área, y crea o
elige el pipeline (por ejemplo con Home Assistant Cloud). Más tarde puedes repetirlo desde la
página del dispositivo.

Hay dos formas de empezar una conversación:

- **Botón "Hablar"** en la app: la conversación empieza al tocarlo y HA detecta cuándo dejas de
  hablar. Solo necesita voz a texto y texto a voz en el pipeline.
- **Escucha continua** (interruptor en la app, activado por defecto en este modo): el teléfono
  transmite el micrófono sin parar y **la integración busca la palabra de activación con el motor
  instalado en HA** (por ejemplo el complemento **openWakeWord**, Ajustes > Complementos). La
  palabra se elige en el asistente de configuración o con la configuración del satélite, y no hace
  falta ponerla en el pipeline. Si no hay motor, usa el de la configuración del pipeline. Si falla
  3 veces seguidas, la app desactiva la escucha continua y te avisa.

Cada teléfono tiene su propio selector de **pipeline** y de **detección de fin de frase**
(entidades `select`), así que puede usar otro idioma o asistente que el resto de la casa.

Mientras suena una respuesta, el teléfono no envía el micrófono, para no oírse a sí mismo. La
entidad también soporta el servicio `assist_satellite.announce`.

Privacidad: con la escucha continua activa, todo el audio del micrófono viaja a tu HA (en tu red,
o por Nabu Casa si usas esa dirección). Se procesa ahí y no se guarda salvo que lo configures así
en el pipeline.

## Instalar la integración en HA

**Con HACS (recomendado):** HACS > menú de tres puntos > *Repositorios personalizados* >
URL `https://github.com/pipouyt1/phone-speaker`, categoría *Integración* > Añadir, y luego
descarga *Phone Speaker*.

**A mano:** copia la carpeta `custom_components/phone_speaker` a `/config/custom_components/`
de tu HA (Samba, SSH, o el add-on File editor).

Después, en cualquiera de los dos casos:

1. Reinicia HA.
2. Ajustes > Dispositivos y servicios > Añadir integración > **Phone Speaker**.
   Te muestra un código de 6 dígitos (vale 10 minutos).

## Instalar y emparejar la app

1. Descarga `phone-speaker-debug.apk` desde *Releases* e instálalo en el teléfono
   (o compílalo y usa `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Abre la app, elige el modo, escribe la dirección de HA (por ejemplo `192.168.1.50`, o `192.168.1.50:8123`,
   o una URL `https://...`) y el código. Toca **Emparejar**.
3. En HA pulsa **Enviar**. El teléfono aparece como dispositivo con sus entidades.
4. Elige el modo **antes** de emparejar (por defecto, Asistente de voz) y da el permiso del
   micrófono cuando lo pida.

La app guarda un token propio del dispositivo (no tu token de HA). Para revocarlo, borra la
integración en HA o toca **Desemparejar** en la app.

Para que Android no cierre el servicio, desactiva la optimización de batería para la app y,
si el teléfono queda fijo, déjalo enchufado.

## La app

Interfaz con Material 3 Expressive (Jetpack Compose): colores dinámicos, movimiento con resortes,
un orbe que cambia de forma según lo que hace el asistente (espera, escucha, piensa, responde) y
se agranda con el volumen del micrófono, y tres pestañas: **Inicio** (estado, último comando,
botón Hablar), **Sonido** (salida de audio, volumen del pitido, modo) y **Conexión**. El primer uso
es una pantalla guiada de tres pasos. Al abrir la app el parlante arranca solo, salvo que lo hayas
detenido tú.

## Compilar

Android Studio, o por línea de comandos con JDK 17 y Gradle 8.13:

```
gradle :app:assembleDebug
```

`compileSdk` 36, `targetSdk` 34, `minSdk` 28. Android Gradle Plugin 8.9.2, Kotlin 2.1.21 y
Material 3 `1.5.0-alpha10`: los componentes expresivos solo son públicos en las versiones alfa de
esa serie (la 1.4.0 estable los oculta), así que pueden cambiar de API.

## Protocolo (app ↔ integración)

- `POST /api/phone_speaker/pair` `{code, device_id, name, mode}` → `{token}`
- WebSocket `/api/phone_speaker/ws`. Primer mensaje del teléfono:
  `{"type":"hello","token":...,"mode":...}` → HA responde `{"type":"hello_ok"}`.
- Teléfono → HA: `{"type":"state","player":{state,volume,muted},"battery":N,"outputs":[...],"output":"..."}`
- HA → teléfono: `play {url, announce}`, `pause`, `resume`, `stop`, `volume {level}`,
  `mute {muted}`, `output {name}`.
- Voz, teléfono → HA: `voice_start {stage: "stt"|"wake_word"}`, luego **frames binarios** de audio
  PCM de 16 bits, y `voice_stop`.
- Voz, HA → teléfono: `voice_event {event, text?, message?}` y `voice_end` (HA terminó de usar el audio).

## Estado y límites

- Probado: la app contra un servidor de prueba con el código real de la integración (emparejar,
  reproducir, voz de extremo a extremo, escucha continua), y la integración importada e
  instanciada contra el código de Home Assistant 2026.11.0.
- Sin probar dentro de un HA en ejecución: el flujo de configuración y las entidades de voz con un
  pipeline de Assist real. Si algo falla, abre un issue con el log de HA.
- Los anuncios (`announce`) no bajan el volumen de lo que ya suena.
- Usa `MediaPlayer` simple; para más formatos (HLS, etc.) habría que pasar a Media3/ExoPlayer.
- La palabra de activación no se detecta en el teléfono, sino en HA (consume algo de red y batería).
- En Android 14+ el servicio con micrófono solo puede iniciarse con la app abierta; si Android lo
  reinicia en segundo plano, cae a modo sin micrófono hasta que abras la app.
- `legacy-esphome/` conserva el primer intento (API nativa de ESPHome), fuera de la compilación.
