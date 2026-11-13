package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import com.opensource.svgaplayer.utils.log.LogUtils

class SVGADrawable(val videoItem: SVGAVideoEntity, val dynamicItem: SVGADynamicEntity): Drawable() {

    constructor(videoItem: SVGAVideoEntity): this(videoItem, SVGADynamicEntity())

    var cleared = true
        internal set (value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var currentFrame = 0
        internal set (value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    private val drawer = SVGACanvasDrawer(videoItem, dynamicItem)
    private var released = false

    override fun draw(canvas: Canvas) {
        if (cleared) {
            return
        }
        val saveCount = canvas.save()
        try {
            drawer.drawFrame(canvas, currentFrame, scaleType)
        } catch (error: OutOfMemoryError) {
            clear()
            LogUtils.error("SVGADrawable", "Insufficient memory to render SVGA", error)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun setAlpha(alpha: Int) {

    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {

    }

    fun resume() {
        drawer.audioEnabled = true
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                if (videoItem.usesSharedSoundPool){
                    SVGASoundManager.resume(it)
                }else{
                    videoItem.soundPool?.resume(it)
                }
            }
        }
    }

    fun pause() {
        drawer.audioEnabled = false
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                if (videoItem.usesSharedSoundPool){
                    SVGASoundManager.pause(it)
                }else{
                    videoItem.soundPool?.pause(it)
                }
            }
        }
    }

    fun stop() {
        drawer.audioEnabled = false
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                if (videoItem.usesSharedSoundPool){
                    SVGASoundManager.stop(it)
                }else{
                    videoItem.soundPool?.stop(it)
                }
            }
            audio.playID = null
        }
    }

    fun clear() {
        clear(releaseVideo = true, releaseDynamic = true)
    }

    internal fun clear(releaseVideo: Boolean, releaseDynamic: Boolean) {
        if (released) return
        released = true
        cleared = true
        videoItem.audioList.forEach { audio ->
            audio.playID?.let {
                if (videoItem.usesSharedSoundPool){
                    SVGASoundManager.stop(it)
                }else{
                    videoItem.soundPool?.stop(it)
                }
            }
            audio.playID = null
        }
        drawer.clear()
        if (releaseDynamic) dynamicItem.clearDynamicObjects()
        if (releaseVideo) videoItem.clear()
    }
}
