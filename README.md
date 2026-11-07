# Phone Speaker

Usa un teléfono Android como parlante de Home Assistant. Son dos piezas:

- `app/`: la app Android. **El teléfono se conecta a HA** (WebSocket), no al revés, así que
  funciona aunque HA no pueda abrir conexiones hacia el teléfono (doble NAT, VLANs, etc.).
- `custom_components/phone_speaker/`: la integración de HA. Crea el
  `media_player` y, en modo inteligente, un selector de salida de audio y un sensor de batería.

Modos (se elige en la app):
- **Genérico**: solo el `media_player` (reproducir URL/TTS/medios, pausa, stop, volumen, mute).
- **Inteligente**: además `select` "Audio output" (altavoz, Bluetooth emparejado, cable, USB)
  y sensor de batería.

El audio sale por el ruteo normal de Android, así que un parlante Bluetooth emparejado al
teléfono funciona eligiéndolo en el selector.

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
2. Abre la app, escribe la dirección de HA (por ejemplo `192.168.1.50`, o `192.168.1.50:8123`,
   o una URL `https://...`) y el código. Toca **Emparejar**.
3. En HA pulsa **Enviar**. El teléfono aparece como dispositivo con sus entidades.

La app guarda un token propio del dispositivo (no tu token de HA). Para revocarlo, borra la
integración en HA o toca **Desemparejar** en la app.

## Compilar

Android Studio, o por línea de comandos con JDK 17 y Gradle 8.9:

```
gradle :app:assembleDebug
```

`compileSdk`/`targetSdk` 34, `minSdk` 28.

## Protocolo (app ↔ integración)

- `POST /api/phone_speaker/pair` `{code, device_id, name, mode}` → `{token}`
- WebSocket `/api/phone_speaker/ws`. Primer mensaje del teléfono:
  `{"type":"hello","token":...,"mode":...}` → HA responde `{"type":"hello_ok"}`.
- Teléfono → HA: `{"type":"state","player":{state,volume,muted},"battery":N,"outputs":[...],"output":"..."}`
- HA → teléfono: `play {url, announce}`, `pause`, `resume`, `stop`, `volume {level}`,
  `mute {muted}`, `output {name}`.

## Estado y límites

- La app compila y el servidor se probó con un cliente simulado. Falta probarlo contra tu HA real.
- Los anuncios (`announce`) suenan como reproducción normal, sin bajar el volumen de lo que suena.
- Usa `MediaPlayer` simple; para más formatos (HLS, etc.) habría que pasar a Media3/ExoPlayer.
- Sin micrófono/Assist ni proxy BLE todavía.
- Si Android cierra el servicio, desactiva la optimización de batería para la app.
- `legacy-esphome/` conserva el primer intento (API nativa de ESPHome), fuera de la compilación.
