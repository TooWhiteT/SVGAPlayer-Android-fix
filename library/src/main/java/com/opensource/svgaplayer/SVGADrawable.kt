package com.opensource.svgaplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import com.opensource.svgaplayer.entities.SVGAAudioEntity
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
            audioFramePending = true
            invalidateSelf()
        }

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    private val drawer = SVGACanvasDrawer(videoItem, dynamicItem)
    private var audioEnabled = true
    private var audioFramePending = true
    private var released = false

    // Rebinding a wrapper transfers ownership back to it, including any shared entities.
    internal fun onBoundToView() {
        released = false
    }

    override fun draw(canvas: Canvas) {
        if (cleared) {
            return
        }
        val saveCount = canvas.save()
        try {
            if (audioEnabled && audioFramePending) {
                audioFramePending = false
                updateAudio()
            }
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

    private fun updateAudio() {
        videoItem.audioList.forEach { audio ->
            if (audio.startFrame == currentFrame) {
                audio.soundID?.let { soundID ->
                    stopStream(audio)
                    audio.playID = if (videoItem.usesSharedSoundPool) SVGASoundManager.play(soundID)
                    else videoItem.soundPool?.play(soundID, 1f, 1f, 1, 0, 1f)
                }
            }
            if (audio.endFrame <= currentFrame) stopStream(audio)
        }
    }

    private fun stopStream(audio: SVGAAudioEntity) {
        val streamID = audio.playID ?: return
        if (videoItem.usesSharedSoundPool) SVGASoundManager.stop(streamID)
        else videoItem.soundPool?.stop(streamID)
        audio.playID = null
    }

    fun resume() {
        audioEnabled = true
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
        audioEnabled = false
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
        audioEnabled = false
        resetAudio()
    }

    internal fun onAnimationRepeat() {
        resetAudio()
        // A one-frame range repeats without changing currentFrame.
        invalidateSelf()
    }

    private fun resetAudio() {
        videoItem.audioList.forEach(::stopStream)
        audioFramePending = true
    }

    fun clear() {
        clear(releaseVideo = true, releaseDynamic = true)
    }

    internal fun clear(releaseVideo: Boolean, releaseDynamic: Boolean) {
        if (released) return
        released = true
        cleared = true
        stop()
        drawer.clear()
        if (releaseDynamic) dynamicItem.clearDynamicObjects()
        if (releaseVideo) videoItem.clear()
    }
}
