package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SVGAMatteRenderingTest {
    private fun drawable(): SVGADrawable {
        fun sprite(key: String, args: String, color: String, matte: String = ""): JSONObject =
                JSONObject("""{"imageKey":"$key","matteKey":"$matte","frames":[{"alpha":1,"shapes":[{"type":"rect","args":$args,"styles":{"fill":$color}}]}]}""")
        val json = JSONObject("""{"movie":{"viewBox":{"width":100,"height":100},"fps":20,"frames":1}}""")
        json.put("sprites", JSONArray().apply {
            put(sprite("mask.matte", """{"x":20,"y":20,"width":40,"height":40,"cornerRadius":0}""", "[1,1,1,1]"))
            put(sprite("color", """{"x":0,"y":0,"width":100,"height":100,"cornerRadius":0}""", "[1,0,0,1]", "mask.matte"))
        })
        return SVGADrawable(SVGAVideoEntity(json, File("."))).apply { cleared = false }
    }

    @Test fun maskPreservesTransparencyAndClippedOffsets() {
        val drawable = drawable()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            drawable.draw(Canvas(bitmap))
            assertEquals(Color.RED, bitmap.getPixel(40, 40))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(10, 40))
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLUE)
            canvas.clipRect(30, 35, 80, 85)
            drawable.draw(canvas)
            assertEquals(Color.RED, bitmap.getPixel(40, 40))
            assertEquals(Color.BLUE, bitmap.getPixel(25, 40))
            assertEquals(Color.BLUE, bitmap.getPixel(70, 40))
        } finally { drawable.clear() }
    }

    @Test fun largeMaskBuffersShareBudgetAndReuseTheSameSampledSize() {
        val values = SVGACanvasDrawer.ShareValues()
        try {
            val layerCanvas = values.shareLayerCanvas(8192, 4096)
            val layer = values.sharedLayerBitmap()
            values.shareMatteCanvas(8192, 4096)
            val mask = values.sharedMatteBitmap()
            assertSame(layerCanvas, values.shareLayerCanvas(8192, 4096))
            assertSame(layer, values.sharedLayerBitmap())
            assertTrue(mask.width < 8192)
            assertEquals(mask.width, layer.width)
            assertEquals(mask.height, layer.height)
            assertTrue(mask.width <= SVGAMemoryLimits.MAX_BITMAP_DIMENSION)
            assertTrue(mask.allocationByteCount.toLong() + layer.allocationByteCount <= SVGAMemoryLimits.matteBytes)
        } finally { values.clear() }
    }

    @Test fun sampledMaskStillRendersAtTheDestinationSize() {
        val drawable = drawable()
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        try {
            drawable.draw(Canvas(bitmap))
            assertEquals(Color.RED, bitmap.getPixel(1024, 1024))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 100))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(1800, 1024))
        } finally { drawable.clear() }
    }
}
