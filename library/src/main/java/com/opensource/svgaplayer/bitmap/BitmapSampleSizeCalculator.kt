package com.opensource.svgaplayer.bitmap

import android.graphics.BitmapFactory
import com.opensource.svgaplayer.utils.SVGAMemoryLimits

/**
 *
 * Create by im_dsd 2020/7/7 17:59
 */
internal object BitmapSampleSizeCalculator {

    fun calculate(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int,
                  maxBytes: Long = SVGAMemoryLimits.bitmapBytes): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        require(maxBytes >= 4) { "Insufficient bitmap memory budget" }
        if (reqWidth > 0 && reqHeight > 0 && (height > reqHeight || width > reqWidth)) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        // Use Long and rounded-up dimensions: panoramic images and missing target sizes
        // must obey the same limit as square images. ARGB_8888 preserves transparent PNGs.
        while (true) {
            val decodedWidth = (width.toLong() + inSampleSize - 1) / inSampleSize
            val decodedHeight = (height.toLong() + inSampleSize - 1) / inSampleSize
            if (decodedWidth <= SVGAMemoryLimits.MAX_BITMAP_DIMENSION &&
                    decodedHeight <= SVGAMemoryLimits.MAX_BITMAP_DIMENSION &&
                    decodedWidth * decodedHeight <= maxBytes / 4) break
            require(inSampleSize <= Int.MAX_VALUE / 2) { "Bitmap dimensions exceed sampling limits" }
            inSampleSize *= 2
        }

        return inSampleSize
    }
}
