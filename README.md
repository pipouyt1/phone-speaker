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

La opción **Pantalla** (abajo) se suma a cualquiera de los tres.

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

## Pantalla (el teléfono como display)

Opción aparte del modo: en la app, **Sonido > Pantalla > Mostrar contenido de Home Assistant**
(también se elige al emparejar). Con ella activa, el teléfono muestra a pantalla completa lo que
le mandes desde HA, y la integración agrega dos entidades: `notify` "Screen" y el botón
"Close screen".

- **Imágenes, videos y páginas web:** con `media_player.play_media` sobre el reproductor del teléfono.
  `media_content_type` puede ser `image`, `video` (también acepta HLS, por ejemplo una cámara con
  `media-source://camera/...`) o `url` para abrir una página web. Con `extra` puedes poner un
  `title` y una `duration` en segundos (por defecto: imágenes y mensajes 30 s; videos y páginas
  hasta que terminen o los cierres).
- **Mensajes:** `notify.send_message` sobre la entidad "Screen", con `message` y `title`.
- **Cerrar:** el botón "Close screen", o la X en la pantalla del teléfono.

```yaml
action: media_player.play_media
target:
  entity_id: media_player.mi_telefono
data:
  media_content_type: image
  media_content_id: media-source://media_source/local/foto.jpg
  extra:
    title: Cámara del frente
    duration: 20
```

Límites: para que el contenido se abra solo cuando la app está cerrada, Android exige el permiso
"Mostrar sobre otras apps" (la app lo pide; sin él llega una notificación que lo abre al tocarla).
Una página web de HA pide iniciar sesión una vez dentro del teléfono. La pantalla muestra el
contenido y se cierra, no es un panel interactivo permanente.

## Tableta, pantalla completa y modo launcher

- **Pensada para tableta:** en pantallas anchas el micrófono se muestra grande a un lado y, al
  otro, el estado, el último comando y un botón **Hablar ahora** grande; en vertical todo va en
  una columna con un micrófono grande.
- **Pantalla completa** (Sonido > Pantalla): oculta la barra de estado y los botones de
  navegación. Se desliza desde el borde para verlos un momento.
- **Modo launcher** (Sonido > Pantalla): la pantalla de inicio muestra un reloj y la fecha
  grandes y un botón **Aplicaciones** con todas las apps instaladas. **Usar como pantalla de
  inicio** abre el selector de Android para elegir esta app como launcher predeterminado (la app
  siempre se ofrece como opción de pantalla de inicio, pero solo lo es si la eliges).

## Modo de Habitación

Para una tableta fija en una pieza: un **tablero de Home Assistant a pantalla completa** y, al decir
la palabra de activación, el asistente **aparece flotando arriba** sobre el tablero.

- Se activa en **Sonido > Modo de Habitación**, con la dirección del tablero: solo la ruta (por
  ejemplo `/lovelace/0`) o una dirección completa. La primera vez hay que iniciar sesión en Home
  Assistant dentro del dispositivo (marca "Mantener la sesión iniciada"); la sesión queda guardada.
- Con el modo activo, la app abre directo el tablero, oculta las barras del sistema y mantiene la
  pantalla encendida. Un engranaje pequeño en la esquina lleva a los ajustes, donde **Volver al
  tablero** regresa. El botón atrás navega dentro del tablero.
- El asistente flotante (modo de voz): **Te escucho** con las barras del micrófono, lo que
  entendió, y la respuesta escrita mientras habla; el tablero se atenúa detrás y la tarjeta se va
  unos segundos después de responder.
- Si Home Assistant se reinicia o se corta la red, el tablero se recarga solo cada 15 s.
- Con este modo conviene dejar la tableta enchufada. Para ocultar la barra lateral y el encabezado
  de Home Assistant, usa un tablero sin ellos (por ejemplo con el complemento Kiosk Mode de HACS).

## Abrir apps con la voz y asistente sobre otras apps

- **"Abre (nombre de la app)":** en modo de voz, di por ejemplo "Abre calculadora" o "Abre
  Spotify" y el dispositivo abre esa app. Entiende "abre", "abrir", "inicia", "lanza" y "open", con
  o sin "la", "mi" o "app". Solo actúa si el nombre coincide con una app instalada; frases como
  "abre la persiana" las sigue resolviendo Home Assistant. Como Home Assistant no conoce esas apps y
  contestaría "no entendí", el dispositivo no reproduce esa respuesta y muestra "Abriendo ..." en
  la tarjeta.
- **Asistente flotante sobre otras apps** (Sonido > Pantalla): al oír la palabra de activación, la
  tarjeta del asistente aparece arriba de lo que tengas en pantalla (Te escucho con las barras del
  micrófono, lo que entendió y su respuesta) y se va unos segundos después de responder. Los
  toques la atraviesan. Si la pantalla estaba apagada, la enciende.
