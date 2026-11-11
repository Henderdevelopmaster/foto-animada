# AGENTS.md: guía para trabajar en Camaratrix (Codex y otros agentes)

App Android de cámara de Hender (sin anuncios): fotos animadas tipo Live Photo, modo noche,
retrato, instantánea, efectos y galería propia. Se usa en un **Redmi Note 13 Pro 4G**
(modelo 23117RA68G, MediaTek Helio G99, GPU Mali-G57 MC2): procesador modesto, así que
todo lo pesado va en segundo plano y medido.

## Documentos

| Archivo | Para qué |
|---|---|
| [docs/FLUJO.md](docs/FLUJO.md) | El flujo completo: qué hace cada botón y modo, qué se espera ver, hallazgos y lista de pruebas. **Es el contrato:** si cambias algo, actualízalo. |
| [docs/FUNCION_PRINCIPAL.md](docs/FUNCION_PRINCIPAL.md) | El camino principal en el código: del toque al archivo guardado, con enlaces a cada función. |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Qué cambió en cada versión (se genera con `python3 scripts/changelog.py`). |
| [README.md](README.md) | Resumen corto del proyecto. |

## Compilar y publicar (no hay PC: todo pasa por GitHub Actions)

- Rama de trabajo: `dev`. Cada push a `dev` compila (sirve para ver si compila).
- Publicar: `git push origin dev:main`. Eso crea el Release `build-N` con `Camaratrix-v1.0.N.apk`
  (N = número de ejecución de Actions). Hender lo instala desde el cel.
- No hay SDK de Android local en el entorno de los agentes: lo que no se pueda compilar aquí se
  prueba con harnesses de JVM (ver "Probar sin cel").
- Kotlin, minSdk 29, targetSdk 34, CameraX 1.4.1 (core, camera2, lifecycle, view, video, extensions).

## Reglas del proyecto (las pidió Hender)

1. **Textos de la app en español** (Colombia, cercano). **Nunca el guion largo** (em dash, U+2014) en textos,
   comentarios nuevos ni mensajes.
2. **Diseño pulido** y con íconos cuidados (vectores propios en `res/drawable`). Nada que se vea
   "de plantilla".
3. **Convenciones de uso parejas en toda la app**, sin esperar a que las pida: un menú se cierra al
   tocar de nuevo su botón o al tocar fuera; un solo panel abierto a la vez; toda acción lenta
   avisa (píldora "Quieto", aro en la miniatura, aviso de "Procesando…").
4. **Lo nuevo es opcional**: funciones nuevas como botón o modo **apagado por defecto** (ej. Viva,
   panel de Rendimiento). Nada automático que cambie el resultado sin que el usuario lo elija.
5. **Lo que se ve es lo que se guarda**: si la foto lleva un ajuste (brillo de noche, Viva, color
   de noche), la vista previa lo muestra igual (shader TONE en `InstantFx.kt`).
6. **Nunca se pierde una foto**: si una mejora falla, se guarda la original.
7. **Revisa el flujo completo** antes de entregar: todos los modos, cámara frontal y trasera,
   de día y de noche, y la galería. Actualiza `docs/FLUJO.md`.

## Mapa del código (`app/src/main/java/com/hender/fotoanimada/`)

| Archivo | Qué hace |
|---|---|
| `MainActivity.kt` | La cámara: UI, modos, CameraX, disparo, guardado, ajustes, panel de rendimiento. |
| `EnhanceCore.kt` | Mejora de la foto en Kotlin puro (sin Android): tono local, brillos, color, grano, nitidez, Viva. Se prueba en JVM. |
| `Enhance.kt` | Puente Android de la mejora (decodifica, caras con ML Kit, llama a `EnhanceCore`). |
| `NightStack.kt` | Junta 4 fotos de noche (alinea, descarta lo que se movió, promedia). Kotlin puro. |
| `Brightness.kt` | Brillo y color de noche compartidos por vista previa, foto y video del Live. |
| `InstantFx.kt` | Shaders AGSL de la vista previa (TONE: brillo, nitidez, Viva, color de noche) y looks de instantánea. |
| `MotionRecorder.kt` | Analizador de CameraX: buffer circular H.264 para el Live (1.5 s antes y después). |
| `MotionPhotoWriter.kt` | Arma el Motion Photo (JPEG + XMP + MP4) y lo guarda en MediaStore. |
| `Processing.kt` | Decodificar/codificar, retrato, noche simple, filtros. |
| `Bracket.kt` | Alineación por luz (MTB) usada por noche y atardecer. |
| `CameraSettings.kt` | Claves de ajustes (SharedPreferences). |
| `PerfMonitor.kt` | Panel de rendimiento (fps, demora del disparo, tiempo hasta lista, memoria). |
| `Registro.kt` | Registro para revisar errores: guarda el último cierre y comparte el logcat de la app. |
| `GalleryActivity.kt`, `ViewerActivity.kt` | Galería y visor (Live, efectos, Viva en copia, texto en vivo). |
| `Effects*.kt`, `Beauty.kt` | Modo Efectos y belleza. |

## Registros (logs)

- Tags: `Camaratrix` (MainActivity), `Enhance`, `MotionRecorder`, `Registro`. Líneas clave:
  - `Camaratrix: Disparo: <MODO> <frontal|trasera> noche=… ganancia=… fotos=… live=… viva=… zsl=…`
  - `Camaratrix: Noche: N fotos, usado [...] [d=dx,dy k=… mov=… centro=… | se movió (…) | movida …]` (NightStack)
  - `Enhance: Mejora WxH en N ms (caras …, noche=…, viva=…, auto=…): gain=… exp=… wb=R/G/B …`
  - `Camaratrix: Foto lista en N ms`
  - `Camaratrix: Procesado del fabricante: …` (extensiones de cámara disponibles)
- En el cel: **Ajustes > Compartir registro** arma un .txt (app, cel, ajustes, último cierre
  inesperado y logcat de la app) en `Descargas/Camaratrix` y abre el menú de compartir. Pídele
  ese archivo a Hender cuando haya un error.
- **Ajustes > Rendimiento > Mostrar**: panel con números en vivo (pídele captura).

## Probar sin cel

`EnhanceCore.kt`, `NightStack.kt`, `Brightness.kt` y `Bracket.kt` no usan Android: se compilan con
`kotlinc` junto a un `main` de prueba que lee PNG/JPG con `javax.imageio` y escribe el resultado.
Así se probaron Viva, el color de noche y el descarte de fotos movidas (ráfagas sintéticas con la
cara quieta, cel movido, cabeza movida, otra escena y una mano). Los shaders AGSL se validan con
`skia-python` (`skia.RuntimeEffect.MakeForShader`).

## Pendientes conocidos

Ver la sección de pendientes y la lista de pruebas en `docs/FLUJO.md`. Lo más importante:
- Probar en el cel la versión nueva de noche (fantasmas, color, tiempo hasta lista).
- Con LIVE prendido el botón queda ocupado ~1.5 s después de cada foto.
- El video del Live de noche no lleva la corrección de color (la foto sí).
