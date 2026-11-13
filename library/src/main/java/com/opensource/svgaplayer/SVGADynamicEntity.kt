package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.opensource.svgaplayer.bitmap.SVGABitmapByteArrayDecoder
import com.opensource.svgaplayer.utils.SVGAIO
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import com.opensource.svgaplayer.utils.log.LogUtils
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import android.graphics.Canvas
import android.text.BoringLayout
import android.text.StaticLayout
import android.text.TextPaint
import java.net.HttpURLConnection
import java.net.URL

/**
 * Created by cuiminghui on 2017/3/30.
 */
class SVGADynamicEntity {

    private class ImageRequest {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var task: Future<*>? = null
        private var pendingBitmap: Bitmap? = null
        private var delivery: Runnable? = null

        @Synchronized
        fun post(owner: WeakReference<SVGADynamicEntity>, key: String, bitmap: Bitmap): Boolean {
            if (cancelled) {
                bitmap.recycle()
                return false
            }
            pendingBitmap = bitmap
            // The message only holds the request. Cancellation can detach its bitmap immediately.
            val message = Runnable { deliverImage(owner, this, key) }
            delivery = message
            if (imageHandler.post(message)) return true
            delivery = null
            pendingBitmap = null
            bitmap.recycle()
            return false
        }

        @Synchronized
        fun takeBitmap(): Bitmap? {
            delivery = null
            val bitmap = pendingBitmap
            pendingBitmap = null
            return bitmap
        }

        fun cancel(): Boolean {
            val discarded = synchronized(this) {
                cancelled = true
                delivery?.let { imageHandler.removeCallbacks(it) }
                delivery = null
                val bitmap = pendingBitmap
                pendingBitmap = null
                bitmap?.recycle()
                bitmap != null
            }
            connection?.disconnect()
            task?.cancel(true)
            return discarded
        }
    }

    private val imageRequests = ConcurrentHashMap<String, ImageRequest>()
    private val imageReservations = HashMap<ImageRequest, Long>()

    @Synchronized
    private fun reserveImage(request: ImageRequest, key: String): Long {
        if (request.cancelled || imageRequests[key] !== request) return 0
        val retained = dynamicImage.values.sumOf { if (it.isRecycled) 0L else it.allocationByteCount.toLong() }
        val available = SVGAMemoryLimits.videoBitmapBytes - retained - imageReservations.values.sum()
        val budget = minOf(SVGAMemoryLimits.bitmapBytes, available)
        if (budget >= 4) imageReservations[request] = budget
        return budget
    }

    @Synchronized
    private fun finishImage(request: ImageRequest, key: String, bitmap: Bitmap): Boolean {
        imageReservations.remove(request)
        if (request.cancelled || !imageRequests.remove(key, request)) return false
        val retained = dynamicImage.filterKeys { it != key }.values.sumOf {
            if (it.isRecycled) 0L else it.allocationByteCount.toLong()
        }
        if (retained + bitmap.allocationByteCount > SVGAMemoryLimits.videoBitmapBytes) return false
        dynamicImage[key] = bitmap
        isTextDirty = true
        return true
    }

    @Synchronized
    private fun discardImage(request: ImageRequest, key: String) {
        imageReservations.remove(request)
        imageRequests.remove(key, request)
    }

    @Synchronized
    private fun postImage(owner: WeakReference<SVGADynamicEntity>, request: ImageRequest,
                          key: String, bitmap: Bitmap): Boolean {
        if (request.cancelled || imageRequests[key] !== request) {
            bitmap.recycle()
            discardImage(request, key)
            return false
        }
        imageReservations[request] = bitmap.allocationByteCount.toLong()
        val posted = request.post(owner, key, bitmap)
        if (!posted) discardImage(request, key)
        return posted
    }

    private fun cancelImage(request: ImageRequest) {
        // A running decoder still owns its reservation until its finally block exits.
        // A queued result is detached and recycled synchronously, so refund it now.
        if (request.cancel()) imageReservations.remove(request)
    }

    internal var dynamicHidden: HashMap<String, Boolean> = hashMapOf()

    internal var dynamicImage: HashMap<String, Bitmap> = hashMapOf()

    internal var dynamicText: HashMap<String, String> = hashMapOf()

    internal var dynamicTextPaint: HashMap<String, TextPaint> = hashMapOf()

    internal var dynamicStaticLayoutText: HashMap<String, StaticLayout> = hashMapOf()

    internal var dynamicBoringLayoutText: HashMap<String, BoringLayout> = hashMapOf()

    internal var dynamicDrawer: HashMap<String, (canvas: Canvas, frameIndex: Int) -> Boolean> = hashMapOf()

    //点击事件回调map
    internal var mClickMap : HashMap<String, IntArray> = hashMapOf()
    internal var dynamicIClickArea: HashMap<String, IClickAreaListener> = hashMapOf()

