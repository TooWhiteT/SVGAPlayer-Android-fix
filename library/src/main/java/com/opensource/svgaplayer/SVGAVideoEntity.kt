package com.opensource.svgaplayer

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import com.opensource.svgaplayer.bitmap.SVGABitmapByteArrayDecoder
import com.opensource.svgaplayer.bitmap.SVGABitmapFileDecoder
import com.opensource.svgaplayer.entities.SVGAAudioEntity
import com.opensource.svgaplayer.entities.SVGAVideoSpriteEntity
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.proto.MovieParams
import com.opensource.svgaplayer.utils.SVGARect
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import com.opensource.svgaplayer.utils.SVGAIO
import com.opensource.svgaplayer.utils.SVGADecodeBudget
import com.opensource.svgaplayer.utils.SVGAJsonDecoder
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import java.io.IOException

/**
 * Created by PonyCui on 16/6/18.
 */
class SVGAVideoEntity {

    var antiAlias = true
    var movieItem: MovieEntity? = null

    var videoSize = SVGARect(0.0, 0.0, 0.0, 0.0)
        private set

    var FPS = 15
        private set

    var frames: Int = 0
        private set

    internal var spriteList: List<SVGAVideoSpriteEntity> = emptyList()
    internal var audioList: List<SVGAAudioEntity> = emptyList()
    internal var soundPool: SoundPool? = null
    private var sharedPoolGeneration: Long? = null
    internal val usesSharedSoundPool: Boolean
        get() = sharedPoolGeneration != null && sharedPoolGeneration == SVGASoundManager.poolGeneration()
    private var soundCallback: SVGASoundManager.SVGASoundCallBack? = null
    internal var imageMap = HashMap<String, Bitmap>()
    private var mCacheDir: File
    private var mFrameHeight = 0
    private var mFrameWidth = 0
    private var imageBytes = 0L
    @Volatile internal var isCleared = false
        private set
    private var mCallback: (() -> Unit)? = null
    private val preparationHandler = Handler(Looper.getMainLooper())
    // Keep the video alive while the native audio load is pending, with a bounded lifetime.
    private val preparationTimeout = Runnable { completePreparation() }

    constructor(json: JSONObject, cacheDir: File) : this(json, cacheDir, 0, 0)

    constructor(json: JSONObject, cacheDir: File, frameWidth: Int, frameHeight: Int) {
        mFrameWidth = frameWidth
        mFrameHeight = frameHeight
        mCacheDir = cacheDir
        SVGAJsonDecoder.validate(json)
        val movieJsonObject = json.optJSONObject("movie") ?: return
        setupByJson(movieJsonObject)
        try {
            parserImages(json)
            resetSprites(json)
        } catch (error: Throwable) {
            clear()
            throw error
        }
    }

    private fun setupByJson(movieObject: JSONObject) {
        movieObject.optJSONObject("viewBox")?.let { viewBoxObject ->
            val width = viewBoxObject.optDouble("width", 0.0)
            val height = viewBoxObject.optDouble("height", 0.0)
            videoSize = SVGARect(0.0, 0.0, width, height)
        }
        FPS = movieObject.optInt("fps", 20)
        frames = movieObject.optInt("frames", 0)
    }

    constructor(entity: MovieEntity, cacheDir: File) : this(entity, cacheDir, 0, 0)

    constructor(entity: MovieEntity, cacheDir: File, frameWidth: Int, frameHeight: Int) {
        this.mFrameWidth = frameWidth
        this.mFrameHeight = frameHeight
        this.mCacheDir = cacheDir
        this.movieItem = entity
        entity.params?.let(this::setupByMovie)
        try {
            validateStructure(entity)
            parserImages(entity)
            resetSprites(entity)
        } catch (error: Throwable) {
            clear()
            throw error
        }
    }

    private fun setupByMovie(movieParams: MovieParams) {
        val width = (movieParams.viewBoxWidth ?: 0.0f).toDouble()
        val height = (movieParams.viewBoxHeight ?: 0.0f).toDouble()
        videoSize = SVGARect(0.0, 0.0, width, height)
        FPS = movieParams.fps ?: 20
        frames = movieParams.frames ?: 0
    }

