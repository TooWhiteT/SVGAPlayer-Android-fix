package com.opensource.svgaplayer

import android.os.Looper
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SVGAParseDeliveryTest {
    private fun video() = SVGAVideoEntity(JSONObject("""{"movie":{"fps":20,"frames":10}}"""), File("."))

    private fun receiver(onComplete: (SVGAVideoEntity) -> Unit = { it.clear() }, onError: () -> Unit = { fail("Unexpected error") }) =
            object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) = onComplete.invoke(videoItem)
                override fun onError() = onError.invoke()
            }

    @Test fun cancellingQueuedDeliveryClearsVideoBeforeMainThreadRuns() {
        var delivered = 0
        val request = SVGAParseRequest(receiver({ delivered++; it.clear() }), "queued")
        val video = video()
        try {
            request.begin()
            assertTrue(request.attach(video))
            request.ready()
            request.workerFinished()
            request.cancel()
            assertTrue(video.isCleared)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, delivered)
        } finally {
            request.cancel()
            request.workerFinished()
        }
    }

    @Test fun nullReceiverDiscardsImmediatelyAndReturnsCapacity() {
        repeat(SVGAMemoryLimits.MAX_PENDING_VIDEOS + 2) {
            val request = SVGAParseRequest(null, "unobserved")
            val video = video()
            try {
                request.begin()
                assertTrue(request.attach(video))
                request.ready()
                assertTrue(video.isCleared)
            } finally {
                request.cancel()
                request.workerFinished()
            }
        }
    }

    @Test fun cancelledParsingKeepsCapacityUntilWorkerExits() {
        val first = SVGAParseRequest(receiver(), "parsing")
        val second = SVGAParseRequest(receiver(), "parsing")
        val third = SVGAParseRequest(receiver(), "would-block-main")
        try {
            first.begin()
            second.begin()
            first.cancel()
            assertThrows(IOException::class.java) { third.begin() }
            first.workerFinished()
            val next = SVGAParseRequest(null, "capacity-returned")
            try { next.begin() } finally { next.cancel(); next.workerFinished() }
        } finally {
            first.cancel(); first.workerFinished()
            second.cancel(); second.workerFinished()
            third.cancel(); third.workerFinished()
        }
    }

    @Test fun failedPreparationClearsVideoAndReturnsCapacity() {
        var errors = 0
        val request = SVGAParseRequest(receiver(onError = { errors++ }), "broken")
        val video = video()
        try {
            request.begin()
            request.attach(video)
            request.fail(IOException("simulated preparation failure"))
            request.workerFinished()
            assertTrue(video.isCleared)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, errors)
        } finally {
            request.cancel(); request.workerFinished()
        }
    }

    @Test fun interruptedAdmissionClosesOwnedInput() {
        val first = SVGAParseRequest(null, "held-slot-1")
        val second = SVGAParseRequest(null, "held-slot-2")
        val previous = SVGAParser.threadPoolExecutor
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var errors = 0
        val executor = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
            override fun beforeExecute(thread: Thread, runnable: Runnable) { started.countDown() }
        }
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                fail("Input must not be read without a parsing slot")
                return -1
            }
            override fun close() { super.close(); closed.countDown() }
        }
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            first.begin()
            second.begin()
            SVGAParser(RuntimeEnvironment.getApplication()).decodeFromInputStream(
                    input, "interrupted-admission", receiver(onError = { errors++ }), true)
            assertTrue(started.await(10, TimeUnit.SECONDS))
            executor.shutdownNow()
            assertTrue(closed.await(10, TimeUnit.SECONDS))
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, errors)
        } finally {
            first.cancel(); first.workerFinished()
            second.cancel(); second.workerFinished()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }

    @Test fun cancellationAfterCallbackDoesNotClearDeliveredVideo() {
        var delivered: SVGAVideoEntity? = null
        val request = SVGAParseRequest(receiver({ delivered = it }), "delivered")
        val video = video()
        try {
            request.begin()
            request.attach(video)
            request.ready()
            request.workerFinished()
            shadowOf(Looper.getMainLooper()).idle()
            request.cancel()
            assertSame(video, delivered)
            assertFalse(video.isCleared)
        } finally {
            video.clear()
            request.cancel(); request.workerFinished()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pausedMainThreadBackpressuresFurtherParsing() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val parsed = LinkedBlockingQueue<Int>()
        var delivered = 0
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            val parser = SVGAParser(RuntimeEnvironment.getApplication())
            val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("rose_2.0.0.svga")).use { it.readBytes() }
            repeat(10) { index ->
                val input = object : ByteArrayInputStream(bytes) {
                    override fun close() { super.close(); parsed.add(index) }
                }
                parser.decodeFromInputStream(input, "backpressure_$index", receiver({ delivered++; it.clear() }), true)
            }
            repeat(SVGAMemoryLimits.MAX_PENDING_VIDEOS) {
                assertNotNull(parsed.poll(10, TimeUnit.SECONDS))
            }
            assertNull("No more videos may finish while main-thread delivery is paused", parsed.poll(200, TimeUnit.MILLISECONDS))
            assertEquals(0, delivered)
            repeat(10 - SVGAMemoryLimits.MAX_PENDING_VIDEOS) {
                shadowOf(Looper.getMainLooper()).runOneTask()
                assertNotNull(parsed.poll(10, TimeUnit.SECONDS))
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(10, delivered)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }

    @Test fun urlCancellationRemovesAlreadyQueuedResult() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        SVGAParser.setThreadPoolExecutor(executor)
        var delivered = 0
        var cancellation: (() -> Unit)? = null
        try {
            val parser = SVGAParser(RuntimeEnvironment.getApplication())
            parser.fileDownloader = object : SVGAParser.FileDownloader() {
                override fun resume(url: URL, complete: (java.io.InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
                    val task = executor.submit {
                        requireNotNull(javaClass.classLoader?.getResourceAsStream("rose_2.0.0.svga")).use(complete)
                    }
                    return { task.cancel(true); Unit }
                }
            }
            cancellation = parser.decodeFromURL(URL("https://example.invalid/queued-svga"), receiver({ delivered++; it.clear() }))
            executor.submit {}.get(10, TimeUnit.SECONDS)
            cancellation?.invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, delivered)
            // Repeated synchronous admission proves cancellation returned the retained slot.
            repeat(SVGAMemoryLimits.MAX_PENDING_VIDEOS + 1) {
                val next = SVGAParseRequest(null, "after-cancellation")
                try { next.begin() } finally { next.cancel(); next.workerFinished() }
            }
        } finally {
            cancellation?.invoke()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }
}
