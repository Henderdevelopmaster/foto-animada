package com.hender.fotoanimada

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingPhotoTest {
    @Test
    fun partialWriteIsDiscardedWithoutPublishing() {
        val original = byteArrayOf(1, 2, 3)
        val pending = ByteArrayOutputStream()
        val error = IOException("Sin espacio")
        val steps = mutableListOf<String>()

        val thrown = assertThrows(IOException::class.java) {
            publishPendingPhoto(
                write = {
                    pending.write(byteArrayOf(8, 9))
                    throw error
                },
                publish = { steps.add("publish") },
                discard = {
                    steps.add("discard")
                    pending.reset()
                },
            )
        }

        assertSame(error, thrown)
        assertEquals(listOf("discard"), steps)
        assertEquals(0, pending.size())
        assertArrayEquals(byteArrayOf(1, 2, 3), original)
    }

    @Test
    fun failedPublicationDiscardsThePendingPhoto() {
        val steps = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            publishPendingPhoto(
                write = { steps.add("write") },
                publish = { throw IOException("No se pudo publicar") },
                discard = { steps.add("discard") },
            )
        }
        assertEquals(listOf("write", "discard"), steps)
    }

    @Test
    fun successfulWritePublishesOnceWithoutDiscarding() {
        val steps = mutableListOf<String>()
        publishPendingPhoto(
            write = { steps.add("write") },
            publish = { steps.add("publish") },
            discard = { steps.add("discard") },
        )
        assertEquals(listOf("write", "publish"), steps)
    }
}
