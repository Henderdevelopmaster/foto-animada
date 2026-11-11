package com.hender.fotoanimada

import org.junit.Assert.assertEquals
import org.junit.Test

/** Giro del cel: la foto de lado debe salir de lado, sin saltar en el borde entre posiciones. */
class OrientationTest {
    @Test
    fun clearPositions() {
        assertEquals(0, Orientation.snap(5, 0))
        assertEquals(90, Orientation.snap(95, 0))
        assertEquals(180, Orientation.snap(185, 90))
        assertEquals(270, Orientation.snap(268, 0))
    }

    @Test
    fun nearTheBorder_staysPut() {
        // 50° está pasado del medio camino (45°) pero dentro del margen: sigue vertical
        assertEquals(0, Orientation.snap(50, 0))
        assertEquals(90, Orientation.snap(40, 90))
        // 61° ya es claro
        assertEquals(90, Orientation.snap(61, 0))
        // Cerca de 360 es vertical, y 300 con el cel ya a 270 se queda en 270
        assertEquals(0, Orientation.snap(350, 0))
        assertEquals(270, Orientation.snap(300, 270))
        assertEquals(0, Orientation.snap(350, 270))
    }

    @Test
    fun surfaceRotationMatchesAndroid() {
        assertEquals(0, Orientation.surfaceRotation(0)) // ROTATION_0
        assertEquals(3, Orientation.surfaceRotation(90)) // ROTATION_270
        assertEquals(2, Orientation.surfaceRotation(180)) // ROTATION_180
        assertEquals(1, Orientation.surfaceRotation(270)) // ROTATION_90
    }
}
