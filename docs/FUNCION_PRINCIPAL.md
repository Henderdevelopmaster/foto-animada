# Función principal: del toque a la foto guardada

Camaratrix existe para esto: **tocar el botón y que quede guardada la mejor foto posible de ese
instante** (con su video corto si LIVE está prendido), igual a lo que se veía en pantalla.
Aquí está el camino completo en el código, en orden. Los enlaces van a la línea de cada función
(líneas de esta versión; si cambian, busca el nombre).

## 0. Antes de tocar: la cámara y la vista previa

1. [`startCamera`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2504) abre CameraX y llama a [`bindCamera`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2552), que
   une la vista previa, la foto ([`buildPhotoGroup`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2959): ZSL si se puede, calidad 98)
   y el análisis (`MotionRecorder`, que graba en memoria para el Live).
2. Cada cuadro de la cámara pasa por [`onExposure`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2657) (exposición, ISO) y cada
   0.5 s [`balanceExposure`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L1964) mide la luz de la pantalla: decide si hay poca luz,
   sube el brillo de noche ([`updateDigitalGain`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2111)) y el color de noche.
3. [`applyTone`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L1439) pinta encima de la vista previa el shader TONE
   ([`tonePreview`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/InstantFx.kt#L161)): brillo de noche, nitidez, Viva y color, lo mismo que
   llevará la foto.

## 1. El toque

1. [`shutter.setOnTouchListener`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2241): al **apoyar** el dedo, si se
   puede ([`canPreShoot`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3499)), la foto ya se pide
   ([`startPreShot`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3504)); al **soltar** se confirma ([`commitPreShot`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3512)).
   Si no se puede (temporizador, flash, noche), al soltar va a [`onShutterTap`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2277)
   → [`shoot`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L2288), que elige según el modo (fotomatón, boomerang, luna,
   atardecer o [`takeStill`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3284)).
2. [`doTakeStill`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3299) es el corazón del disparo:
   - decide modo, cámara, noche (`dark = poca luz y lunita prendida`), Viva y cuántas fotos de
     noche tomar;
   - llama a `takePicture` (en memoria). En `onCaptureStarted` suena el clic y parpadea
     (`shotTaken`), en el instante real de la foto;
   - con LIVE pide el clip a `MotionRecorder` y marca la hora exacta del sensor;
   - de noche toma 3 fotos más ([`grabBurst`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3605));
   - cuando todo llegó, llama a [`saveFinished`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3421).

## 2. Guardar según el modo

[`saveFinished`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3421) manda cada modo a su camino:

| Modo | Camino |
|---|---|
| FOTO | [`saveResult`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3673) |
| RETRATO, NOCHE, ATARDECER, VISIÓN NOCTURNA | [`saveProcessed`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3747) con su proceso |
| INSTANTÁNEA | [`saveInstant`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3728) |
| EFECTOS | `saveEffectPhoto` |

[`saveResult`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3673) (FOTO), el caso más usado:
- **De día:** prepara en segundo plano la mejora y el filtro cuando están activos.
- **De noche:** junta las fotos ([`mergeNightBitmap`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3646) → `NightStack`) y las mejora
  con `Enhance.bitmap`.
- Publica una sola foto, ya escrita por completo. Si falla la mejora, usa la captura original.
  Si falla el video del Live, guarda el JPEG cuando es posible.
- Al final, [`finishSave`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L4371) actualiza la miniatura y
  [`processingEnd`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L3263) apaga el aro y anota "Foto lista en N ms".

## 3. La mejora de la foto

1. [`bitmap`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/Enhance.kt#L62) (Android): saca los píxeles, busca caras (ML Kit) y llama a
   [`process`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/EnhanceCore.kt#L60) (Kotlin puro), que en orden hace:
   - de noche, el brillo igual al de la vista previa (`Brightness`), solo si la foto salió oscura;
   - quitar brillos fuertes (sin tocar la piel), tono local con filtro guiado (sombras y luces);
   - balance de blancos, y de noche el arreglo del morado ([`nightWb`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/Brightness.kt#L128));
   - grano y nitidez según el ruido medido; color (vibrancia cuidando la piel);
   - Viva si está prendida (claridad, color, nitidez y curva S); salida sin quemar blancos.
2. [`add`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/NightStack.kt#L60) (noche): alinea cada foto con la primera, descarta la foto entera
   si se movió el cel, la cabeza o la escena, descarta por bloques lo que se movió poco y promedia;
   [`result`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/NightStack.kt#L288) entrega la imagen.

## 4. El archivo

- [`muxToMp4`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MotionPhotoWriter.kt#L35) arma el MP4 del Live; el JPEG lleva XMP de Motion
  Photo y el MP4 pegado al final (Google Fotos lo reproduce).
- [`save`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MotionPhotoWriter.kt#L125)
  escribe en una entrada pendiente de MediaStore (DCIM) y la publica al cerrar el archivo.

## 5. Después

- Galería y visor: `GalleryActivity`, `ViewerActivity` (Viva en copia: [`runVivid`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/ViewerActivity.kt#L934)).
- Ajustes: [`showResolutionMenu`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L1034) (se abre con el botón ⚙ de los MP).
- Revisar qué pasó: [`settingsSummary`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/MainActivity.kt#L1488) + [`share`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/Registro.kt#L50)
  (Ajustes > Compartir registro) y [`PerfMonitor`](https://github.com/Henderdevelopmaster/foto-animada/blob/main/app/src/main/java/com/hender/fotoanimada/PerfMonitor.kt#L19) (Ajustes > Rendimiento).
