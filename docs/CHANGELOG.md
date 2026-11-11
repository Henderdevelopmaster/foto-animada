# Registro de cambios (Camaratrix)

Cada versión es un Release de GitHub (`build-N` → `Camaratrix-v1.0.N.apk`). Lo más nuevo arriba.
Debajo de cada versión van los cambios que trae (mensajes de los commits).
Se genera con `python3 scripts/changelog.py`.

## Versión siguiente a v1.0.73

- Paquete para Codex: AGENTS.md (reglas, mapa del código, registros, cómo probar), docs/FUNCION_PRINCIPAL.md (del toque a la foto guardada, con enlaces), docs/CHANGELOG.md y scripts/changelog.py
- Ajustes > Compartir registro: un .txt con la app, el cel, los ajustes, el último cierre inesperado y el registro de la app (disparos, noche, mejoras y tiempos); se guarda en Descargas/Camaratrix

## v1.0.73 (2026-11-11) · [build-73](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-73)

- Noche sin fantasmas ni doble exposición (una foto que se movió no entra; topes al umbral; borde sin costura), color de noche sin morado (foto y vista previa), selfie clara sin lavar, foto de noche guardada una sola vez ya lista y más rápida, engranaje en Ajustes

## v1.0.71 (2026-11-11) · [build-71](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-71)

- El video del Live sigue a la vista previa también cuando cambia la luz (Viva más suave de noche)
- Revisar si Xiaomi deja usar su procesado (extensiones de cámara) y mostrarlo en el panel de Rendimiento
- Viva (botón, apagado por defecto): más nitidez, contraste local y color en vista previa, foto y Live; copia con Viva desde la galería; panel de rendimiento opcional; QR sin copiar la pantalla; bitrate del Live según tamaño; Ajustes solo reabre la cámara cuando hace falta

## v1.0.67 (2026-11-10) · [build-67](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-67)

- docs/FLUJO.md: flujo completo de la app (reglas, controles, obturador, modos, brillo, flash, ajustes, guardado, galería), hallazgos de la revisión y lista de pruebas
- Miniaturas de filtros iguales a la pantalla (giro y espejo correctos); el aro de procesado sigue hasta la versión final de la foto; errores al mejorar no rompen el guardado
- Compila: una sola anotación OptIn con las dos APIs
- Sincronía real de la foto: se toma al apoyar el dedo (o el volumen) y sale del cuadro de ese instante (ZSL, con respaldo automático); el Live usa la hora del sensor para que video y foto coincidan; menús que se cierran al tocar de nuevo o fuera y un solo panel a la vez; miniaturas de filtros en vivo; miniaturas y Live más nítidos; video con aviso inmediato; QR sin trabar la vista previa

## v1.0.62 (2026-11-07) · [build-62](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-62)

- Toma de fotos sincronizada: el clic, el parpadeo y la vibración ahora van en el instante real de la foto (no al tocar), sin la espera de reenfoque que la retrasaba; "Quieto" con contador si tarda o son varias fotos (noche, atardecer, luna) y aro girando en la miniatura mientras se procesa, en todos los modos

## v1.0.60 (2026-11-07) · [build-60](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-60)

- Modo noche con interruptor: tocar la lunita lo prende o lo apaga (apagado: blanca y tachada, una sola foto y sin subir el brillo de más); también en Ajustes

## v1.0.58 (2026-11-07) · [build-58](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-58)

- Fotos mejores: noche con varias fotos juntas (alineadas, sin fantasmas, la mitad de grano), blancos de verdad y luces que ya no se apagan de día; belleza con interruptor (apagada = piel natural); panel de ajustes deslizable

## v1.0.56 (2026-11-06) · [build-56](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-56)

- Selfies como te ves en la pantalla: la foto, el Live, el QuickTake, la ráfaga y el fotomatón de la frontal salen volteados como la vista previa (antes salían al revés y la cara se veía rara). Ajuste "Selfies: Como te ves / Como te ven"

## v1.0.54 (2026-11-05) · [build-54](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-54)

- Mejora: el anti-halo ya no deja manchas grises en la cara. Las caras detectadas quedan protegidas y solo se quita el velo sobre zonas oscuras (piel o paredes iluminadas no se tocan)

## v1.0.52 (2026-11-05) · [build-52](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-52)

- Noche: la frontal ya no queda negra. Detecta la oscuridad por el tiempo de exposición (no solo ISO), deja exponer más y sube el brillo por software con la misma curva en vista previa, foto y Live; el ± funciona aunque la cámara esté al tope; insignia "Noche"; botón ± en píldora

## v1.0.50 (2026-11-05) · [build-50](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-50)

- Brillo: equilibrio automático siempre activo y, encima, botón ± con regla para subir/bajar (se mantiene); el equilibrio no deshace el ajuste del usuario; bloqueo AE real al mantener presionado
- Exposición equilibrada en ambas cámaras: baja si hay zonas quemadas, sube si todo está oscuro, vuelve a lo normal si no; sin ganancia a ciegas; fps ~20 para nitidez; vista previa con nitidez y sombras levantadas; foto un poco más nítida

