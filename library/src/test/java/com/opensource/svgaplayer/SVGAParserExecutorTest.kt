package com.opensource.svgaplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SVGAParserExecutorTest {
    @Test fun replacingDefaultPoolDrainsQueuedWorkAndRetiresIdleThreads() {
        val original = SVGAParser.threadPoolExecutor
        val originalKeepAlive = original.getKeepAliveTime(TimeUnit.NANOSECONDS)
        val replacement = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
        val started = CountDownLatch(2)
        val finish = CountDownLatch(1)
        try {
            original.setKeepAliveTime(50, TimeUnit.MILLISECONDS)
            val workers = List(2) {
                original.submit<Thread> {
                    started.countDown()
                    finish.await()
                    Thread.currentThread()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val queued = original.submit { }
            SVGAParser.setThreadPoolExecutor(replacement)
            SVGAParser.threadPoolExecutor.submit { }.get(5, TimeUnit.SECONDS)

            finish.countDown()
            queued.get(5, TimeUnit.SECONDS)
            workers.forEach { result ->
                val thread = result.get(5, TimeUnit.SECONDS)
                thread.join(5_000)
                assertFalse("Replaced default pool still has a live worker", thread.isAlive)
            }
            SVGAParser.setThreadPoolExecutor(original)
            assertFalse(replacement.isShutdown)
            replacement.submit { }.get(5, TimeUnit.SECONDS)
        } finally {
            finish.countDown()
            original.setKeepAliveTime(originalKeepAlive, TimeUnit.NANOSECONDS)
            SVGAParser.setThreadPoolExecutor(original)
            replacement.shutdownNow()
        }
    }
}
