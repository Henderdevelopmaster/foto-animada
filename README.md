# Foto Animada

App Android para tomar **fotos animadas** (tipo Live Photo de iPhone), sin anuncios.

Cada foto guarda ~1.5 s de video antes y después del disparo, en el formato
**Motion Photo** de Google, así que Google Fotos la reproduce como foto animada.

## Cómo funciona

- `MotionRecorder`: la cámara graba video todo el tiempo en memoria (buffer circular H.264).
- Al tocar el botón se toma la foto en alta calidad y se siguen grabando ~1.5 s.
- `MotionPhotoWriter`: mete el clip en un MP4, le pone metadatos XMP al JPEG y pega el MP4 al final.
- Se guarda en `DCIM/FotoAnimada`.

## Instalar

Cada push a `main` compila el APK con GitHub Actions y lo publica en **Releases**.
Descárgalo desde el celular e instálalo (permite "instalar apps desconocidas").

## Stack

Kotlin, CameraX, MediaCodec, MediaMuxer. minSdk 29 (Android 10).