## v1.0.47 (2026-11-05) · [build-47](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-47)

- Exposición libre de noche (sin tope de 30 fps), medición sobre caras en ambas cámaras y vista previa con el tono de la mejora

## v1.0.45 (2026-11-05) · [build-45](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-45)

- Poca luz: la vista previa se aclara y la foto pasa por la mejora de noche

## v1.0.43 (2026-11-05) · [build-43](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-43)

- Frontal: exposición más larga permitida y medición sobre la cara; detección de poca luz según el tope de cada cámara

## v1.0.41 (2026-11-05) · [build-41](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-41)

- Enfoque y brillo al tocar tipo iPhone (cuadro con sol, arrastrar para brillo, mantener para bloquear AE/AF), volumen como obturador, modo silencio y pantalla siempre encendida

## v1.0.39 (2026-11-04) · [build-39](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-39)

- Seguir editando en otra app: enlace directo a CapCut y chooser de editores

## v1.0.37 (2026-11-04) · [build-37](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-37)

- Compartir fotos animadas como video, video 9:16 para historias o GIF, con mejor resolución

## v1.0.35 (2026-11-04) · [build-35](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-35)

- Atardecer: ráfaga real de 3 exposiciones alineadas y fusionadas; luna un poco más oscura por defecto
- Arreglo de compilación en Luna
- Modos Atardecer y Luna; la mejora respeta los colores del atardecer

## v1.0.31 (2026-11-04) · [build-31](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-31)

- Flash de pantalla sincronizado con la foto, guardado inmediato con mejora después, miniatura redonda sin bordes

## v1.0.29 (2026-11-04) · [build-29](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-29)

- Linterna: se aplica con la cámara activa; adelante, aro de luz en pantalla. Flash con formas para brillo en los ojos

## v1.0.27 (2026-11-04) · [build-27](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-27)

- Flash (auto, encendido, linterna), flash de pantalla de colores y modo visión nocturna

## v1.0.25 (2026-11-04) · [build-25](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-25)

- Botón de formato a la izquierda para no tapar el LIVE

## v1.0.23 (2026-11-04) · [build-23](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-23)

- Versión 1.0

## v1.0.21 (2026-11-04) · [build-21](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-21)

- Panel de calidad: valores por defecto correctos (mejora activada, efectos 720)
- v1.0: mejora automática, formatos, instantáneas con formas/marcos/filtros, boomerang, fotomatón, visor fluido

## v1.0.18 (2026-11-03) · [build-18](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-18)

- Pulido: editor de texto en hoja, botón Editar
- Hojas tipo iPhone, biblioteca con categorías, íconos en efectos, creador de efectos propios
- Live estilo iPhone en el visor; motor de efectos ampliado
- Live sincronizado con el disparo real; CameraX 1.4

## v1.0.13 (2026-11-03) · [build-13](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-13)

- v0.85: vista previa llena el ancho, máscaras alineadas y modo Belleza

## v1.0.12 (2026-11-03) · [build-12](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-12)

- v0.8: efectos fluidos (detección aparte + capa encima), stickers rediseñados y calibrados, filtros con miniaturas y panel de calidad

## v1.0.11 (2026-11-03) · [build-11](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-11)

- Logo propio: reloj con diafragma de cámara

## v1.0.10 (2026-11-03) · [build-10](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-10)

- v0.75: elegir resolución de foto, video, live y efectos

## v1.0.9 (2026-11-03) · [build-9](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-9)

- v0.7: biblioteca de efectos descargables y filtros de cámara instantánea

## v1.0.8 (2026-11-03) · [build-8](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-8)

- v0.6: modo EFECTOS con máscaras y detección de cara en tiempo real

## v1.0.7 (2026-11-03) · [build-7](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-7)

- v0.55: botón Live pantalla en Ajustes rápidos

## v1.0.6 (2026-11-03) · [build-6](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-6)

- v0.5: modos retrato, noche y time-lapse, instantánea tipo IG, lector QR, selección y editor en la galería

## v1.0.5 (2026-11-03) · [build-5](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-5)

- v0.45: QuickTake, ráfaga, temporizador, cuadrícula y nivel, zoom, modo video, texto en vivo, recorte, favoritos, álbumes y exportar

## v1.0.4 (2026-11-03) · [build-4](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-4)

- v0.4: galería completa, filtros en vivo y modo Instantánea

## v1.0.3 (2026-11-03) · [build-3](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-3)

- v0.3 Camaratrix: logo verde, mismo encuadre foto/video, galería tipo iPhone con efectos

## v1.0.2 (2026-11-03) · [build-2](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-2)

- v0.2: interfaz tipo cámara, audio en el clip y visor con mantener presionado

## v1.0.1 (2026-11-03) · [build-1](https://github.com/Henderdevelopmaster/foto-animada/releases/tag/build-1)

- Prueba de concepto: cámara con fotos animadas (Motion Photo)
