package com.opensource.svgaplayer

import android.os.Looper
import com.opensource.svgaplayer.proto.FrameEntity
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.SpriteEntity
import com.opensource.svgaplayer.utils.SVGAJsonDecoder
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SVGAStructureLimitTest {
    private fun Buffer.varint(value: Int): Buffer {
        var remaining = value
        while (remaining >= 128) {
            writeByte((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        return writeByte(remaining)
    }

    private fun message(tag: Int, bytes: ByteArray): ByteArray =
            Buffer().writeByte(tag * 8 + 2).varint(bytes.size).write(bytes).readByteArray()

    private fun frames(count: Int): ByteArray = message(4, Buffer().apply {
        repeat(count) { writeByte(18).writeByte(0) }
    }.readByteArray())

    @Test fun rejectsFramesBeforeReadingTheWholeMovieAndResetsWorkerBudget() {
        val input = ByteArrayInputStream(frames(SVGAMemoryLimits.structureNodes * 2))
        val failure = assertThrows(IOException::class.java) { MovieEntity.ADAPTER.decode(input) }
        assertTrue(failure.message.orEmpty().contains("structure"))
        assertTrue("Reject during decoding, before constructing the full tree", input.available() > 0)
        assertEquals(1, MovieEntity.ADAPTER.decode(frames(1)).sprites.single().frames.size)
    }

    @Test fun countsFramesAcrossSpritesAndCountsNestedShapes() {
        val manySprites = Buffer().apply {
            repeat(SVGAMemoryLimits.structureNodes / 100 + 1) { write(frames(100)) }
        }.readByteArray()
        assertThrows(IOException::class.java) { MovieEntity.ADAPTER.decode(manySprites) }
        val shapes = Buffer().apply {
            repeat(SVGAMemoryLimits.structureNodes) { writeByte(42).writeByte(0) }
        }.readByteArray()
        assertThrows(IOException::class.java) { MovieEntity.ADAPTER.decode(message(4, message(2, shapes))) }
        assertEquals(1, MovieEntity.ADAPTER.decode(frames(1)).sprites.size)
    }

    @Test fun rejectsRepeatedScalarFieldsEvenWhenTheyOverwriteTheSameValue() {
        val bytes = Buffer().apply {
            repeat(SVGAMemoryLimits.structureValues + 1) { writeByte(10).writeByte(0) }
        }.readByteArray()
        assertThrows(IOException::class.java) { MovieEntity.ADAPTER.decode(bytes) }
        assertEquals(1, MovieEntity.ADAPTER.decode(frames(1)).sprites.size)
    }

    @Test fun streamingJsonRejectsContainersValuesAndExcessiveDepth() {
        val containers = "{\"sprites\":[" + List(SVGAMemoryLimits.structureNodes * 2) { "{}" }.joinToString(",") + "]}"
        val input = ByteArrayInputStream(containers.toByteArray())
        assertThrows(IOException::class.java) { SVGAJsonDecoder.decode(input) }
        assertTrue(input.available() > 0)
        val values = "{\"values\":[" + List(SVGAMemoryLimits.structureValues + 1) { "0" }.joinToString(",") + "]}"
        assertThrows(IOException::class.java) { SVGAJsonDecoder.decode(ByteArrayInputStream(values.toByteArray())) }
        val nested = "{\"x\":".repeat(SVGAMemoryLimits.MAX_STRUCTURE_DEPTH) + "0" + "}".repeat(SVGAMemoryLimits.MAX_STRUCTURE_DEPTH)
        assertThrows(IOException::class.java) { SVGAJsonDecoder.decode(ByteArrayInputStream(nested.toByteArray())) }
        assertEquals(1, SVGAJsonDecoder.decode(ByteArrayInputStream("{\"ok\":1}".toByteArray())).getInt("ok"))
    }

    @Test fun directVideoConstructionAlsoRejectsOversizedFrameLists() {
        val empty = FrameEntity(null, null, null, null, emptyList())
        val sprite = SpriteEntity("v", List(SVGAMemoryLimits.structureNodes) { empty }, null)
        val movie = MovieEntity("2.0", null, emptyMap(), listOf(sprite), emptyList())
        assertThrows(IOException::class.java) { SVGAVideoEntity(movie, File(".")) }
    }

    @Test fun compressedAmplificationReportsErrorAndClosesOwnedInput() {
        val compressed = ByteArrayOutputStream().let { output ->
            DeflaterOutputStream(output).use { it.write(frames(100_000)) }
            output.toByteArray()
        }
        assertTrue(compressed.size < 1024)
        val previous = SVGAParser.threadPoolExecutor
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        var closed = false
        var errors = 0
        val input = object : ByteArrayInputStream(compressed) {
            override fun close() { closed = true; super.close() }
        }
        SVGAParser.setThreadPoolExecutor(worker)
        try {
            SVGAParser(RuntimeEnvironment.getApplication()).decodeFromInputStream(input, "limited-frames",
                    object : SVGAParser.ParseCompletion {
                        override fun onComplete(videoItem: SVGAVideoEntity) { videoItem.clear(); fail("Oversized movie accepted") }
                        override fun onError() { errors++ }
                    }, true)
            worker.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(closed)
            assertEquals(1, errors)
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(10, TimeUnit.SECONDS)
            SVGAParser.setThreadPoolExecutor(previous)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun oversizedExistingSampleReportsError() {
        val previous = SVGAParser.threadPoolExecutor
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        var errors = 0
        SVGAParser.setThreadPoolExecutor(worker)
        try {
            val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("mp3_to_long.svga"))
            SVGAParser(RuntimeEnvironment.getApplication()).decodeFromInputStream(input, "oversized-existing-sample",
                    object : SVGAParser.ParseCompletion {
                        override fun onComplete(videoItem: SVGAVideoEntity) { videoItem.clear(); fail("Oversized movie accepted") }
                        override fun onError() { errors++ }
                    }, true)
            worker.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, errors)
        } finally {
            worker.shutdownNow()
            worker.awaitTermination(10, TimeUnit.SECONDS)
            SVGAParser.setThreadPoolExecutor(previous)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
