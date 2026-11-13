package com.opensource.svgaplayer

import com.opensource.svgaplayer.utils.SVGAIO
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

class SVGAIOTest {
    @Test fun readsAcrossSegmentsWithShortReads() {
        val bytes = ByteArray(8192 * 2 + 17) { (it % 251).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return super.read(buffer, offset, minOf(length, 97))
            }
        }
        assertArrayEquals(bytes, SVGAIO.readBytes(input, bytes.size.toLong()))
    }

    @Test fun zeroLimitAcceptsOnlyEmptyInput() {
        assertArrayEquals(byteArrayOf(), SVGAIO.readBytes(ByteArrayInputStream(byteArrayOf()), 0))
        assertThrows(IOException::class.java) {
            SVGAIO.readBytes(ByteArrayInputStream(byteArrayOf(1)), 0)
        }
    }

    @Test fun acceptsExactLimit() {
        assertArrayEquals(byteArrayOf(1, 2, 3), SVGAIO.readBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
    }

    @Test fun rejectsOversizedInputBeforeBufferCanGrowWithoutBound() {
        assertThrows(IOException::class.java) {
            SVGAIO.readBytes(ByteArrayInputStream(ByteArray(8192)), 16)
        }
    }

    @Test fun callerRetainsStreamOwnershipOnSuccessAndSizeError() {
        for (limit in listOf(3L, 2L)) {
            var closed = false
            val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun close() { closed = true; super.close() }
            }
            if (limit == 3L) {
                assertArrayEquals(byteArrayOf(1, 2, 3), SVGAIO.readBytes(input, limit))
            } else {
                assertThrows(IOException::class.java) { SVGAIO.readBytes(input, limit) }
            }
            assertFalse("readBytes must not close its input", closed)
        }
    }

    @Test fun propagatesReadFailuresWithoutClosingInput() {
        val failure = IOException("simulated read failure")
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        assertSame(failure, assertThrows(IOException::class.java) { SVGAIO.readBytes(input, 16) })
        assertFalse(closed)
    }

    @Test fun interruptedRequestStopsReading() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                SVGAIO.readBytes(ByteArrayInputStream(byteArrayOf(1)), 16)
            }
            assertTrue("Cancellation must preserve the interrupt flag", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
