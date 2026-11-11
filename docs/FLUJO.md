# Camaratrix: flujo completo (cómo debe comportarse todo)

Este documento es la referencia de la app. Cualquier cambio, lo haga Claude o Codex, tiene que
cumplir estas reglas. Si algo de la app no hace lo que dice aquí, es un error.

Última revisión: 10 de noviembre de 2026. Si cambias algo de la app, actualiza este documento.

---

## 1. Reglas que se cumplen en TODA la app

1. **La foto es del instante en que apoyas el dedo.** No del instante en que lo levantas, ni de
   medio segundo después. Lo mismo con el botón de volumen.
2. **El aviso es honesto.** El clic y el parpadeo salen cuando la foto de verdad quedó. Si hay
   que esperar (varias fotos, flash), sale "Quieto…" con contador. Si después falta procesar,
   gira un aro alrededor de la miniatura de la galería hasta que la foto final esté lista.
3. **Lo que ves es lo que se guarda.** Brillo, filtro, espejo de la frontal, formato y recorte de
   la vista previa son los mismos de la foto y del video del Live.
4. **Un solo panel abierto a la vez.** Tocar el mismo botón otra vez lo cierra. Tocar fuera lo
   cierra (en el visor, ese toque solo cierra, no enfoca). Al disparar o cambiar de modo, todo
   se cierra.
5. **Las hojas que suben desde abajo** (flash, ajustes, compartir, etc.) se cierran tocando
   fuera, incluido el botón que las abrió, o deslizando hacia abajo.
6. **Nada se queda trabado sin aviso.** Si algo falla, sale un mensaje corto y la cámara queda
   lista otra vez.
7. **La vista previa nunca se traba** por tareas de fondo (QR, brillo, miniaturas): todo eso usa
   imágenes pequeñas que reduce la GPU.

---

## 2. Pantalla de cámara

### Barra de arriba (de izquierda a derecha)

| Control | Toque | Mantener | Dónde aparece |
|---|---|---|---|
| Filtros | Abre/cierra la barra de filtros (miniaturas en vivo) | | Foto, Retrato, Noche, Atardecer, Luna, Instantánea |
| Flash | Abre/cierra la hoja de flash | | Todos |
| Formato (4:3, 16:9, 1:1, Completa) | Pasa al siguiente formato | | Modos de foto |
| LIVE | Prende/apaga el Live | | Solo FOTO |
| Tipo (instantánea) | Abre la hoja: foto, boomerang, etc. | | Solo INSTANTÁNEA |
| Resolución (ej. 12MP) | Abre Ajustes | | Todos |
| Temporizador | 0 → 3 s → 10 s → 0 | | Todos |
| Cuadrícula | Prende/apaga | | Todos |

### Visor

- **Toque:** enfoca y mide la luz en ese punto (cuadro amarillo con sol). Si había un panel
  abierto, el toque solo lo cierra.
- **Arrastrar arriba/abajo con el cuadro a la vista:** sube/baja el brillo (el mismo valor del ±).
- **Mantener presionado:** bloqueo de exposición y enfoque (AE/AF). El ± sigue funcionando.
- **Deslizar a los lados:** cambia de modo.
- **Pellizcar:** zoom.

### Barra de abajo

- **Zoom** (1x, 2x).
- **±**: abre/cierra la regla de brillo (de −2 a +2). El valor queda puesto y se ve en amarillo.
  Mantener el ± o doble toque en la regla vuelve a automático.
- **Modos**: TIME-LAPSE, VIDEO, FOTO, EFECTOS, RETRATO, NOCHE, VISIÓN NOCTURNA, ATARDECER, LUNA,
  INSTANTÁNEA.
- **Miniatura** (izquierda): abre la galería. Mientras se procesa una foto, gira un aro.
- **Obturador** (centro).
- **Girar cámara** (derecha).

### Insignias

- **Lunita "Noche"** (arriba a la izquierda): sale cuando está oscuro en FOTO, Retrato e
  Instantánea. Amarilla = modo noche automático prendido. Blanca y tachada = apagado.
  Tocarla lo prende o apaga.
- **LIVE** (arriba al centro): mientras se graba el movimiento del Live.
- **"Quieto…" / "Quieto · 2 de 4"**: mientras la cámara necesita que no muevas el cel.

