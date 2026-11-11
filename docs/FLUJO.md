# Camaratrix: flujo completo (cómo debe comportarse todo)

Este documento es la referencia de la app. Cualquier cambio, lo haga Claude o Codex, tiene que
cumplir estas reglas. Si algo de la app no hace lo que dice aquí, es un error.

Última revisión: 11 de noviembre de 2026. Si cambias algo de la app, actualiza este documento.

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
8. **La foto sale derecha aunque el cel esté de lado.** La interfaz es siempre vertical, pero
   la foto, el video, el Live, la ráfaga y el time-lapse siguen el giro del cel (de lado, de
   cabeza). Los íconos redondos y el ± giran en su sitio para leerse bien. Cambia cuando el cel
   pasó 15° del medio camino (no salta en el borde); acostado boca arriba se queda como estaba.
   Excepción: la vista previa de EFECTOS sigue vertical (la dibuja el motor de efectos).

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
| Ajustes (engranaje + calidad, ej. ⚙ 12MP) | Abre Ajustes | | Todos |
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
- **±** (izquierda): abre/cierra la regla de brillo (de −2 a +2). El valor queda puesto y se ve
  en amarillo. Mantener el ± o doble toque en la regla vuelve a automático.
- **Viva** (derecha, dos destellos): prende/apaga Viva. **Apagado por defecto**; queda guardado
  entre usos. Prendido = destellos amarillos con aro amarillo y aviso "Viva: más nitidez y
  color"; apagado = blanco y aviso "Viva apagada". Sale en FOTO, RETRATO, NOCHE, ATARDECER e
  INSTANTÁNEA (foto y fotomatón). No sale en VIDEO, TIME-LAPSE, EFECTOS, VISIÓN NOCTURNA, LUNA
  ni boomerang, porque ahí no hace nada. Tocarlo cierra cualquier panel abierto.
- **Modos**: TIME-LAPSE, VIDEO, FOTO, EFECTOS, RETRATO, NOCHE, VISIÓN NOCTURNA, ATARDECER, LUNA,
  INSTANTÁNEA.
- **Miniatura** (izquierda): abre la galería. Mientras se procesa una foto, gira un aro.
- **Obturador** (centro).
  Con TalkBack se puede activar como botón. Su etiqueta indica la acción de foto, video o time-lapse.
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
| FOTO de noche (lunita amarilla) | Junta 4 fotos | Desde el toque, ~1 s | "Quieto · n de 4", "Listo, ya puedes moverte" y "Mejorando la foto de noche…" con el aro; la foto aparece ya lista |
| Con flash (pantalla o LED) | Una foto | Cuando el flash ya iluminó | Pantalla blanca o LED; clic al tomarse |
| Con temporizador | Cuenta y toma | Al terminar la cuenta | Números grandes en el centro |
| Botón de volumen | Igual que el obturador | Al **presionar** | Igual |

Si el toque termina siendo video o ráfaga, la foto que se pidió al apoyar el dedo **se descarta**
(no aparece en la galería).

Cambiar de modo, girar la cámara o tocar el botón del temporizador cancela una cuenta
regresiva que ya esté corriendo.

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
5. **Juntar fotos de noche sin fantasmas:** si entre una foto y otra se movió el cel (más de
   3.5 %), la cabeza (más del 15 % del centro) o casi toda la escena (30 %), esa foto no entra.
   Queda un poco más de grano, nunca manchas, parches ni doble exposición. Donde una foto
   corrida no alcanza a cubrir, entra de a poco (sin costura recta en el borde).
6. **Color de noche:** con muy poca luz el sensor de este cel tira a morado (y la pantalla a
   azul). De noche se sube el verde y se baja el azul de más, con el mismo brillo, en la vista
   previa y en la foto. Lo cálido (una lámpara) se respeta.
7. **Si la foto salió clara** (flash de pantalla o llegó más luz), no se le sube el brillo de
   noche: antes se levantaba igual y la cara quedaba lavada, casi blanca.

### Viva: qué hace y qué se espera

