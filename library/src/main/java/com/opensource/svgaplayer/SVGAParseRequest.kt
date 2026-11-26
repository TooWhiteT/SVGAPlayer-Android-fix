package com.opensource.svgaplayer

import android.os.Handler
import android.os.Looper
import com.opensource.svgaplayer.utils.SVGAIO
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Semaphore

/**
 * 一个解析请求的所有权边界：额度覆盖解析、音频准备及主线程待交付阶段。
 * 主线程消息只引用请求；取消时断开视频和业务回调，并移除消息，避免大对象继续排队。
 * 已进入成功回调的视频归调用方所有，后续取消不会将其回收。
 */
internal class SVGAParseRequest(callback: SVGAParser.ParseCompletion?, private val alias: String?) {
    companion object {
        private val slots = Semaphore(SVGAMemoryLimits.MAX_PENDING_VIDEOS, true)
    }

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var callback = callback
    private var video: SVGAVideoEntity? = null
    private var delivery: Runnable? = null
    private var cancelSource: (() -> Unit)? = null
    private var workerActive = false
    private var hasSlot = false
    private var cleaning = false
    private var finished = false
    private var cancelled = false

    /** 在读取或分配素材前申请额度；额度不足时后台等待，主线程则立即失败，避免死锁。 */
    fun begin() {
        synchronized(lock) {
            if (finished) throw InterruptedIOException("SVGA request cancelled")
            workerActive = true
        }
        SVGAIO.checkInterrupted()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!slots.tryAcquire()) throw IOException("SVGA delivery queue is busy")
        } else {
            slots.acquire()
        }
        synchronized(lock) {
            hasSlot = true
            if (finished) throw InterruptedIOException("SVGA request cancelled")
        }
    }

    fun workerFinished() {
        synchronized(lock) { workerActive = false }
        releaseSlotIfFinished()
    }

    fun setSourceCancellation(action: () -> Unit) {
        val cancelNow = synchronized(lock) {
            if (!finished) cancelSource = action
            cancelled
        }
        if (cancelNow) action()
    }

    /** 构造与取消发生竞争时，不允许将取消后才完成的实体挂入请求。 */
    fun attach(video: SVGAVideoEntity): Boolean {
        val accepted = synchronized(lock) {
            if (finished) false else { this.video = video; true }
        }
        if (!accepted) video.clear()
        return accepted
    }

    fun hasReceiver(): Boolean = synchronized(lock) { callback != null && !finished }

    fun ready() {
        var discard = false
        var postFailed = false
        synchronized(lock) {
            if (finished || delivery != null) return
            if (callback == null) {
                discard = true
            } else {
                val message = Runnable { dispatch() }
                delivery = message
                // 在同一把锁内登记并入队，确保 cancel() 不会漏掉刚入队的消息。
                postFailed = !handler.post(message)
            }
        }
        if (discard) discardWithoutReceiver()
        if (postFailed) cancel()
    }

    private fun dispatch() {
        val result = synchronized(lock) {
            if (finished) return
            finished = true
            val result = video to callback
            video = null
            callback = null
            delivery = null
            cancelSource = null
            result
        }
        try {
            val item = result.first ?: return
            val receiver = result.second
            if (receiver == null) item.clear() else receiver.onComplete(item)
        } finally {
            releaseSlotIfFinished()
        }
    }

    private fun discardWithoutReceiver() {
        val item = synchronized(lock) {
            if (finished) return
            finished = true
            val item = video
            video = null
            callback = null
            cancelSource = null
            cleaning = item != null
            item
        }
        clearVideo(item)
    }

    fun fail(error: Exception) {
        val item = synchronized(lock) {
            if (finished) return
            finished = true
            LogUtils.error("SVGAParser", "SVGA parse failed: $alias", error)
            val item = video
            video = null
            cleaning = item != null
            delivery?.let { handler.removeCallbacks(it) }
            cancelSource = null
            val message = Runnable {
                val receiver = synchronized(lock) {
                    delivery = null
                    val receiver = callback
                    callback = null
                    receiver
                }
                receiver?.onError()
            }
            delivery = message
            if (callback == null || !handler.post(message)) {
                callback = null
                delivery = null
            }
            item
        }
        clearVideo(item)
    }

    fun cancel() {
        val resources = synchronized(lock) {
            cancelled = true
            finished = true
            callback = null
            delivery?.let { handler.removeCallbacks(it) }
            delivery = null
            val resources = video to cancelSource
            video = null
            cancelSource = null
            if (resources.first != null) cleaning = true
            resources
        }
        try {
            resources.second?.invoke()
        } finally {
            clearVideo(resources.first)
        }
    }

    private fun clearVideo(item: SVGAVideoEntity?) {
        try {
            // 不持有请求锁调用 video.clear()，避免与音频回调里的 video 锁形成锁反转。
            item?.clear()
        } finally {
            if (item != null) synchronized(lock) { cleaning = false }
            releaseSlotIfFinished()
        }
    }

    private fun releaseSlotIfFinished() {
        val release = synchronized(lock) {
            // 解析中的取消须等工作线程退出；清理中的取消须等资源断开，才允许新分配。
            if (finished && hasSlot && !workerActive && !cleaning) {
                hasSlot = false
                true
            } else false
        }
        if (release) slots.release()
    }
}