---

## 3. Obturador: qué pasa en cada caso

| Caso | Qué hace | Cuándo se toma | Aviso |
|---|---|---|---|
| FOTO, toque | Una foto | Al **apoyar** el dedo | Clic y parpadeo al soltar (con LIVE, solo parpadeo) |
| FOTO, mantener | QuickTake (video) | Mientras se mantiene | Sonido de inicio/fin, contador |
| FOTO, deslizar a la izquierda | Ráfaga (hasta 50) | Una tras otra | Clic en cada foto real, contador |
| FOTO de noche (lunita amarilla) | Junta 4 fotos | Desde el toque, ~1 s | "Quieto · n de 4" y luego "Listo, ya puedes moverte" |
| Con flash (pantalla o LED) | Una foto | Cuando el flash ya iluminó | Pantalla blanca o LED; clic al tomarse |
| Con temporizador | Cuenta y toma | Al terminar la cuenta | Números grandes en el centro |
| Botón de volumen | Igual que el obturador | Al **presionar** | Igual |

Si el toque termina siendo video o ráfaga, la foto que se pidió al apoyar el dedo **se descarta**
(no aparece en la galería).

### Disparo sin retraso (ZSL)

La cámara guarda los últimos cuadros a resolución completa; la foto sale del cuadro del instante
en que se pidió. Si el cel no lo permite, se usa el modo rápido normal (puede haber un pelito de
retraso). Si el cel falla al usarlo, se apaga solo y no vuelve a intentarlo. Se puede apagar en
Ajustes > Disparo. No funciona con el flash LED encendido o automático (lo dice CameraX).

---

## 4. Modos

| Modo | Toque | Qué se guarda | Procesado |
|---|---|---|---|
| FOTO | Foto (Live si está prendido) | JPEG con video del Live adentro (Motion Photo) | Mejora automática; de noche junta 4 fotos |
| VIDEO | Empieza/para | MP4 1080p | Aro hasta que termina de guardar |
| TIME-LAPSE | Empieza/para | MP4 acelerado | "Guardando time-lapse…" + aro |
| EFECTOS | Foto con efecto; mantener = video | JPEG/MP4 con el efecto | Belleza solo si está encendida |
| RETRATO | Foto | JPEG con fondo desenfocado | "Procesando retrato…" + aro |
| NOCHE | Junta 5 fotos | JPEG | "Quieto · n de 5", luego aro |
| VISIÓN NOCTURNA | Foto | JPEG verde | Aro |
| ATARDECER | 3 fotos con distinto brillo | JPEG combinado | "Quieto · n de 3", luego aro |
| LUNA | 6 fotos | JPEG apilado | "Quieto · n de 6", luego aro |
| INSTANTÁNEA | Foto con forma/marco/texto; mantener = video | Instantánea | Revelado tipo Polaroid |
| INSTANTÁNEA (fotomatón) | Cuenta y 4 poses | Tira de 4 fotos | Parpadeo en cada pose, aro al armar |
| INSTANTÁNEA (boomerang) | Graba 1.3 s | Boomerang | Aro al armar |

---

## 5. Brillo, enfoque y noche

1. La cámara equilibra la luz sola dos veces por segundo (no quema el cielo, no deja todo negro).
2. El ± y el sol del cuadro suben/bajan encima de eso. El equilibrio no deshace lo que elegiste.
3. **Modo noche automático** (lunita amarilla): si la cámara ya expone todo lo que puede, la app
   sube el brillo con la misma curva en la vista previa, la foto y el video del Live, y junta
   4 fotos para quitar grano. Apagado: una sola foto, sin subir el brillo de más.
4. Bloqueo AE/AF: congela la exposición; solo el ± la mueve.

## 6. Flash

- **Atrás:** Apagado, Automático, Encendido, Linterna (luz fija).
- **Adelante:** la pantalla hace de flash (color y forma configurables). Linterna adelante =
  aro de luz en la pantalla mientras ves la vista previa.

## 7. Ajustes (botón de resolución)

Foto (MP), Selfies (Como te ves / Como te ven), Disparo (Sin retraso / Normal), Modo noche,
Belleza, Sonido de la cámara, Mejora automática, Video, Live/QuickTake/time-lapse, Video con
efectos. La hoja se desliza si no cabe y se cierra tocando fuera o con "Listo".