    @Synchronized
    internal fun prepare(callback: () -> Unit, playCallback: SVGAParser.PlayCallback?) {
        if (isCleared) return
        mCallback = callback
        preparationHandler.postDelayed(preparationTimeout, 30_000)
        val entity = movieItem
        // Decoded sprites and bitmaps are sufficient for playback. Do not retain PNG bytes.
        movieItem = null
        try {
            if (entity == null) completePreparation() else setupAudios(entity, playCallback)
        } catch (error: Throwable) {
            clear()
            throw error
        }
    }

    @Synchronized
    private fun completePreparation() {
        preparationHandler.removeCallbacks(preparationTimeout)
        val callback = mCallback
        mCallback = null
        if (!isCleared) callback?.invoke()
    }

    private fun bitmapBudget(remainingImages: Int): Long {
        SVGAIO.checkInterrupted()
        val available = (SVGAMemoryLimits.videoBitmapBytes - imageBytes) / remainingImages.coerceAtLeast(1)
        if (available < 4) throw IOException("SVGA texture count exceeds its memory budget")
        return minOf(SVGAMemoryLimits.bitmapBytes, available)
    }

    private fun storeBitmap(key: String, bitmap: Bitmap) {
        imageMap.put(key, bitmap)?.let { imageBytes -= it.allocationByteCount.toLong() }
        imageBytes += bitmap.allocationByteCount.toLong()
    }

    private fun parserImages(json: JSONObject) {
        val imgJson = json.optJSONObject("images") ?: return
        val keys = imgJson.keys().asSequence().toList()
        keys.forEachIndexed { index, imgKey ->
            val filePath = generateBitmapFilePath(imgJson[imgKey].toString(), imgKey)
            if (filePath.isEmpty()) {
                return@forEachIndexed
            }
            val bitmapKey = imgKey.replace(".matte", "")
            val bitmap = createBitmap(filePath, bitmapBudget(keys.size - index))
            if (bitmap != null) storeBitmap(bitmapKey, bitmap)
        }
    }

    private fun generateBitmapFilePath(imgName: String, imgKey: String): String {
        val path = mCacheDir.absolutePath + "/" + imgName
        val path1 = "$path.png"
        val path2 = mCacheDir.absolutePath + "/" + imgKey + ".png"

        return when {
            File(path).exists() -> path
            File(path1).exists() -> path1
            File(path2).exists() -> path2
            else -> ""
        }
    }

    private fun createBitmap(filePath: String, budget: Long): Bitmap? {
        return SVGABitmapFileDecoder.decodeBitmapFrom(filePath, mFrameWidth, mFrameHeight, budget)
    }

    private fun isAudio(data: okio.ByteString): Boolean {
        if (data.size < 3) return false
        return (data[0] == 73.toByte() && data[1] == 68.toByte() && data[2] == 51.toByte()) ||
                (data[0] == (-1).toByte() && data[1] == (-5).toByte() && data[2] == (-108).toByte())
    }

    private fun parserImages(obj: MovieEntity) {
        val images = obj.images.entries.filterNot { isAudio(it.value) }
        images.forEachIndexed { index, entry ->
            val budget = bitmapBudget(images.size - index)
            val data = entry.value
            // File references are small UTF-8 names; binary PNG/JPEG data must never be
            // converted to a cached UTF-8 String just to try a filesystem lookup.
            val isFileName = data.size in 1..255 &&
                    (0 until data.size).all { (data[it].toInt() and 0xff) >= 32 }
            val bitmap = if (isFileName) {
                val path = generateBitmapFilePath(data.utf8(), entry.key)
                if (path.isEmpty()) null else createBitmap(path, budget)
            } else {
                SVGABitmapByteArrayDecoder.decodeBitmapFrom(data.toByteArray(), mFrameWidth, mFrameHeight, budget)
            }
            if (bitmap != null) storeBitmap(entry.key, bitmap)
        }
    }

    private fun resetSprites(json: JSONObject) {
        val mutableList: MutableList<SVGAVideoSpriteEntity> = mutableListOf()
        json.optJSONArray("sprites")?.let { item ->
            for (i in 0 until item.length()) {
                item.optJSONObject(i)?.let { entryJson ->
                    mutableList.add(SVGAVideoSpriteEntity(entryJson))
                }
            }
        }
        spriteList = mutableList.toList()
    }

    private fun resetSprites(entity: MovieEntity) {
        spriteList = entity.sprites?.map {
            return@map SVGAVideoSpriteEntity(it)
        } ?: listOf()
    }