| Qué | Resultado esperado |
|---|---|
| Vista previa | Con Viva se ve de una vez más color, más contraste y más nítido (lo mismo que llevará la foto) |
| Foto | Más textura (hojas, nubes, pelo, ropa), cielo y verdes con más color, bordes limpios, negros más profundos |
| Caras | La piel casi no cambia de color ni se marcan los poros (las caras se protegen) |
| De noche | Viva va a menos de la mitad para no subir el grano |
| Live y QuickTake | El video lleva el mismo color y contraste que la vista previa |
| Mejora automática apagada | La foto sale tal cual del sensor y encima solo Viva |
| Ráfaga | Sin Viva (la ráfaga es para rapidez y no lleva mejoras) |
| Se apaga mientras se procesa | La foto que ya se tomó igual sale con Viva (se decide al disparar) |
| Fotos viejas | En el visor, menú (⋯) > **Viva**: alterna Original y Viva en una vista previa. Solo **Guardar copia** crea un JPEG quieto |
| Modo MÁX (200 MP) | Sin Viva ni mejora: la foto gigante se deja con todo su detalle |

## 6. Flash

- **Atrás:** Apagado, Automático, Encendido, Linterna (luz fija).
- **Adelante:** la pantalla hace de flash (color y forma configurables). Linterna adelante =
  aro de luz en la pantalla mientras ves la vista previa.

## 7. Ajustes (botón de resolución)

Foto (MP), Selfies (Como te ves / Como te ven), Disparo (Sin retraso / Normal), Modo noche,
Belleza, Sonido de la cámara, Mejora automática, **Viva** (igual que el botón del visor),
Video, Live/QuickTake/time-lapse, Video con efectos y **Rendimiento**. La hoja se desliza si no
cabe y se cierra tocando fuera o con "Listo".

- Solo lo que cambia la cámara la vuelve a abrir al cerrar Ajustes (tamaño de foto, disparo,
  video, Live, efectos). Lo demás (selfies, noche, belleza, sonido, mejora, Viva, rendimiento)
  se aplica sin cortar la vista previa.
- **Rendimiento** (apagado por defecto): panel arriba a la derecha con cuadros por segundo de
  la cámara, del análisis y de la pantalla, el peor cuadro del último segundo, cuánto tarda la
  foto desde el toque ("Disparo"), cuánto tarda en quedar lista ("Lista"), la memoria en uso,
  si van ZSL, Live y Viva, y si Xiaomi deja usar su procesado (HDR, Noche, Auto…) desde otras
  apps ("Procesado Xiaomi"). Sirve para optimizar con números del cel de verdad.
- **Compartir registro** (botón al final de Ajustes): arma un .txt con la versión, el cel, los
  ajustes, el último cierre inesperado (si hubo) y lo que la app anotó (disparos, fotos de noche,
  mejoras y tiempos). Queda en Descargas/Camaratrix y se abre el menú de compartir. No lleva
  fotos. Es lo que hay que mandar cuando algo falla.

## 8. Guardado

1. En FOTO, el aro gira mientras se prepara la versión que se va a guardar. Si no hay mejoras
   pendientes, se escribe directamente la captura. La galería muestra un solo archivo, completo.
2. Si fallan la mejora o la escritura de la versión mejorada, se intenta guardar la captura
   original. Si falla el armado o el guardado del Live, se intenta guardar el JPEG y se avisa
   «Se guardó sin movimiento» cuando queda guardado.
3. MediaStore recibe un archivo pendiente. Solo aparece en la galería cuando se ha escrito y
   cerrado completo. Si falla la escritura o la publicación, se intenta borrar esa entrada
   pendiente. Si tampoco se puede guardar el original, se avisa «No se pudo guardar la foto».
4. Terminar de guardar una foto actualiza el aro y la miniatura. El obturador y el aviso LIVE
   los controla la captura activa.
5. Si falla el procesado en un modo de foto, se intenta guardar la captura original. En
   ATARDECER se prefiere la exposición neutra si llegó. En LUNA y fotomatón se usa la primera
   captura válida como respaldo.

## 9. Galería y visor

