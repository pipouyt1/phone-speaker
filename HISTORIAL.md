# Phone Speaker: historial completo del proyecto

Registro de todo lo que se hizo, en orden, con las decisiones, los problemas y cómo se resolvieron.

## 1. Origen de la idea

- El usuario tenía varios teléfonos sin usar y un servidor Home Assistant (HA OS 2026.11.0, `192.168.1.16`, con Nabu Casa).
- Pregunta inicial: ¿se puede dar a HA acceso a Bluetooth, parlantes y micrófono de un teléfono (rooteado)?
- Se acotó a lo más simple: una app que vuelve el teléfono un parlante visible para HA, donde el usuario elige si es genérico o inteligente.
- Requisito: que sea una app más una integración que se descarga, se configura y funciona (HACS). Se descartó la opción sin integración.

## 2. Primer intento: API de ESPHome (abandonado)

- La app emulaba un dispositivo ESPHome. HA no lo encontraba y al agregarlo manualmente se quedaba pegado.
- Se usó el MCP de HA (a través de Nabu Casa) para inspeccionar la instancia y ver los logs.
- Causa real: la red tiene doble NAT (router ASUS detrás de un mesh eero, ambos con el SSID "Rodrigo40"). HA no puede abrir conexiones hacia el teléfono.
- Se descartó el modo punto de acceso del ASUS: ya lo habían probado y degradaba el mesh.
- Ese código quedó en `legacy-esphome/`.

## 3. Arquitectura final

El teléfono se conecta a HA, no al revés, por WebSocket, así que funciona detrás de NAT.

- Integración personalizada `custom_components/phone_speaker`:
  - Config flow con código de emparejamiento.
  - Vistas HTTP `/api/phone_speaker/pair` y `/api/phone_speaker/ws`.
  - Token por dispositivo.
  - "Sala de espera": el teléfono se conecta justo después de emparejar, antes de que exista la entrada, y `async_setup_entry` lo adopta y le envía `attached`.
  - Recarga de la entrada si cambia el modo o la pantalla.
- Entidades:
  - `media_player` (siempre).
  - `select` de salida de audio y `sensor` de batería (modo inteligente).
  - `assist_satellite` (modo voz).
  - `notify` "Screen" y `button` "Close screen" (pantalla).
- Protocolo:
  - Teléfono a HA: `hello`, `state`, `voice_start{stage,run}`, `voice_stop`, audio binario, `play_error`, `play_skipped`.
  - HA al teléfono: `play{url,announce}`, `pause`, `resume`, `stop`, `volume`, `mute`, `output`, `voice_event{event,text}`, `voice_end{run}`, `display{kind,url,title,text,seconds}`, `display_clear`, `attached`.
- Publicado como repositorio público en GitHub (`pipouyt1/phone-speaker`) para instalarlo desde HACS. Cada publicación se confirmó antes con el usuario.

## 4. Línea de versiones

| Versión | Contenido |
|---|---|
| inicial | Parlante genérico/inteligente, emparejamiento, media_player, selector de salida, batería |
| 0.4.x | Parlante inteligente con Assist (satélite de voz), asistente de configuración de voz de HA, pitido |
| 0.5.x | Volumen del pitido, UI Material 3 Expressive |
| 0.6.0 | Pantalla/contenido, diseño para tablet, pantalla completa, modo launcher |
| 0.7.0 | Modo Habitación, "Abre (app)" por voz, asistente flotante sobre cualquier app |
| 0.7.1 | La píldora flotante se estira con animación para mensajes largos |
| 0.8.0 | Asistente digital del teléfono: la app se elige en Ajustes y el gesto del asistente muestra la píldora flotante |

## 5. Funciones entregadas

### Audio y modos
- Modos: genérico (solo media_player), inteligente (+ salida de audio y batería) y voz (+ Assist).
- Selección de salida de audio (altavoz, Bluetooth, auriculares).
- Servicio en primer plano (`mediaPlayback|microphone`), reconexión con retroceso exponencial.

### Voz (satélite Assist)
- La detección de la palabra de activación ocurre en HA (openWakeWord, entidad `wake_word`) dentro del satélite, porque el usuario la elige en HA.
- Pulsar para hablar desde la app.
- Selects de pipeline y sensibilidad VAD.
- El asistente de configuración de HA aparece al añadir el dispositivo.
- Pitido al detectar la voz, con volumen configurable.
- Respuesta en texto (evento `reply` desde `INTENT_END`).

### Pantalla
- Mostrar imagen, vídeo, web o texto enviados desde HA (`notify`, `media_player`).
- Diseño de tablet de dos columnas, botón Hablar grande.
- Pantalla completa inmersiva.
- Modo launcher: reloj y cuadrícula de aplicaciones, registrado como HOME.