    internal var dynamicDrawerSized: HashMap<String, (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean> = hashMapOf()


    internal var isTextDirty = false

    fun setHidden(value: Boolean, forKey: String) {
        this.dynamicHidden.put(forKey, value)
    }

    @Synchronized
    fun setDynamicImage(bitmap: Bitmap, forKey: String) {
        imageRequests.remove(forKey)?.let { cancelImage(it) }
        dynamicImage[forKey] = bitmap
        isTextDirty = true
    }

    @Synchronized
    fun setDynamicImage(url: String, forKey: String) {
        val request = ImageRequest()
        imageRequests.put(forKey, request)?.let { cancelImage(it) }
        val owner = WeakReference(this)
        try {
            request.task = SVGAParser.threadPoolExecutor.submit { downloadImage(owner, request, url, forKey) }
            if (request.cancelled) request.task?.cancel(true)
        } catch (error: Exception) {
            imageRequests.remove(forKey, request)
            request.cancel()
            LogUtils.error("SVGADynamicEntity", "Image request rejected", error)
        }
    }

    companion object {
        private val imageHandler = Handler(Looper.getMainLooper())

        private fun deliverImage(owner: WeakReference<SVGADynamicEntity>, request: ImageRequest, key: String) {
            val bitmap = request.takeBitmap() ?: return
            val entity = owner.get()
            if (entity == null || !entity.finishImage(request, key, bitmap)) bitmap.recycle()
        }

        private fun downloadImage(owner: WeakReference<SVGADynamicEntity>, request: ImageRequest,
                                  url: String, key: String) {
            var connection: HttpURLConnection? = null
            var posted = false
            try {
                if (request.cancelled || owner.get() == null) return
                val budget = owner.get()?.reserveImage(request, key) ?: return
                if (budget < 4) return
                connection = URL(url).openConnection() as HttpURLConnection
                request.connection = connection
                if (request.cancelled) return
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.connect()
                val bytes = connection.inputStream.use { SVGAIO.readBytes(it, SVGAMemoryLimits.inputBytes) }
                if (request.cancelled) return
                val bitmap = SVGABitmapByteArrayDecoder.decodeBitmapFrom(bytes, 0, 0, budget) ?: return
                val entity = owner.get()
                if (entity == null) bitmap.recycle()
                else posted = entity.postImage(owner, request, key, bitmap)
            } catch (error: Exception) {
                if (!request.cancelled) LogUtils.error("SVGADynamicEntity", "Image download failed", error)
            } catch (error: OutOfMemoryError) {
                LogUtils.error("SVGADynamicEntity", "Insufficient memory for dynamic image", error)
            } finally {
                request.connection = null
                connection?.disconnect()
                if (!posted) owner.get()?.discardImage(request, key)
            }
        }
    }

    fun setDynamicText(text: String, textPaint: TextPaint, forKey: String) {
        this.isTextDirty = true
        this.dynamicText.put(forKey, text)
        this.dynamicTextPaint.put(forKey, textPaint)
    }

    fun setDynamicText(layoutText: StaticLayout, forKey: String) {
        this.isTextDirty = true
        this.dynamicStaticLayoutText.put(forKey, layoutText)
    }

    fun setDynamicText(layoutText: BoringLayout, forKey: String) {
        this.isTextDirty = true
        BoringLayout.isBoring(layoutText.text,layoutText.paint)?.let {
            this.dynamicBoringLayoutText.put(forKey,layoutText)
        }
    }

    fun setDynamicDrawer(drawer: (canvas: Canvas, frameIndex: Int) -> Boolean, forKey: String) {
        this.dynamicDrawer.put(forKey, drawer)
    }

    fun setClickArea(clickKey: List<String>) {
        for(itemKey in clickKey){
            dynamicIClickArea.put(itemKey,object : IClickAreaListener {
                override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                    mClickMap.let {
                        if(it.get(key) == null){
                            it.put(key, intArrayOf(x0,y0,x1,y1))
                        }else{
                            it.get(key)?.let {
                                it[0] = x0
                                it[1] = y0
                                it[2] = x1
                                it[3] = y1
                            }
                        }
                    }
                }
            })
        }
    }

    fun setClickArea(clickKey: String) {
        dynamicIClickArea.put(clickKey, object : IClickAreaListener {
            override fun onResponseArea(key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
                mClickMap.let {
                    if (it.get(key) == null) {
                        it.put(key, intArrayOf(x0, y0, x1, y1))
                    } else {
                        it.get(key)?.let {
                            it[0] = x0
                            it[1] = y0
                            it[2] = x1
                            it[3] = y1
                        }
                    }
                }
            }
        })
    }

    fun setDynamicDrawerSized(drawer: (canvas: Canvas, frameIndex: Int, width: Int, height: Int) -> Boolean, forKey: String) {
        this.dynamicDrawerSized.put(forKey, drawer)
    }

    @Synchronized
    fun clearDynamicObjects() {
        imageRequests.values.forEach { cancelImage(it) }
        imageRequests.clear()
        this.isTextDirty = true
        this.dynamicHidden.clear()
        this.dynamicImage.clear()
        this.dynamicText.clear()
        this.dynamicTextPaint.clear()
        this.dynamicStaticLayoutText.clear()
        this.dynamicBoringLayoutText.clear()
        this.dynamicDrawer.clear()
        this.dynamicIClickArea.clear()
        this.mClickMap.clear()
        this.dynamicDrawerSized.clear()
    }
}