- Galería en cuadrícula de 4; miniaturas pedidas más grandes que la casilla (nítidas).
- Visor: al abrir un Live, el movimiento corto y silencioso termina en la marca de la foto.
  Mantener presionado reproduce el clip con ExoPlayer alrededor de esa marca, con audio si se
  grabó. Al soltar, vuelve la foto. Los efectos Bucle, Rebote y Exposición larga usan fotogramas
  decodificados aparte.
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
| 11 | Tironcito cada 0.8 s en FOTO | El lector de QR le pedía a la GPU una copia de la pantalla y la vista esperaba | Arreglado: en FOTO lee del cuadro que ya llega para el Live (sin copia); en Retrato y Noche, copia pequeña cada 1.6 s |
| 12 | Cerrar Ajustes cortaba la vista previa aunque solo cambiaras el sonido | Cualquier cambio volvía a abrir la cámara | Arreglado: solo lo que cambia la cámara la reabre |
| 13 | El video del Live gastaba de más | 10 Mbps fijos para 1280 × 960 | Arreglado: según el tamaño (≈5 Mbps), misma nitidez |
| 14 | En INSTANTÁNEA con look de shader la vista previa no llevaba la mejora | Se mostraba solo el look | Arreglado: mejora (y Viva) debajo del look, igual que la foto |
| 15 | Noche: doble exposición (cara con pasto y una mano encima) y un rectángulo marcado | Si toda la foto cambió, el umbral de "qué se movió" subía con ella y dejaba pasar todo | Arreglado: topes al umbral; si se movió el cel, la cara o la escena, esa foto no entra |
| 16 | Noche: manchas cafés en la nariz y la cara a parches cuadrados | Se mezclaban solo unos bloques de la cara que se había movido | Arreglado: si se movió el centro, la foto entera no entra; el mapa de mezcla va suavizado |
| 17 | Noche: costura recta en el borde izquierdo y arriba | Donde la foto corrida no cubría quedaba solo la primera | Arreglado: entra de a poco (64 px) |
| 18 | Selfie de noche lavada, casi blanca | Se le subía el brillo de noche aunque ya hubiera salido clara | Arreglado: solo se sube si salió oscura |
| 19 | Fotos de noche moradas | El sensor a ISO alto tira a magenta y no se corregía | Arreglado: corrección de tinte de noche (foto y vista previa) |
| 20 | Al compartir rápido una foto de noche salía oscura y con grano | Se compartía la versión rápida antes de que terminara la mejora | Arreglado: de noche se guarda una sola vez, ya lista |
| 21 | No se encontraba Ajustes | El botón solo decía los MP | Arreglado: engranaje junto a los MP |

### Pendiente (siguiente revisión)

- Con LIVE prendido, la cámara queda ocupada ~1.5 s después de cada foto (espera el final del
  video). Para fotos seguidas con LIVE hay que permitir varios videos a la vez.
- Si LIVE sigue esperando cuadros 4 segundos después del disparo, o si se sale de la cámara
  con una foto confirmada y el clip pendiente, se guarda el JPEG cuando está disponible.
  Falta comprobar en el Redmi el regreso del fondo durante una captura.
- Proteger las ediciones de portada Live y las ediciones en el mismo archivo del visor si falla
  la escritura.
- Extensiones del fabricante: CameraX 1.6.2 vuelve a consultar HDR, Noche, Retrato, Retoque y Auto
  para frontal y trasera. «Procesado Xiaomi» muestra los modos detectados, «ninguna» cuando todas
  las consultas devuelven que no hay soporte y «sin confirmar» si falló alguna consulta y no
  hubo detecciones. El registro incluye la compilación del cel. Falta confirmar la detección en
  el Redmi y probar captura con las opciones que aparezcan. La consulta no activa extensiones.
  Para adoptar esa versión se compila con SDK 36 y se alinean las dependencias de Media3
  del visor con las de CameraX. El mínimo sigue en Android 10.
- Disparo al apoyar el dedo también en INSTANTÁNEA y EFECTOS.

---

## 11. Cómo probar (lista rápida)

Para probar `dev` en el cel, abre la ejecución de «Compilar APK» en GitHub Actions,
descarga `Camaratrix-dev-<número>` desde Artifacts y extrae el APK del ZIP.

1. FOTO a algo que se mueva (carro, tren, mano): la foto debe mostrar el instante del toque.
2. FOTO con LIVE: al abrir, el movimiento corto debe terminar en la foto. Al mantener, revisar
   que el clip incluya el movimiento antes y después de la marca de la foto y que al soltar vuelva
   a la imagen fija. Si se grabó voz, comprobar que se escuche durante el gesto largo.