- Ambas necesitan el permiso de Android **Mostrar sobre otras apps** (la app tiene un botón que
  lo pide). Sin él, "Abre ..." solo funciona con la app a la vista y, en segundo plano, llega una
  notificación para abrirla.

## Asistente del teléfono (botón de inicio)

La app también se puede elegir como el **asistente digital del teléfono**. En **Sonido >
Asistente del teléfono** toca **Usar como asistente del teléfono** y elige **Phone Speaker** en
Ajustes (aparece como *Asistente digital* o *Aplicación de asistencia*).

Después, al mantener pulsado el botón de inicio (o usar el gesto del asistente), empieza una
conversación con Home Assistant y aparece **la misma píldora flotante** que con la palabra de
activación, estés en la app que estés. La píldora se muestra aunque tengas desactivado el
asistente flotante por palabra de activación; necesita el permiso **Mostrar sobre otras apps**.
Sin ese permiso se abre la app para que veas y oigas la conversación.

- Requiere el modo **Asistente de voz** y el micrófono concedido.
- El gesto concreto depende del teléfono: pulsación larga de inicio, botón lateral o el gesto de
  asistente de Android.
- Si Phone Speaker no aparece en la lista, el fabricante no permite asistentes de terceros; la
  palabra de activación y el botón **Hablar** siguen funcionando igual.

### Modo asistente

El interruptor **Modo asistente** (en la misma tarjeta) apaga la escucha continua: el micrófono
solo se enciende cuando invocas al asistente (botón de encendido o gesto) o pulsas **Hablar**, como
en cualquier teléfono. No hay palabra de activación en este modo.

## Preguntas con botones

El servicio `phone_speaker.ask` hace aparecer la píldora con una pregunta y de 2 a 4 botones, sobre
cualquier app (o dentro de la app si está abierta). Necesita el permiso **Mostrar sobre otras apps**.

```yaml
- action: phone_speaker.ask
  target:
    entity_id: media_player.mi_telefono
  data:
    question: "Alguien está en la puerta. ¿Abro?"
    camera: camera.puerta        # opcional: imagen en vivo sobre la pregunta
    # media: /local/foto.jpg     # opcional: imagen o video (URL o fuente multimedia)
    options: ["Abrir", "Ignorar"]
    timeout: 60                  # segundos, 0 = hasta que respondas
  response_variable: r           # r[entidad] = {answered, index, option, reason}
```

Con `wait: false` la automatización sigue de inmediato; en cualquier caso se lanza el evento
`phone_speaker_answer` (`entity_id`, `id`, `question`, `answered`, `index`, `option`, `reason`).

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

## Actualizar la app

En **Conexión > Actualizaciones** la app busca el último release de GitHub y, si hay una versión
nueva, la descarga y abre el instalador de Android (la primera vez pide el permiso de instalar apps
desconocidas).

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
- Pantalla, HA → teléfono: `display {kind: image|video|web|text, url?, title?, text?, seconds?}` y `display_clear`.
- Teléfono → HA: `{"type":"state","player":{state,volume,muted},"battery":N,"outputs":[...],"output":"..."}`
- HA → teléfono: `play {url, announce}`, `pause`, `resume`, `stop`, `volume {level}`,
  `mute {muted}`, `output {name}`.
- Voz, teléfono → HA: `voice_start {stage: "stt"|"wake_word"}`, luego **frames binarios** de audio
  PCM de 16 bits, y `voice_stop`.
- Preguntas: HA → teléfono `prompt {id, question, options, timeout, media?: {kind, url, refresh}}`;
  teléfono → HA `prompt_answer {id, index, reason}`.
- Voz, HA → teléfono: `voice_event {event, text?, message?}` y `voice_end` (HA terminó de usar el audio).

## Estado y límites

- Probado: la app contra un servidor de prueba con el código real de la integración (emparejar,
  reproducir, voz de extremo a extremo, escucha continua), y la integración importada e
  instanciada contra el código de Home Assistant 2026.11.0.
- Sin probar dentro de un HA en ejecución: el flujo de configuración y las entidades de voz con un
  pipeline de Assist real. Si algo falla, abre un issue con el log de HA.
- El asistente del teléfono depende de que Android permita elegir apps de terceros en Ajustes >
  Asistente digital; algunos fabricantes solo ofrecen el suyo. La píldora necesita "Mostrar sobre
  otras apps"; sin el permiso se abre la app.
- Los anuncios (`announce`) no bajan el volumen de lo que ya suena.
- Usa `MediaPlayer` simple; para más formatos (HLS, etc.) habría que pasar a Media3/ExoPlayer.
- La palabra de activación no se detecta en el teléfono, sino en HA (consume algo de red y batería).
- En Android 14+ el servicio con micrófono solo puede iniciarse con la app abierta; si Android lo
  reinicia en segundo plano, cae a modo sin micrófono hasta que abras la app.
- `legacy-esphome/` conserva el primer intento (API nativa de ESPHome), fuera de la compilación.
