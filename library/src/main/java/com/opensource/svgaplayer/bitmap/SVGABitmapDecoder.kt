package com.opensource.svgaplayer.bitmap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.opensource.svgaplayer.utils.SVGAIO
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import java.io.IOException

/**
 * Bitmap 解码器
 *
 * <T> 需要加载的数据类型
 *
 * Create by im_dsd 2020/7/7 17:39
 */
internal abstract class SVGABitmapDecoder<T> {

    fun decodeBitmapFrom(data: T, reqWidth: Int, reqHeight: Int,
                         maxBytes: Long = SVGAMemoryLimits.bitmapBytes): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        onDecode(data, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val largestDimension = maxOf(options.outWidth, options.outHeight)
        options.inSampleSize = BitmapSampleSizeCalculator.calculate(options, reqWidth, reqHeight, maxBytes)
        options.inJustDecodeBounds = false
        while (true) {
            SVGAIO.checkInterrupted()
            try {
                val bitmap = onDecode(data, options) ?: return null
                if (bitmap.allocationByteCount.toLong() <= maxBytes) return bitmap
                // This bitmap has never been published to a View or a caller.
                bitmap.recycle()
            } catch (error: OutOfMemoryError) {
                if (options.inSampleSize >= largestDimension) {
                    throw IOException("Unable to allocate SVGA bitmap", error)
                }
            }
            if (options.inSampleSize >= largestDimension || options.inSampleSize > Int.MAX_VALUE / 2) {
                throw IOException("Decoded bitmap exceeds its memory budget")
            }
            options.inSampleSize *= 2
        }
    }

    abstract fun onDecode(data: T, ops: BitmapFactory.Options): Bitmap?
}
