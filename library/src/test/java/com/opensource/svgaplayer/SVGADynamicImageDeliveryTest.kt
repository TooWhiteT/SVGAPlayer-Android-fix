package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.os.Looper
import android.os.Message
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SVGADynamicImageDeliveryTest {
    private fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun queued(message: Runnable): Boolean {
        var next = field(Looper.getMainLooper().queue, "mMessages") as? Message
        val link = Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        while (next != null) {
            if (next.callback === message) return true
            next = link.get(next) as? Message
        }
        return false
    }

    private fun withDownloadedImage(block: (SVGADynamicEntity, Any, Bitmap, Runnable) -> Unit) {
        val bytes = ByteArrayOutputStream().let { output ->
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serving = Executors.newSingleThreadExecutor()
        val response = serving.submit {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
        val previous = SVGAParser.threadPoolExecutor
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val dynamic = SVGADynamicEntity()
        SVGAParser.setThreadPoolExecutor(worker)
        try {
            dynamic.setDynamicImage("http://127.0.0.1:${server.localPort}/avatar", "avatar")
            worker.submit {}.get(10, TimeUnit.SECONDS)
            response.get(10, TimeUnit.SECONDS)
            val request = (field(dynamic, "imageRequests") as Map<*, *>).values.single()!!
            val bitmap = field(request, "pendingBitmap") as Bitmap
            val message = field(request, "delivery") as Runnable
            assertTrue(queued(message))
            block(dynamic, request, bitmap, message)
        } finally {
            dynamic.clearDynamicObjects()
            worker.shutdownNow()
            worker.awaitTermination(10, TimeUnit.SECONDS)
            SVGAParser.setThreadPoolExecutor(previous)
            server.close()
            serving.shutdownNow()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun clearRemovesQueuedMessageAndRecyclesBitmapWithoutDrainingMainQueue() = withDownloadedImage { dynamic, request, bitmap, message ->
        dynamic.clearDynamicObjects()
        assertTrue(bitmap.isRecycled)
        assertNull(field(request, "pendingBitmap"))
        assertFalse(queued(message))
        assertTrue((field(dynamic, "imageReservations") as Map<*, *>).isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dynamic.dynamicImage.isEmpty())
    }

    @Test fun callerBitmapReplacementCancelsPendingResultWithoutRecyclingCallerBitmap() = withDownloadedImage { dynamic, _, bitmap, message ->
        val callerBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        dynamic.setDynamicImage(callerBitmap, "avatar")
        assertTrue(bitmap.isRecycled)
        assertFalse(queued(message))
        assertTrue((field(dynamic, "imageReservations") as Map<*, *>).isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(callerBitmap, dynamic.dynamicImage["avatar"])
        dynamic.clearDynamicObjects()
        assertFalse(callerBitmap.isRecycled)
    }

    @Test fun urlReplacementAlsoDetachesTheOldQueuedBitmap() = withDownloadedImage { dynamic, _, bitmap, message ->
        dynamic.setDynamicImage("http://127.0.0.1:1/replacement", "avatar")
        assertTrue(bitmap.isRecycled)
        assertFalse(queued(message))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dynamic.dynamicImage.isEmpty())
    }

    @Test fun deliveredBitmapStaysValidAfterClear() = withDownloadedImage { dynamic, request, bitmap, _ ->
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(bitmap, dynamic.dynamicImage["avatar"])
        assertNull(field(request, "pendingBitmap"))
        assertTrue((field(dynamic, "imageReservations") as Map<*, *>).isEmpty())
        dynamic.clearDynamicObjects()
        assertFalse(bitmap.isRecycled)
    }
}