### Modo Habitación
- Dashboard de HA a pantalla completa en un WebView.
- Autorecarga cada 15 s si falla.
- Botón de volver y engranaje pequeño.
- Una píldora asistente flota arriba cuando se detecta la palabra de activación.

### Comandos y flotante
- "Abre (nombre de la app)": `AppLauncher` con verbos `abre|abrir|abri|abreme|abrime|inicia|iniciar|lanza|lanzar|ejecuta|ejecutar|open|launch|start`. Se omite la respuesta TTS de HA y se envía `play_skipped`.
- Asistente flotante sobre cualquier app: ventana overlay (`TYPE_APPLICATION_OVERLAY`) con permiso `SYSTEM_ALERT_WINDOW`. Se activa con la palabra de activación incluso con otra app abierta, y despierta la pantalla.
- Píldora que se estira con animación spring (`animateContentSize` + `AnimatedContent`) para textos largos (hasta 14 líneas).

## 6. Problemas y soluciones

- HA no alcanzaba al teléfono (doble NAT): se invirtió la conexión, el teléfono marca a HA.
- La prueba de conexión del asistente fallaba (URL :8123 inalcanzable): la integración envía rutas relativas (`allow_relative_url=True`) y la app las resuelve contra su URL base.
- El asistente no aparecía (satélite no disponible al crearse): sala de espera.
- `SatelliteBusyError` por pruebas concurrentes: el anuncio de la prueba de conexión retorna de inmediato.
- "No audio data received" (un `voice_end` viejo cortaba la ejecución nueva): se agregaron ids de ejecución.
- "speech-to-text failed" y wake word sin responder: se movió la detección al satélite y se ajustó el flujo de audio.
- Error "pipeline does not support speech-to-text": configuración de pipeline en HA.
- Error de Google AI `Unauthorized` con `['assist','homeassistant']`: las APIs LLM con `requires_admin` exigen un usuario admin y los satélites no tienen. El usuario lo resolvió en HA dejando solo "Assist" en el agente. Sin cambio de código.
- Compose: Material3 1.4.0 oculta Expressive, se subió a 1.5.0-alpha10. Faltaba `Morph.toShape`, se hizo un `MorphShape` propio desde `asCubics`. Choque de nombre `display` con `Context.display`, se renombró a `screenItem`.
- La rotación cambiaba el modo (restauración del RadioGroup): se resolvió con la reescritura en Compose.
- El botón "Volver al tablero" tapaba el botón Hablar: pasó a ser un botón dentro del contenido de Inicio.
- Subtítulos en vivo: pedidos y luego cancelados por el usuario ("mejor no hagamos lo de los subtitulos"). El reconocedor local no estaba disponible en la tablet. Código revertido.
- Un push fue bloqueado por el clasificador de permisos y se publicó después con aprobación explícita.

## 7. Compilación y pruebas

- Herramientas: Gradle 8.13, AGP 8.9.2, Kotlin 2.1.21 (plugin compose), compileSdk 36, minSdk 28, targetSdk 34. APK debug de ~28 MB. Las herramientas se descargaron en el equipo.
- Instalación y pruebas por adb en el teléfono y luego en la tablet (`HA284V6R`).
- Servidor HA falso en Python que reutiliza `http.py` y `device.py` reales con módulos de HA simulados. También se probaron clases reales de HA en un venv.
- Pruebas de interfaz: capturas y `screenrecord` por adb, intercambio de preferencias con `run-as`, páginas de dashboard servidas con `python3 -m http.server`.
- Verificación de la animación 0.7.1: grabación de pantalla, extracción de fotogramas a 8 fps y hoja de contactos. La tarjeta crece a mitad de camino mientras el texto nuevo aparece con fundido, sin saltos.

## 8. Estado actual

- Última versión publicada: v0.8.0 en https://github.com/pipouyt1/phone-speaker/releases/tag/v0.8.0
- La tablet quedó con datos de la app borrados y permisos (notificaciones, Bluetooth, micrófono) reconcedidos. Hay que volver a emparejarla.
- Sin verificar con el HA real del usuario:
  - Emparejamiento de la tablet.
  - Modo Habitación con un dashboard real.
  - "Abre ..." por voz real.
  - Asistente flotante con el pipeline real.
  - Asistente del teléfono: conversación completa desde el gesto (sí se verificó que aparece en el
    selector de Android 16 y que `KEYCODE_ASSIST` abre la app).

## 9. Asistente del teléfono (asistente digital de Android)

- Se agregó `AssistActivity`, una activity transparente que responde a `android.intent.action.ASSIST`
  con categoría `DEFAULT`. Eso es lo que pide Android para ofrecer la app en Ajustes > Apps >
  Asistente digital; no hizo falta implementar `VoiceInteractionService`.