## 8. Guardado

1. La foto tal cual sale de la cámara se guarda **al instante** (ya aparece en la galería).
2. Por detrás: noche (juntar fotos), mejora, filtro; luego se reemplaza el mismo archivo.
3. El aro de la miniatura gira hasta que termina el paso 2.
4. Si el paso 2 falla, queda la foto del paso 1 (nunca se pierde la foto).

## 9. Galería y visor

- Galería en cuadrícula de 4; miniaturas pedidas más grandes que la casilla (nítidas).
- Visor: foto a 1.5 veces la pantalla; Live: mantener presionado para verlo moverse
  (a 900 px y 20 cuadros por segundo).
- Efecto Live: Live, Bucle, Rebote, Exposición larga, Desactivado.
- Compartir Live: historia 9:16, video, GIF o foto. Editar en CapCut u otra app.

---

## 10. Hallazgos de la revisión del 10/11/2026

| # | Problema | Causa | Estado |
|---|---|---|---|
| 1 | La foto sale "a contratiempo" | Se pedía al **soltar** el dedo y la cámara tomaba un cuadro nuevo después (100 a 400 ms más tarde) | Arreglado: se pide al apoyar el dedo y sale del cuadro de ese instante (ZSL) |
| 2 | En el Live, el video y la foto no coinciden | Los cuadros del video se marcaban con la hora en que llegaban a la app (≈0.1 s tarde), no con la hora del sensor | Arreglado: hora del sensor en cuadros y foto |
| 3 | El menú de flash no se cierra al tocar el botón otra vez | Las hojas no se cerraban al tocar fuera | Arreglado en todas las hojas y en Ajustes |
| 4 | Paneles encima de otros (filtros + belleza + brillo) | Cada botón abría lo suyo sin cerrar lo demás | Arreglado: uno a la vez; tocar el visor cierra |
| 5 | Miniaturas de filtros quietas | Se tomaba una sola foto al abrir la barra | Arreglado: en vivo, 4 veces por segundo |
| 6 | Miniaturas de la galería borrosas | Se pedían más pequeñas que la casilla y se estiraban | Arreglado |
| 7 | Live borroso y a saltos en el visor | Se decodificaba a 540 px y 15 cuadros por segundo | Mejorado: 900 px y 20 cuadros por segundo |
| 8 | Video: el botón tardaba en cambiar y el sonido de fin llegaba tarde | Esperaba la confirmación de la cámara | Arreglado: cambia al tocar |
| 9 | El aro de procesado se apagaba antes de tiempo | Se apagaba al guardar la versión rápida | Arreglado: gira hasta la versión final |
| 10 | Tirones en la vista previa en modo FOTO | El lector de QR pedía una imagen de pantalla completa cada 0.8 s | Arreglado: imagen pequeña |

### Pendiente (siguiente revisión)

- Con LIVE prendido, la cámara queda ocupada ~1.5 s después de cada foto (espera el final del
  video). Para fotos seguidas con LIVE hay que permitir varios videos a la vez.
- Reproducir el Live con un reproductor de video (resolución completa, 30 cuadros por segundo)
  en vez de imágenes sueltas.
- Disparo al apoyar el dedo también en INSTANTÁNEA y EFECTOS.

---

## 11. Cómo probar (lista rápida)

1. FOTO a algo que se mueva (carro, tren, mano): la foto debe mostrar el instante del toque.
2. FOTO con LIVE: mantener la foto en la galería; el movimiento debe terminar exactamente en la
   foto, sin salto.
3. Mantener el obturador: debe grabar video y **no** guardar una foto extra.
4. Deslizar a la izquierda: ráfaga, sin foto extra.
5. Abrir flash y tocar el botón de flash otra vez: debe cerrarse.
6. Abrir filtros, luego tocar ±: los filtros se cierran y aparece la regla.
7. Abrir filtros: las miniaturas se mueven con lo que ve la cámara.
8. Galería: miniaturas nítidas.
9. VIDEO: el botón cambia al tocar; al parar suena de una.
10. De noche en FOTO: "Quieto · n de 4", luego aro en la miniatura hasta la foto final.
