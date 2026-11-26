package com.opensource.svgaplayer.drawer

import android.graphics.*
import android.os.Build
import android.text.StaticLayout
import android.text.TextUtils
import android.widget.ImageView
import com.opensource.svgaplayer.bitmap.BitmapSampleSizeCalculator
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.entities.SVGAVideoShapeEntity

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal class SVGACanvasDrawer(videoItem: SVGAVideoEntity, val dynamicItem: SVGADynamicEntity) : SGVADrawer(videoItem) {

    private val sharedValues = ShareValues()
    private val drawTextCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var textCacheBytes = 0L
    private val pathCache = PathCache()

    fun clear() {
        clearTextCache()
        pathCache.clear()
        sharedValues.clear()
        clearSpritePool()
    }

    override fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        super.drawFrame(canvas, frameIndex, scaleType)
        pathCache.onSizeChanged(canvas)
        val sprites = requestFrameSprites(frameIndex)
        try {
            val mattes = sprites.filter { it.imageKey?.endsWith(".matte") == true }
                    .associateBy { it.imageKey }
            var index = 0
            while (index < sprites.size) {
                val sprite = sprites[index]
                if (sprite.imageKey?.endsWith(".matte") == true) {
                    index++
                    continue
                }
                val matte = mattes[sprite.matteKey]
                if (matte == null) {
                    drawSprite(sprite, canvas, frameIndex)
                    index++
                    continue
                }
                var end = index + 1
                while (end < sprites.size && sprites[end].matteKey == sprite.matteKey &&
                        sprites[end].imageKey?.endsWith(".matte") != true) end++
                drawMatteGroup(canvas, sprites.subList(index, end), matte, frameIndex)
                index = end
            }
        } finally {
            releaseFrameSprites(sprites)
        }
    }

    private fun drawMatteGroup(canvas: Canvas, sprites: List<SVGADrawerSprite>,
                               matte: SVGADrawerSprite, frameIndex: Int) {
        // Use the visible local bounds. Neither the mask nor its color layer may allocate
        // a full-size unbounded saveLayer on the destination canvas.
        val bounds = canvas.clipBounds
        if (bounds.isEmpty) return
        val layer = sharedValues.shareLayerCanvas(bounds.width(), bounds.height())
        val mask = sharedValues.shareMatteCanvas(bounds.width(), bounds.height())
        layer.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        mask.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
        sprites.forEach { drawSprite(it, layer, frameIndex) }
        drawSprite(matte, mask, frameIndex)
        layer.setMatrix(null)
        val maskBitmap = sharedValues.sharedMatteBitmap()
        val mattePaint = sharedValues.shareMattePaint().apply {
            // ALPHA_8 drawBitmap is treated as coverage on software canvases. A full
            // rectangle with an alpha shader also blends transparent pixels with DST_IN.
            shader = BitmapShader(maskBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        layer.drawRect(0f, 0f, maskBitmap.width.toFloat(), maskBitmap.height.toFloat(), mattePaint)
        mattePaint.shader = null
        val paint = sharedValues.sharedPaint().apply { isFilterBitmap = true }
        canvas.drawBitmap(sharedValues.sharedLayerBitmap(), null, bounds, paint)
    }

    private fun shareFrameMatrix(transform: Matrix): Matrix {
        val matrix = this.sharedValues.sharedMatrix()
        matrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy)
        matrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
        matrix.preConcat(transform)
        return matrix
    }

    private fun drawSprite(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        drawImage(sprite, canvas)
        drawShape(sprite, canvas)
        drawDynamic(sprite, canvas, frameIndex)
    }

    private fun drawImage(sprite: SVGADrawerSprite, canvas: Canvas) {
        val imageKey = sprite.imageKey ?: return
        val isHidden = dynamicItem.dynamicHidden[imageKey] == true
        if (isHidden) {
            return
        }
        val bitmapKey = if (imageKey.endsWith(".matte")) imageKey.substring(0, imageKey.length - 6) else imageKey
        val drawingBitmap = (dynamicItem.dynamicImage[bitmapKey] ?: videoItem.imageMap[bitmapKey])
                ?: return
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        val paint = this.sharedValues.sharedPaint()
        paint.isAntiAlias = videoItem.antiAlias
        paint.isFilterBitmap = videoItem.antiAlias
        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
        if (sprite.frameEntity.maskPath != null) {
            val maskPath = sprite.frameEntity.maskPath ?: return
            canvas.save()
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            path.transform(frameMatrix)
            canvas.clipPath(path)
            frameMatrix.preScale((sprite.frameEntity.layout.width / drawingBitmap.width).toFloat(), (sprite.frameEntity.layout.height / drawingBitmap.height).toFloat())
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
            canvas.restore()
        } else {
            frameMatrix.preScale((sprite.frameEntity.layout.width / drawingBitmap.width).toFloat(), (sprite.frameEntity.layout.height / drawingBitmap.height).toFloat())
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
        }
        dynamicItem.dynamicIClickArea.let {
            it.get(imageKey)?.let { listener ->
                val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                frameMatrix.getValues(matrixArray)
                listener.onResponseArea(imageKey, matrixArray[2].toInt()
                        , matrixArray[5].toInt()
                        , (drawingBitmap.width * matrixArray[0] + matrixArray[2]).toInt()
                        , (drawingBitmap.height * matrixArray[4] + matrixArray[5]).toInt())
            }
        }
        drawTextOnBitmap(canvas, drawingBitmap, sprite, frameMatrix)
    }

    private fun clearTextCache() {
        drawTextCache.clear()
        textCacheBytes = 0
    }

    private fun cacheTextBitmap(key: String, bitmap: Bitmap) {
        drawTextCache.remove(key)?.let { textCacheBytes -= it.allocationByteCount }
        val iterator = drawTextCache.iterator()
        while (textCacheBytes + bitmap.allocationByteCount > SVGAMemoryLimits.bitmapBytes && iterator.hasNext()) {
            textCacheBytes -= iterator.next().value.allocationByteCount
            iterator.remove()
        }
        drawTextCache[key] = bitmap
        textCacheBytes += bitmap.allocationByteCount
    }

    private fun createTextBitmap(source: Bitmap): Bitmap? {
        val options = BitmapFactory.Options().apply { outWidth = source.width; outHeight = source.height }
        var sample = BitmapSampleSizeCalculator.calculate(options, 0, 0)
        while (true) {
            val width = ((source.width.toLong() + sample - 1) / sample).toInt().coerceAtLeast(1)
            val height = ((source.height.toLong() + sample - 1) / sample).toInt().coerceAtLeast(1)
            try {
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (error: OutOfMemoryError) {
                if (width == 1 && height == 1) return null
                sample *= 2
            }
        }
    }

    private fun drawTextOnBitmap(canvas: Canvas, drawingBitmap: Bitmap, sprite: SVGADrawerSprite, frameMatrix: Matrix) {
        if (dynamicItem.isTextDirty) {
            clearTextCache()
            dynamicItem.isTextDirty = false
        }
        val imageKey = sprite.imageKey ?: return
        var textBitmap: Bitmap? = null
        dynamicItem.dynamicText[imageKey]?.let { drawingText ->
            dynamicItem.dynamicTextPaint[imageKey]?.let { drawingTextPaint ->
                drawTextCache[imageKey]?.let {
                    textBitmap = it
                } ?: kotlin.run {
                    val bitmap = createTextBitmap(drawingBitmap) ?: return
                    textBitmap = bitmap
                    val drawRect = Rect(0, 0, drawingBitmap.width, drawingBitmap.height)
                    val textCanvas = Canvas(bitmap).apply { scale(bitmap.width.toFloat() / drawingBitmap.width, bitmap.height.toFloat() / drawingBitmap.height) }
                    drawingTextPaint.isAntiAlias = true
                    val fontMetrics = drawingTextPaint.getFontMetrics();
                    val top = fontMetrics.top
                    val bottom = fontMetrics.bottom
                    val baseLineY = drawRect.centerY() - top / 2 - bottom / 2
                    textCanvas.drawText(drawingText, drawRect.centerX().toFloat(), baseLineY, drawingTextPaint);
                    cacheTextBitmap(imageKey, bitmap)
                }
            }
        }

        dynamicItem.dynamicBoringLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true

                val bitmap = createTextBitmap(drawingBitmap) ?: return
                textBitmap = bitmap
                val textCanvas = Canvas(bitmap).apply { scale(bitmap.width.toFloat() / drawingBitmap.width, bitmap.height.toFloat() / drawingBitmap.height) }
                textCanvas.translate(0f, ((drawingBitmap.height - it.height) / 2).toFloat())
                it.draw(textCanvas)
                cacheTextBitmap(imageKey, bitmap)
            }
        }

        dynamicItem.dynamicStaticLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                var layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    var lineMax = try {
                        val field = StaticLayout::class.java.getDeclaredField("mMaximumVisibleLineCount")
                        field.isAccessible = true
                        field.getInt(it)
                    } catch (e: Exception) {
                        Int.MAX_VALUE
                    }
                    StaticLayout.Builder
                            .obtain(it.text, 0, it.text.length, it.paint, drawingBitmap.width)
                            .setAlignment(it.alignment)
                            .setMaxLines(lineMax)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .build()
                } else {
                    StaticLayout(it.text, 0, it.text.length, it.paint, drawingBitmap.width, it.alignment, it.spacingMultiplier, it.spacingAdd, false)
                }
                val bitmap = createTextBitmap(drawingBitmap) ?: return
                textBitmap = bitmap
                val textCanvas = Canvas(bitmap).apply { scale(bitmap.width.toFloat() / drawingBitmap.width, bitmap.height.toFloat() / drawingBitmap.height) }
                textCanvas.translate(0f, ((drawingBitmap.height - layout.height) / 2).toFloat())
                layout.draw(textCanvas)
                cacheTextBitmap(imageKey, bitmap)
            }
        }
        textBitmap?.let { textBitmap ->
            val paint = this.sharedValues.sharedPaint()
            paint.isAntiAlias = videoItem.antiAlias
            paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
            if (sprite.frameEntity.maskPath != null) {
                val maskPath = sprite.frameEntity.maskPath ?: return@let
                canvas.save()
                canvas.concat(frameMatrix)
                canvas.clipRect(0, 0, drawingBitmap.width, drawingBitmap.height)
                val bitmapShader = BitmapShader(textBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                bitmapShader.setLocalMatrix(Matrix().apply {
                    setScale(drawingBitmap.width.toFloat() / textBitmap.width,
                            drawingBitmap.height.toFloat() / textBitmap.height)
                })
                paint.shader = bitmapShader
                val path = this.sharedValues.sharedPath()
                maskPath.buildPath(path)
                canvas.drawPath(path, paint)
                canvas.restore()
            } else {
                paint.isFilterBitmap = videoItem.antiAlias
                val textMatrix = Matrix(frameMatrix).apply {
                    preScale(drawingBitmap.width.toFloat() / textBitmap.width,
                            drawingBitmap.height.toFloat() / textBitmap.height)
                }
                canvas.drawBitmap(textBitmap, textMatrix, paint)
            }
        }
    }

    private fun drawShape(sprite: SVGADrawerSprite, canvas: Canvas) {
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
        sprite.frameEntity.shapes.forEach { shape ->
            shape.buildPath()
            shape.shapePath?.let {
                val paint = this.sharedValues.sharedPaint()
                paint.reset()
                paint.isAntiAlias = videoItem.antiAlias
                paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
                val path = this.sharedValues.sharedPath()
                path.reset()
                path.addPath(this.pathCache.buildPath(shape))
                val shapeMatrix = this.sharedValues.sharedMatrix2()
                shapeMatrix.reset()
                shape.transform?.let {
                    shapeMatrix.postConcat(it)
                }
                shapeMatrix.postConcat(frameMatrix)
                path.transform(shapeMatrix)
                shape.styles?.fill?.let {
                    if (it != 0x00000000) {
                        paint.style = Paint.Style.FILL
                        paint.color = it
                        val alpha = Math.min(255, Math.max(0, (sprite.frameEntity.alpha * 255).toInt()))
                        if (alpha != 255) {
                            paint.alpha = alpha
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
                shape.styles?.strokeWidth?.let {
                    if (it > 0) {
                        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
                        paint.style = Paint.Style.STROKE
                        shape.styles?.stroke?.let {
                            paint.color = it
                            val alpha = Math.min(255, Math.max(0, (sprite.frameEntity.alpha * 255).toInt()))
                            if (alpha != 255) {
                                paint.alpha = alpha
                            }
                        }
                        val scale = matrixScale(frameMatrix)
                        shape.styles?.strokeWidth?.let {
                            paint.strokeWidth = it * scale
                        }
                        shape.styles?.lineCap?.let {
                            when {
                                it.equals("butt", true) -> paint.strokeCap = Paint.Cap.BUTT
                                it.equals("round", true) -> paint.strokeCap = Paint.Cap.ROUND
                                it.equals("square", true) -> paint.strokeCap = Paint.Cap.SQUARE
                            }
                        }
                        shape.styles?.lineJoin?.let {
                            when {
                                it.equals("miter", true) -> paint.strokeJoin = Paint.Join.MITER
                                it.equals("round", true) -> paint.strokeJoin = Paint.Join.ROUND
                                it.equals("bevel", true) -> paint.strokeJoin = Paint.Join.BEVEL
                            }
                        }
                        shape.styles?.miterLimit?.let {
                            paint.strokeMiter = it.toFloat() * scale
                        }
                        shape.styles?.lineDash?.let {
                            if (it.size == 3 && (it[0] > 0 || it[1] > 0)) {
                                paint.pathEffect = DashPathEffect(floatArrayOf(
                                        (if (it[0] < 1.0f) 1.0f else it[0]) * scale,
                                        (if (it[1] < 0.1f) 0.1f else it[1]) * scale
                                ), it[2] * scale)
                            }
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
            }

        }
    }

    private val matrixScaleTempValues = FloatArray(16)

    private fun matrixScale(matrix: Matrix): Float {
        matrix.getValues(matrixScaleTempValues)
        if (matrixScaleTempValues[0] == 0f) {
            return 0f
        }
        var A = matrixScaleTempValues[0].toDouble()
        var B = matrixScaleTempValues[3].toDouble()
        var C = matrixScaleTempValues[1].toDouble()
        var D = matrixScaleTempValues[4].toDouble()
        if (A * D == B * C) return 0f
        var scaleX = Math.sqrt(A * A + B * B)
        A /= scaleX
        B /= scaleX
        var skew = A * C + B * D
        C -= A * skew
        D -= B * skew
        var scaleY = Math.sqrt(C * C + D * D)
        C /= scaleY
        D /= scaleY
        skew /= scaleY
        if (A * D < B * C) {
            scaleX = -scaleX
        }
        return if (scaleInfo.ratioX) Math.abs(scaleX.toFloat()) else Math.abs(scaleY.toFloat())
    }

    private fun drawDynamic(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        val imageKey = sprite.imageKey ?: return
        dynamicItem.dynamicDrawer[imageKey]?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(canvas, frameIndex)
            canvas.restore()
        }
        dynamicItem.dynamicDrawerSized[imageKey]?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.transform)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(canvas, frameIndex, sprite.frameEntity.layout.width.toInt(), sprite.frameEntity.layout.height.toInt())
            canvas.restore()
        }
    }

    class ShareValues {

        private val sharedPaint = Paint()
        private val sharedPath = Path()
        private val sharedPath2 = Path()
        private val sharedMatrix = Matrix()
        private val sharedMatrix2 = Matrix()

        private val shareMattePaint = Paint()
        private var shareMatteCanvas: Canvas? = null
        private var sharedMatteBitmap: Bitmap? = null
        private var shareLayerCanvas: Canvas? = null
        private var sharedLayerBitmap: Bitmap? = null
        private var bufferWidth = 0
        private var bufferHeight = 0

        fun sharedPaint(): Paint {
            sharedPaint.reset()
            return sharedPaint
        }

        fun sharedPath(): Path {
            sharedPath.reset()
            return sharedPath
        }

        fun sharedPath2(): Path {
            sharedPath2.reset()
            return sharedPath2
        }

        fun sharedMatrix(): Matrix {
            sharedMatrix.reset()
            return sharedMatrix
        }

        fun sharedMatrix2(): Matrix {
            sharedMatrix2.reset()
            return sharedMatrix2
        }

        fun shareMattePaint(): Paint {
            shareMattePaint.reset()
            shareMattePaint.color = Color.WHITE
            shareMattePaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_IN))
            return shareMattePaint
        }

        fun sharedMatteBitmap(): Bitmap = requireNotNull(sharedMatteBitmap)

        fun sharedLayerBitmap(): Bitmap = requireNotNull(sharedLayerBitmap)

        fun shareMatteCanvas(width: Int, height: Int): Canvas {
            prepareBuffers(width, height)
            return resetCanvas(requireNotNull(shareMatteCanvas), width, height)
        }

        fun shareLayerCanvas(width: Int, height: Int): Canvas {
            prepareBuffers(width, height)
            return resetCanvas(requireNotNull(shareLayerCanvas), width, height)
        }

        private fun resetCanvas(canvas: Canvas, width: Int, height: Int): Canvas {
            canvas.setMatrix(null)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(requireNotNull(sharedMatteBitmap).width.toFloat() / width,
                    requireNotNull(sharedMatteBitmap).height.toFloat() / height)
            return canvas
        }

        private fun prepareBuffers(width: Int, height: Int) {
            require(width > 0 && height > 0) { "Invalid SVGA matte bounds" }
            if (bufferWidth == width && bufferHeight == height &&
                    sharedMatteBitmap?.isRecycled == false && sharedLayerBitmap?.isRecycled == false) return
            val options = BitmapFactory.Options().apply { outWidth = width; outHeight = height }
            // Account for both buffers together: 4 color bytes and 1 alpha byte per pixel.
            var sample = BitmapSampleSizeCalculator.calculate(options, 0, 0,
                    SVGAMemoryLimits.matteBytes / 5 * 4)
            while (true) {
                val sampledWidth = ((width.toLong() + sample - 1) / sample).toInt().coerceAtLeast(1)
                val sampledHeight = ((height.toLong() + sample - 1) / sample).toInt().coerceAtLeast(1)
                val previousMask = sharedMatteBitmap
                val previousLayer = sharedLayerBitmap
                if (previousMask != null && previousLayer != null &&
                        !previousMask.isRecycled && !previousLayer.isRecycled &&
                        previousMask.width == sampledWidth && previousMask.height == sampledHeight) {
                    bufferWidth = width
                    bufferHeight = height
                    return
                }
                clearBuffers()
                var mask: Bitmap? = null
                var layer: Bitmap? = null
                try {
                    mask = Bitmap.createBitmap(sampledWidth, sampledHeight, Bitmap.Config.ALPHA_8)
                    layer = Bitmap.createBitmap(sampledWidth, sampledHeight, Bitmap.Config.ARGB_8888)
                    if (mask.allocationByteCount.toLong() + layer.allocationByteCount <= SVGAMemoryLimits.matteBytes) {
                        val maskCanvas = Canvas(mask)
                        val layerCanvas = Canvas(layer)
                        sharedMatteBitmap = mask
                        sharedLayerBitmap = layer
                        shareMatteCanvas = maskCanvas
                        shareLayerCanvas = layerCanvas
                        bufferWidth = width
                        bufferHeight = height
                        return
                    }
                } catch (error: OutOfMemoryError) {
                    if (sampledWidth == 1 && sampledHeight == 1) throw error
                } finally {
                    // Only unpublished failed allocations may be recycled explicitly.
                    if (sharedMatteBitmap !== mask) mask?.recycle()
                    if (sharedLayerBitmap !== layer) layer?.recycle()
                }
                check(sample <= Int.MAX_VALUE / 2) { "SVGA matte exceeds sampling limits" }
                sample *= 2
            }
        }

        private fun clearBuffers() {
            shareMatteCanvas?.setBitmap(null)
            shareLayerCanvas?.setBitmap(null)
            shareMatteCanvas = null
            shareLayerCanvas = null
            sharedMatteBitmap = null
            sharedLayerBitmap = null
            bufferWidth = 0
            bufferHeight = 0
        }

        fun clear() {
            clearBuffers()
            sharedPaint.reset()
            shareMattePaint.reset()
            sharedPath.rewind()
            sharedPath2.rewind()
        }
    }

    class PathCache {

        private var canvasWidth: Int = 0
        private var canvasHeight: Int = 0
        private val cache = HashMap<SVGAVideoShapeEntity, Path>()

        fun clear() {
            cache.clear()
        }

        fun onSizeChanged(canvas: Canvas) {
            if (this.canvasWidth != canvas.width || this.canvasHeight != canvas.height) {
                this.cache.clear()
            }
            this.canvasWidth = canvas.width
            this.canvasHeight = canvas.height
        }

        fun buildPath(shape: SVGAVideoShapeEntity): Path {
            if (!this.cache.containsKey(shape)) {
                val path = Path()
                shape.shapePath?.let { path.set(it) }
                this.cache[shape] = path
            }
            return this.cache[shape]!!
        }

    }

}