3. Mantener el obturador: debe grabar video y **no** guardar una foto extra.
4. Deslizar a la izquierda: ráfaga, sin foto extra.
5. Abrir flash y tocar el botón de flash otra vez: debe cerrarse.
6. Abrir filtros, luego tocar ±: los filtros se cierran y aparece la regla.
7. Abrir filtros: las miniaturas se mueven con lo que ve la cámara.
8. Galería: miniaturas nítidas.
9. VIDEO: el botón cambia al tocar; al parar suena de una.
10. De noche en FOTO: "Quieto · n de 4", luego aro en la miniatura hasta la foto final.
11. Viva: tocar los destellos (abajo a la derecha). La vista previa cambia al instante y sale
    "Viva: más nitidez y color". Tomar la misma escena con y sin Viva y compararlas en la galería.
12. Cerrar y abrir la app: Viva sigue como lo dejaste. Pasar a VIDEO: el botón desaparece.
13. Galería > una foto > menú > Viva: alternar Original y Viva en el mismo encuadre. Cancelar
    y verificar que no se cree un archivo. Reabrir y tocar «Guardar copia». Debe aparecer un
    JPEG nuevo y el original debe seguir igual. Repetir con un Live y comprobar que aún se mueva.
14. Ajustes > Rendimiento > Mostrar: tomar 3 fotos y mandar captura del panel (con eso se
    optimiza lo que de verdad está lento en tu cel).
15. Noche (lunita amarilla), selfie: moverse a propósito durante "Quieto · n de 4". La foto
    no debe tener fantasmas, parches ni costuras (a lo sumo un poco más de grano).
16. Noche con la luz de la pantalla: la piel no debe salir morada.
17. Selfie de noche con flash de pantalla: no debe salir lavada.

18. FOTO de día con filtro o Viva: abrir la galería mientras gira el aro y volver a revisar
    cuando termine. Debe quedar una sola foto completa, lista para abrir y compartir.
19. Con una falla de escritura durante el guardado, comprobar que la galería no muestre un
    archivo incompleto. Si hay espacio para la captura original, debe quedar legible. Si tampoco
    se puede guardar esa captura, debe salir un aviso y funcionar el siguiente disparo.
20. Con un clip que no se pueda armar, comprobar que quede un JPEG legible y salga
    «Se guardó sin movimiento». Disparar otra vez mientras termina una mejora anterior y
    comprobar que el guardado anterior no libere el obturador ni oculte LIVE antes de tiempo.
21. Probar en Ajustes > Rendimiento > Mostrar y compartir el registro. Anotar Android y HyperOS,
    lente y modos que aparecen en «Procesado Xiaomi». Para validar un candidato hace falta
    probar Preview + ImageCapture y después comprobar ImageAnalysis, QR y LIVE por separado.
22. Disparar con LIVE y salir al inicio del cel antes de que termine el clip. Volver y comprobar
    que quede una foto legible, se cierre el aviso LIVE y funcione el siguiente disparo.
23. Iniciar una cuenta de 3 s y cambiar de modo antes del cero. Repetir girando la cámara y
    tocando el botón del temporizador. La cuenta debe desaparecer sin tomar foto.
24. Si falla el procesado de un modo de foto después de capturar, comprobar que quede un JPEG
    legible y aparezca el aviso de que se guardó la foto original.
25. En VIDEO, tocar dos veces seguidas para detener una grabación. Debe sonar y empezar a
    guardar una vez, y el aro debe apagarse cuando termine. Activar TalkBack y comprobar que
    el obturador anuncia y ejecuta la acción de FOTO, VIDEO y TIME-LAPSE.

### Orientación y registro (pruebas en el Redmi)

- Con el cel de lado (girado a la derecha y a la izquierda), tomar una foto, una ráfaga corta,
  un Live y un video: en la galería y en Google Fotos deben verse acostados (más anchos que
  altos) y derechos. Repetir con la frontal. Los íconos deben quedar legibles al girar.
- Inclinar el cel despacio cerca de 45°: los íconos no deben parpadear entre dos posiciones.
- Ajustes > Compartir registro: el archivo debe traer "Temperatura" en el encabezado y, si el cel
  se calentó, líneas "Temperatura del cel: …". Útil cuando la vista previa o el Live se ponen lentos.
