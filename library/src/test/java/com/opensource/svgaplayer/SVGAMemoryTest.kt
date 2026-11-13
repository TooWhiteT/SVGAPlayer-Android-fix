package com.opensource.svgaplayer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import android.widget.FrameLayout
import com.opensource.svgaplayer.bitmap.BitmapSampleSizeCalculator
import com.opensource.svgaplayer.bitmap.SVGABitmapByteArrayDecoder
import com.opensource.svgaplayer.bitmap.SVGABitmapDecoder
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.AudioEntity
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SVGAMemoryTest {
    private fun video() = SVGAVideoEntity(JSONObject("""{"movie":{"viewBox":{"width":100,"height":100},"fps":20,"frames":10}}"""), File("."))

    private fun png(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)
        writer.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            writer.writeInt(data.size)
            writer.write(name)
            writer.write(data)
            val crc = CRC32().apply { update(name); update(data) }
            writer.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            val row = ByteArray(1 + width * 4)
            repeat(height) { stream.write(row) }
        }
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun matteCanvasReusesBitmapAndErasesPreviousMask() {
        val values = SVGACanvasDrawer.ShareValues()
        val canvas = values.shareMatteCanvas(100, 80)
        val bitmap = values.sharedMatteBitmap()
        canvas.drawColor(Color.WHITE)
        assertSame(canvas, values.shareMatteCanvas(100, 80))
        assertSame(bitmap, values.sharedMatteBitmap())
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(10, 10))
        values.shareMatteCanvas(120, 90)
        assertNotSame(bitmap, values.sharedMatteBitmap())
        assertEquals(120, values.sharedMatteBitmap().width)
        values.clear()
        values.shareMatteCanvas(120, 90)
        assertEquals(90, values.sharedMatteBitmap().height)
    }

    @Test fun samplingBoundsPanoramasEvenWhenTargetSizeIsMissing() {
        val options = BitmapFactory.Options().apply { outWidth = 32768; outHeight = 64 }
        val sample = BitmapSampleSizeCalculator.calculate(options, 0, 0, 512 * 1024)
        assertTrue(options.outWidth / sample <= SVGAMemoryLimits.MAX_BITMAP_DIMENSION)
        assertTrue(options.outWidth.toLong() / sample * options.outHeight / sample * 4 <= 512 * 1024)
    }

    @Test fun extremeDimensionsFailWithoutIntegerOverflow() {
        val options = BitmapFactory.Options().apply { outWidth = Int.MAX_VALUE; outHeight = Int.MAX_VALUE }
        assertThrows(IllegalArgumentException::class.java) {
            BitmapSampleSizeCalculator.calculate(options, 0, 0, 4)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun transparentPngIsSampledToBudget() {
        val bitmap = requireNotNull(SVGABitmapByteArrayDecoder.decodeBitmapFrom(png(4096, 512), 0, 0, 512 * 1024))
        assertTrue(bitmap.allocationByteCount <= 512 * 1024)
        assertTrue(bitmap.hasAlpha())
        assertTrue(bitmap.width < 4096)
    }

    @Test fun decodeRetriesWithSmallerBitmapAfterAllocationFailure() {
        var attempts = 0
        val decoder = object : SVGABitmapDecoder<Unit>() {
            override fun onDecode(data: Unit, ops: BitmapFactory.Options): Bitmap? {
                if (ops.inJustDecodeBounds) {
                    ops.outWidth = 4096; ops.outHeight = 4096
                    return null
                }
                if (++attempts == 1) throw OutOfMemoryError("simulated allocation failure")
                return Bitmap.createBitmap(4096 / ops.inSampleSize, 4096 / ops.inSampleSize, Bitmap.Config.ARGB_8888)
            }
        }
        val bitmap = requireNotNull(decoder.decodeBitmapFrom(Unit, 0, 0, 4 * 1024 * 1024))
        assertEquals(2, attempts)
        assertTrue(bitmap.allocationByteCount <= 4 * 1024 * 1024)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun manyTexturesShareBudgetAndPreparationDropsOriginalMovie() {
        val bytes = png(2048, 512).toByteString()
        val images = (0 until 40).associate { "image_$it" to bytes }
        val movie = MovieEntity("2.0", null, images, emptyList(), emptyList())
        val video = SVGAVideoEntity(movie, File("."))
        assertEquals(40, video.imageMap.size)
        assertTrue(video.imageMap.values.sumOf { it.allocationByteCount.toLong() } <= SVGAMemoryLimits.videoBitmapBytes)
        var completions = 0
        video.prepare({ completions++ }, null)
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertEquals(1, completions)
        assertNull(video.movieItem)
        video.clear()
        assertTrue(video.imageMap.isEmpty())
    }

    @Test fun drawableClearDropsDynamicResourcesWithoutRecyclingCallerBitmap() {
        val video = video()
        val dynamic = SVGADynamicEntity()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        dynamic.setDynamicImage(bitmap, "avatar")
        dynamic.setDynamicDrawer({ _, _ -> true }, "avatar")
        val drawable = SVGADrawable(video, dynamic)
        drawable.clear()
        drawable.clear()
        assertTrue(video.isCleared)
        assertTrue(dynamic.dynamicImage.isEmpty())
        assertTrue(dynamic.dynamicDrawer.isEmpty())
        assertFalse(bitmap.isRecycled)
    }

    @Test fun replacingDrawableWithSameVideoKeepsSharedVideoAlive() {
        val view = SVGAImageView(RuntimeEnvironment.getApplication())
        val video = video()
        val dynamic = SVGADynamicEntity()
        view.setVideoItem(video, dynamic)
        view.setVideoItem(video, dynamic)
        assertFalse(video.isCleared)
        view.clear()
        assertTrue(video.isCleared)
        assertNull(view.drawable)
        assertFalse(view.isAnimating)
    }

    @Test fun detachReleasesVideoByDefault() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val view = SVGAImageView(activity)
        val video = video()
        activity.setContentView(view)
        view.setVideoItem(video)
        view.startAnimation()
        activity.setContentView(FrameLayout(activity))
        assertTrue(video.isCleared)
        assertFalse(view.isAnimating)
        assertNull(view.drawable)
        controller.pause().stop().destroy()
    }

    @Test fun audioManagerReleasesNativePoolAndCanBeInitializedAgain() {
        SVGASoundManager.init()
        assertTrue(SVGASoundManager.isInit())
        SVGASoundManager.release()
        SVGASoundManager.release()
        assertFalse(SVGASoundManager.isInit())
        SVGASoundManager.init()
        assertTrue(SVGASoundManager.isInit())
        SVGASoundManager.release()
    }

    @Test fun excessiveAudioSamplesFailWithCleanupInsteadOfAllocatingNativeBuffers() {
        SVGACache.onCreate(RuntimeEnvironment.getApplication())
        val audios = (0..SVGAMemoryLimits.MAX_SOUND_SAMPLES).map {
            AudioEntity("audio_limit_test", 0, 1, it, 100)
        }
        val movie = MovieEntity("2.0", null,
                mapOf("audio_limit_test" to byteArrayOf(73, 68, 51, 0).toByteString()), emptyList(), audios)
        val video = SVGAVideoEntity(movie, File("."))
        assertThrows(IOException::class.java) { video.prepare({ fail("Unexpected success") }, null) }
        assertTrue(video.isCleared)
        assertTrue(video.audioList.isEmpty())
        assertNull(video.movieItem)
    }

    @Test fun clearRejectsDownloadedBitmapAlreadyPostedToMainThread() {
        val bytes = png(8, 8)
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serving = Executors.newSingleThreadExecutor()
        val response = serving.submit {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            val dynamic = SVGADynamicEntity()
            dynamic.setDynamicImage("http://127.0.0.1:${server.localPort}/avatar", "avatar")
            // A single-worker barrier ensures decoding and Handler.post have completed.
            executor.submit {}.get(10, TimeUnit.SECONDS)
            response.get(10, TimeUnit.SECONDS)
            dynamic.clearDynamicObjects()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dynamic.dynamicImage.isEmpty())
        } finally {
            executor.shutdownNow()
            SVGAParser.setThreadPoolExecutor(previous)
            server.close()
            serving.shutdownNow()
        }
    }

    @Test fun malformedInputIsClosedAndReportedThroughErrorCallback() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        SVGAParser.setThreadPoolExecutor(executor)
        var closed = false
        var errors = 0
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { closed = true; super.close() }
        }
        try {
            SVGAParser(RuntimeEnvironment.getApplication()).decodeFromInputStream(input, "broken", object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) { fail("Unexpected parse success") }
                override fun onError() { errors++ }
            }, true)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(closed)
            assertEquals(1, errors)
        } finally {
            executor.shutdownNow()
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun parsesExistingCompressedZipAndAudioSamples() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            val context = RuntimeEnvironment.getApplication()
            listOf("rose_2.0.0.svga", "matteBitmap_1.x.svga", "jojo_audio.svga").forEach { name ->
                var completed = 0
                var failed = false
                val input = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
                SVGAParser(context).decodeFromInputStream(input, SVGACache.buildCacheKey(name), object : SVGAParser.ParseCompletion {
                    override fun onComplete(videoItem: SVGAVideoEntity) {
                        assertTrue(name, videoItem.frames > 0)
                        assertTrue(name, videoItem.imageMap.isNotEmpty())
                        assertNull(videoItem.movieItem)
                        completed++
                        videoItem.clear()
                    }
                    override fun onError() { failed = true }
                }, true)
                executor.submit {}.get(20, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
                assertFalse("Parsing failed for $name", failed)
                assertEquals(name, 1, completed)
            }
        } finally {
            executor.shutdownNow()
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }
}