    private fun validateStructure(entity: MovieEntity) {
        SVGADecodeBudget.enter()
        try {
            repeat(entity.images.size + entity.audios.size) {
                SVGADecodeBudget.enter()
                SVGADecodeBudget.exit()
            }
            entity.sprites.forEach { sprite ->
                SVGADecodeBudget.enter()
                try {
                    sprite.frames.forEach { frame ->
                        SVGADecodeBudget.enter()
                        try {
                            frame.shapes.forEach {
                                SVGADecodeBudget.enter()
                                SVGADecodeBudget.exit()
                            }
                        } finally { SVGADecodeBudget.exit() }
                    }
                } finally { SVGADecodeBudget.exit() }
            }
        } finally { SVGADecodeBudget.exit() }
    }

    private class AudioLoadCallback(video: SVGAVideoEntity, private val expected: Int) : SVGASoundManager.SVGASoundCallBack {
        private val owner = WeakReference(video)
        private val completed = AtomicInteger()

        override fun onVolumeChange(value: Float) {
            owner.get()?.let { SVGASoundManager.setVolume(value, it) }
        }

        override fun onComplete() {
            if (completed.incrementAndGet() == expected) owner.get()?.completePreparation()
        }
    }

    private fun setupAudios(entity: MovieEntity, playCallback: SVGAParser.PlayCallback?) {
        if (entity.audios.isEmpty()) {
            completePreparation()
            return
        }
        val files = HashMap<String, File>()
        entity.images.forEach { (key, data) ->
            SVGAIO.checkInterrupted()
            if (isAudio(data)) {
                val file = SVGACache.buildAudioFile(key)
                if (!file.exists()) FileOutputStream(file).use { data.write(it) }
                files[key] = file
            }
        }
        if (playCallback != null) {
            playCallback.onPlay(files.values.toList())
            completePreparation()
            return
        }
        audioList = entity.audios.map { SVGAAudioEntity(it) }
        val jobs = entity.audios.zip(audioList).filter { (audio, _) ->
            (audio.totalTime ?: 0) > 0 && files.containsKey(audio.audioKey)
        }
        if (jobs.isEmpty()) {
            completePreparation()
            return
        }
        // Repeated events can share a sample. Loading each event separately grows native
        // SoundPool memory even when there is only one small MP3 in the asset.
        val samples = jobs.groupBy { (audio, _) -> Triple(audio.audioKey, audio.startTime, audio.totalTime) }
        if (samples.size > SVGAMemoryLimits.MAX_SOUND_SAMPLES) {
            throw IOException("SVGA audio exceeds the sound sample limit")
        }
        val callback = AudioLoadCallback(this, samples.size)
        soundCallback = callback
        sharedPoolGeneration = SVGASoundManager.poolGeneration()
        val shared = sharedPoolGeneration != null
        if (!shared) {
            soundPool = generateSoundPool(samples.size)
            soundPool?.setOnLoadCompleteListener { _, _, _ -> callback.onComplete() }
        }
        samples.values.forEach { events ->
            val audio = events.first().first
            SVGAIO.checkInterrupted()
            FileInputStream(requireNotNull(files[audio.audioKey])).use { input ->
                val length = input.channel.size()
                val offset = (((audio.startTime ?: 0).toDouble() / (audio.totalTime ?: 1)) * length)
                        .toLong().coerceIn(0L, length)
                val id = if (shared) {
                    SVGASoundManager.load(callback, input.fd, offset, length - offset, 1)
                } else {
                    soundPool?.load(input.fd, offset, length - offset, 1) ?: 0
                }
                if (id > 0) events.forEach { (_, item) -> item.soundID = id } else callback.onComplete()
            }
        }
    }

    private fun generateSoundPool(streams: Int): SoundPool {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        return SoundPool.Builder().setAudioAttributes(attributes)
                .setMaxStreams(minOf(12, streams)).build()
    }

    @Synchronized
    fun clear() {
        if (isCleared) return
        isCleared = true
        preparationHandler.removeCallbacks(preparationTimeout)
        mCallback = null
        audioList.forEach { audio ->
            audio.soundID?.let { SVGASoundManager.unload(it, soundCallback) }
        }
        soundCallback = null
        sharedPoolGeneration = null
        soundPool?.setOnLoadCompleteListener(null)
        soundPool?.release()
        soundPool = null
        audioList = emptyList()
        spriteList = emptyList()
        imageMap.clear()
        imageBytes = 0
        movieItem = null
    }
}
