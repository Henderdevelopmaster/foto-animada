package com.hender.fotoanimada

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRequestTest {
    @Test
    fun cancellationAndEncoderCompletionDeliverOnlyOneResult() {
        val calls = AtomicInteger()
        val start = CountDownLatch(1)
        val request = MotionRecorder.ClipRequest(waitForMark = true) { calls.incrementAndGet() }
        val encoder = thread {
            start.await()
            request.complete(null)
        }

        start.countDown()
        request.cancel()
        encoder.join()

        assertTrue(request.isComplete)
        assertEquals(1, calls.get())
    }
}
