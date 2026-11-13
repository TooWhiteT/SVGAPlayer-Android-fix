package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class SVGAImageReplacementTest {
    private fun verifyReplacement(replace: (SVGAImageView, Bitmap) -> Unit) {
        val view = SVGAImageView(RuntimeEnvironment.getApplication())
        val video = SVGAVideoEntity(JSONObject("""{"movie":{"fps":20,"frames":10}}"""), File("."))
        val dynamic = SVGADynamicEntity()
        val borrowed = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        video.imageMap["old"] = borrowed
        dynamic.setDynamicImage(borrowed, "avatar")
        view.setVideoItem(video, dynamic)
        view.startAnimation()

        replace(view, borrowed)

        assertTrue(video.isCleared)
        assertTrue(video.imageMap.isEmpty())
        assertTrue(dynamic.dynamicImage.isEmpty())
        assertFalse(view.isAnimating)
        assertFalse(view.drawable is SVGADrawable)
        assertFalse(borrowed.isRecycled)
        view.clear()
    }

    @Test fun bitmapReplacementReleasesOldVideoBeforeParentResetsDrawable() {
        verifyReplacement { view, borrowed ->
            view.setImageBitmap(borrowed)
            assertSame(borrowed, (view.drawable as BitmapDrawable).bitmap)
        }
    }

    @Test fun resourceReplacementReleasesOldVideo() {
        verifyReplacement { view, _ ->
            view.setImageResource(android.R.drawable.ic_delete)
            assertNotNull(view.drawable)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun uriReplacementReleasesOldVideo() {
        val file = File.createTempFile("svga-replacement", ".png", RuntimeEnvironment.getApplication().cacheDir)
        try {
            verifyReplacement { view, borrowed ->
                file.outputStream().use { assertTrue(borrowed.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                view.setImageURI(Uri.fromFile(file))
                assertNotNull(view.drawable)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun nullBitmapReplacementReleasesOldVideo() {
        verifyReplacement { view, _ -> view.setImageBitmap(null) }
    }

    @Test fun manualImageReplacementCancelsQueuedXmlAssetLoad() {
        verifyQueuedXmlAssetLoadIsCancelled { view ->
            view.setImageBitmap(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        }
    }

    @Test fun settingNullDrawableCancelsQueuedXmlAssetLoad() {
        verifyQueuedXmlAssetLoadIsCancelled { view -> view.setImageDrawable(null) }
    }

    private fun verifyQueuedXmlAssetLoadIsCancelled(replace: (SVGAImageView) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.assets.open("rose_2.0.0.svga").use { assertTrue(it.read() >= 0) }
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val gate = CountDownLatch(1)
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            executor.submit { gate.await() }
            val attrs = Robolectric.buildAttributeSet().addAttribute(R.attr.source, "rose_2.0.0.svga").build()
            val view = SVGAImageView(context, attrs)
            replace(view)
            val replacement = view.drawable
            gate.countDown()
            executor.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(replacement, view.drawable)
            view.clear()
        } finally {
            gate.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }
}
