package com.opensource.svgaplayer

import android.os.Looper
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.MovieParams
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SVGAInputStreamCancellationTest {
    private val previous = SVGAParser.threadPoolExecutor
    private val pool = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val parser = SVGAParser(RuntimeEnvironment.getApplication())
    private val stalled = mutableListOf<StalledInput>()
    private var errors = 0
    private var completions = 0
    private val callback = object : SVGAParser.ParseCompletion {
        override fun onError() { errors++ }
        override fun onComplete(videoItem: SVGAVideoEntity) {
            assertEquals(3, videoItem.frames)
            completions++
            videoItem.clear()
        }
    }

    private class StalledInput(val failClose: Boolean, private val closeGate: CountDownLatch?) : InputStream() {
        val reading = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closes = AtomicInteger()

        override fun read(): Int {
            reading.countDown()
            while (true) {
                try {
                    unblock.await()
                    return -1
                } catch (_: InterruptedException) {
                    // Model a stream that needs a concurrent close to wake the read.
                }
            }
        }

        override fun close() {
            closes.incrementAndGet()
            closing.countDown()
            closeGate?.await()
            unblock.countDown()
            if (failClose) throw IOException("Close failed after unblocking read")
        }
    }

    private class TrackedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet(); super.close() }
    }

    private fun movieBytes(): ByteArray {
        val movie = MovieEntity.Builder().params(MovieParams.Builder().fps(20).frames(3).build()).build()
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { it.write(MovieEntity.ADAPTER.encode(movie)) }
        return bytes.toByteArray()
    }

    private fun validInput() = TrackedInput(movieBytes())

    private fun stalledInput(failClose: Boolean = false, closeGate: CountDownLatch? = null) =
            StalledInput(failClose, closeGate).also { stalled.add(it) }

    @Before fun installExecutor() = SVGAParser.setThreadPoolExecutor(pool)

    private fun finishRequests() {
        pool.shutdown()
        assertTrue("Parser workers must finish", pool.awaitTermination(10, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun restoreExecutor() {
        stalled.forEach { it.unblock.countDown() }
        pool.shutdownNow()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        SVGAParser.setThreadPoolExecutor(previous)
    }

    @Test fun legacyEntryTimesOutStalledOwnedReadsAndDeliversFollowingVideo() {
        parser.ownedInputReadTimeoutMillis = 250
        val first = stalledInput(failClose = true)
        val second = stalledInput()
        parser.decodeFromInputStream(first, "stalled-first", callback, true)
        parser.decodeFromInputStream(second, "stalled-second", callback, true)
        parser.decodeFromInputStream(validInput(), "healthy", callback, true)
        finishRequests()
        assertEquals(1, first.closes.get())
        assertEquals(1, second.closes.get())
        assertEquals(2, errors)
        assertEquals(1, completions)
    }

    @Test fun oneBlockingCloseDoesNotDelayAnotherReadTimeout() {
        parser.ownedInputReadTimeoutMillis = 100
        val releaseFirstClose = CountDownLatch(1)
        val first = stalledInput(closeGate = releaseFirstClose)
        val second = stalledInput()
        try {
            parser.decodeFromInputStream(first, "slow-close", callback, true)
            assertTrue(first.closing.await(10, TimeUnit.SECONDS))
            parser.decodeFromInputStream(second, "another-timeout", callback, true)
            assertTrue(second.unblock.await(10, TimeUnit.SECONDS))
            assertEquals(1, first.unblock.count)
        } finally {
            releaseFirstClose.countDown()
        }
        finishRequests()
        assertEquals(2, errors)
        assertEquals(1, first.closes.get())
        assertEquals(1, second.closes.get())
    }

    @Test fun cancellingRunningAndQueuedOwnedReadsFreesWorkersAndQueueCapacity() {
        val first = stalledInput()
        val second = stalledInput()
        val cancelFirst = parser.decodeFromInputStreamWithCancel(first, "first", callback, true)
        val cancelSecond = parser.decodeFromInputStreamWithCancel(second, "second", callback, true)
        assertTrue(first.reading.await(10, TimeUnit.SECONDS))
        assertTrue(second.reading.await(10, TimeUnit.SECONDS))

        val queued = stalledInput()
        val cancelQueued = parser.decodeFromInputStreamWithCancel(queued, "queued", callback, true)
        cancelQueued()
        cancelQueued()
        assertEquals(1, queued.reading.count)
        assertEquals(1, queued.closes.get())
        val healthy = validInput()
        val cancelHealthy = parser.decodeFromInputStreamWithCancel(healthy, "healthy", callback, true)
        cancelFirst()
        cancelSecond()
        finishRequests()
        cancelHealthy()
        assertEquals(1, first.closes.get())
        assertEquals(1, second.closes.get())
        assertEquals(1, healthy.closes.get())
        assertEquals(1, completions)
        assertEquals(0, errors)
    }

    @Test fun cancellingBorrowedReadLeavesItsStreamOpenAndSuppressesDelivery() {
        val input = stalledInput()
        val cancel = parser.decodeFromInputStreamWithCancel(input, "borrowed", callback)
        assertTrue(input.reading.await(10, TimeUnit.SECONDS))
        cancel()
        assertEquals(0, input.closes.get())
        input.close()
        finishRequests()
        assertEquals(1, input.closes.get())
        assertEquals(0, completions)
        assertEquals(0, errors)
    }

    @Test fun failedAndRejectedOwnedInputsCloseOnceEvenAfterLateCancellation() {
        val malformed = TrackedInput(byteArrayOf(1))
        val cancelMalformed = parser.decodeFromInputStreamWithCancel(malformed, "broken", callback, true)
        finishRequests()
        val rejected = validInput()
        val cancelRejected = parser.decodeFromInputStreamWithCancel(rejected, "rejected", callback, true)
        shadowOf(Looper.getMainLooper()).idle()
        cancelMalformed()
        cancelRejected()
        assertEquals(2, errors)
        assertEquals(1, malformed.closes.get())
        assertEquals(1, rejected.closes.get())
    }

    @Test fun ownedCloseMustSucceedBeforeVideoCanBeDelivered() {
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closes = AtomicInteger()
        val input = object : ByteArrayInputStream(movieBytes()) {
            override fun close() {
                closes.incrementAndGet()
                closing.countDown()
                releaseClose.await()
                throw IOException("Close failed after EOF")
            }
        }
        try {
            parser.decodeFromInputStream(input, "close-before-delivery", callback, true)
            assertTrue(closing.await(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, completions)
            assertEquals(0, errors)
        } finally {
            releaseClose.countDown()
        }
        finishRequests()
        assertEquals(0, completions)
        assertEquals(1, errors)
        assertEquals(1, closes.get())
    }
}