- Al invocarla (pulsación larga de inicio, gesto o botón del asistente), pide al `SpeakerService`
  que inicie una conversación. Si el servicio no está, lo arranca; si Android lo había reiniciado
  sin micrófono, reintenta el tipo `microphone` mientras la activity está en primer plano.
- La píldora es la misma `AssistantOverlay` de la palabra de activación. `assistantSession` la
  muestra también cuando el asistente flotante por wake word está desactivado.
- Nuevo ajuste **Sonido > Asistente del teléfono**: muestra si la app es el asistente
  predeterminado y abre Ajustes (`ACTION_VOICE_INPUT_SETTINGS`, con
  `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` como alternativa) para elegirla.
- Si no hay permiso de "Mostrar sobre otras apps", la conversación se muestra dentro de la app en
  vez de flotando. Sin emparejar o sin modo Voz, la activity abre la app.
- Verificado en la tablet Android 16: el selector "App de asistente digital predeterminada"
  muestra Phone Speaker; al elegirla y pulsar `KEYCODE_ASSIST` (el evento del gesto/botón del
  asistente) se abrió Phone Speaker sin emparejar, y después se restauró Google como
  predeterminado. La conversación completa desde el gesto queda sin probar en hardware por falta
  de emparejamiento.
- Compila (`:app:assembleDebug`) y el APK se instaló en la tablet por adb.

## 10. Modo asistente y preguntas con botones (sin publicar)

- **Modo asistente** (Sonido > Asistente del teléfono): el micrófono no escucha hasta invocar al
  asistente (botón de encendido, gesto) o pulsar Hablar. Sin palabra de activación. Verificado en el
  Motorola edge 70 fusion solo en compilación e instalación.
- **Preguntas con botones**: servicio `phone_speaker.ask` (sobre la entidad media_player) con
  `question`, `options` (2 a 4), `timeout` (60 s por defecto, 0 = sin límite) y `wait`. Muestra la
  píldora con un botón por opción (el primero resaltado), sobre cualquier app (ventana táctil) o dentro
  de la app si está abierta. Devuelve `{answered, index, option, reason}` y lanza el evento
  `phone_speaker_answer` (`entity_id`, `id`, `question`, `answered`, `index`, `option`, `reason`),
  que sirve de disparador de automatizaciones. Protocolo: HA envía `prompt{id,question,options,timeout}`
  y el teléfono responde `prompt_answer{id,index,reason}`.
- Probado con un servidor falso en el Motorola: la píldora con 3 botones aparece sobre la pantalla de
  inicio, al tocar "Ignorar" desaparece y llega `index: 1`. Sin permiso de overlay y con la app
  cerrada, el teléfono responde `reason: no_overlay_permission`. Falta probar con el HA real.

## 11. Imagen, cámara o video en las preguntas, y animaciones sin rebote

- `phone_speaker.ask` acepta `camera` (entidad de cámara: imagen en vivo que el teléfono recarga cada
  2 s) y `media` (URL o fuente multimedia de imagen o video; el tipo se detecta por el mime o la
  extensión y las URL de HA se firman). El teléfono recibe `media{kind,url,refresh}` dentro de
  `prompt`. La imagen se muestra sobre la pregunta con altura máxima de 280 dp; el video se repite
  sin sonido.
- Probado en el Motorola con servidor falso: imagen y video aparecen en la píldora. La cámara real
  de HA no se probó.
- Animaciones: algunas veces rebotaban demasiado. Se cambió el esquema de movimiento a
  `MotionScheme.standard()` y se pusieron resortes sin rebote en el "swell" del orbe (lo mueve el nivel
  del micrófono varias veces por segundo), en el tamaño de las tarjetas y un resorte suave en el
  morfeo de la forma, con el progreso limitado a 0..1.

## 12. Actualizar desde la app

- Conexión > Actualizaciones > "Buscar actualizaciones": consulta el último release de
  `pipouyt1/phone-speaker` en GitHub, compara versiones y, si hay una más nueva, muestra sus notas y
  "Descargar e instalar" (descarga el APK con progreso y abre el instalador de Android, vía
  `FileProvider`). La primera vez Android pide el permiso de instalar apps desconocidas.
- Verificado en el Motorola: con la 0.8.0 instalada y v0.8.0 como último release, muestra
  "Ya tienes la última versión (0.8.0)". El caso "hay una versión nueva" y la instalación quedan por
  probar cuando exista un release más nuevo.

## 13. Normas acordadas

- Antes de subir algo al repositorio público o crear un release, pedir confirmación al usuario.
- Idioma de trabajo: español.
