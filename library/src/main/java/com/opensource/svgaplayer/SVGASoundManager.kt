package com.opensource.svgaplayer

/**
 * @author Devin
 *
 * Created on 2/24/21.
 */
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.FileDescriptor
import java.lang.ref.WeakReference

/**
 * Author : llk
 * Time : 2020/10/24
 * Description : svga 音频加载管理类
 * 将 SoundPool 抽取到单例里边，规避 load 资源之后不回调 onLoadComplete 的问题。
 *
 * 需要对 SVGASoundManager 进行初始化
 *
 * 相关文章：Android SoundPool 崩溃问题研究
 * https://zhuanlan.zhihu.com/p/29985198
 */
object SVGASoundManager {

    private val TAG = SVGASoundManager::class.java.simpleName

    private var soundPool: SoundPool? = null
    private var generation = 0L

    @Synchronized
    internal fun poolGeneration(): Long? = if (soundPool == null) null else generation

    private val soundCallBackMap: MutableMap<Int, WeakReference<SVGASoundCallBack>> = mutableMapOf()

    /**
     * 音量设置，范围在 [0, 1] 之间
     */
    private var volume: Float = 1f

    /**
     * 音频回调
     */
    internal interface SVGASoundCallBack {

        // 音量发生变化
        fun onVolumeChange(value: Float)

        // 音频加载完成
        fun onComplete()
    }

    fun init() {
        init(20)
    }

    @Synchronized
    fun init(maxStreams: Int) {
        LogUtils.debug(TAG, "**************** init **************** $maxStreams")
        if (soundPool != null) {
            return
        }
        val pool = getSoundPool(maxStreams)
        generation++
        soundPool = pool
        pool.setOnLoadCompleteListener { source, soundId, status ->
            val callback = synchronized(this) {
                if (soundPool !== source) return@setOnLoadCompleteListener
                val target = soundCallBackMap[soundId]?.get()
                if (target == null || status != 0) {
                    soundCallBackMap.remove(soundId)
                    source.unload(soundId)
                }
                target
            }
            // Failed loads also finish preparation; the video must not wait forever.
            callback?.onComplete()
        }
    }

    @Synchronized
    fun release() {
        soundCallBackMap.clear()
        soundPool?.setOnLoadCompleteListener(null)
        soundPool?.release()
        soundPool = null
    }

    /**
     * 根据当前播放实体，设置音量
     *
     * @param volume 范围在 [0, 1]
     * @param entity 根据需要控制对应 entity 音量大小，若为空则控制所有正在播放的音频音量
     */
    fun setVolume(volume: Float, entity: SVGAVideoEntity? = null) {
        if (!checkInit()) {
            return
        }

        if (volume < 0f || volume > 1f) {
            LogUtils.error(TAG, "The volume level is in the range of 0 to 1 ")
            return
        }

        if (entity == null) {
            val callbacks = synchronized(this) {
                this.volume = volume
                val values = mutableListOf<SVGASoundCallBack>()
                val iterator = soundCallBackMap.iterator()
                while (iterator.hasNext()) {
                    val (id, reference) = iterator.next()
                    val callback = reference.get()
                    if (callback == null) {
                        soundPool?.unload(id)
                        iterator.remove()
                    } else if (values.none { it === callback }) values.add(callback)
                }
                values
            }
            callbacks.forEach { it.onVolumeChange(volume) }
            return
        }

        synchronized(this) {
            val pool = soundPool ?: return
            if (!entity.usesSharedSoundPool) return
            entity.audioList.forEach { audio ->
                audio.playID?.let { pool.setVolume(it, volume, volume) }
            }
        }
    }

    /**
     * 是否初始化
     * 如果没有初始化，就使用原来SvgaPlayer库的音频加载逻辑。
     * @return true 则已初始化， 否则为 false
     */
    @Synchronized
    internal fun isInit(): Boolean {
        return soundPool != null
    }

    private fun checkInit(): Boolean {
        val isInit = isInit()
        if (!isInit) {
            LogUtils.error(TAG, "soundPool is null, you need call init() !!!")
        }
        return isInit
    }

    private fun getSoundPool(maxStreams: Int) = if (Build.VERSION.SDK_INT >= 21) {
        val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        SoundPool.Builder().setAudioAttributes(attributes)
                .setMaxStreams(maxStreams)
                .build()
    } else {
        SoundPool(maxStreams, AudioManager.STREAM_MUSIC, 0)
    }

    @Synchronized
    internal fun load(callBack: SVGASoundCallBack?,
                      fd: FileDescriptor?,
                      offset: Long,
                      length: Long,
                      priority: Int): Int {
        if (!checkInit()) return -1

        val stale = soundCallBackMap.iterator()
        while (stale.hasNext()) {
            val (id, reference) = stale.next()
            if (reference.get() == null) {
                soundPool?.unload(id)
                stale.remove()
            }
        }

        val soundId = soundPool!!.load(fd, offset, length, priority)

        LogUtils.debug(TAG, "load soundId=$soundId callBack=$callBack")

        if (callBack != null && soundId > 0) {
            soundCallBackMap[soundId] = WeakReference(callBack)
        }
        return soundId
    }

    @Synchronized
    internal fun unload(soundId: Int, owner: SVGASoundCallBack? = null) {
        // A released/re-created SoundPool can reuse IDs. An old video must not unload
        // a different video's sample in the new pool.
        val reference = soundCallBackMap[soundId] ?: return
        if (owner != null && reference.get() !== owner) return
        soundCallBackMap.remove(soundId)
        soundPool?.unload(soundId)
    }

    @Synchronized
    internal fun play(soundId: Int): Int {
        if (!checkInit()) return -1

        LogUtils.debug(TAG, "play soundId=$soundId")
        return soundPool!!.play(soundId, volume, volume, 1, 0, 1.0f)
    }

    @Synchronized
    internal fun stop(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "stop soundId=$soundId")
        soundPool!!.stop(soundId)
    }

    @Synchronized
    internal fun resume(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "stop soundId=$soundId")
        soundPool!!.resume(soundId)
    }

    @Synchronized
    internal fun pause(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "pause soundId=$soundId")
        soundPool!!.pause(soundId)
    }
}
