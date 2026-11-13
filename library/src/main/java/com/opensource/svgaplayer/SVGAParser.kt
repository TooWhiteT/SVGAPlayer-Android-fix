package com.opensource.svgaplayer

import android.content.Context
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.utils.SVGAIO
import com.opensource.svgaplayer.utils.SVGAJsonDecoder
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import com.opensource.svgaplayer.utils.log.LogUtils
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

private val fileLock = Any()

class SVGAParser(context: Context?) {
    private var mContext = context?.applicationContext
    @Volatile private var frameSize = 0 to 0

    init {
        SVGACache.onCreate(mContext)
    }

    interface ParseCompletion {
        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError()
    }

    interface PlayCallback {
        fun onPlay(file: List<File>)
    }

    open class FileDownloader {
        var noCache = false

        /** The completion consumes the stream synchronously, before this method closes it. */
        open fun resume(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
            val cancelled = AtomicBoolean()
            val connection = AtomicReference<HttpURLConnection?>()
            val future = threadPoolExecutor.submit {
                var http: HttpURLConnection? = null
                try {
                    SVGAIO.checkInterrupted()
                    http = url.openConnection() as HttpURLConnection
                    connection.set(http)
                    if (cancelled.get()) return@submit
                    http.connectTimeout = 20_000
                    http.readTimeout = 20_000
                    if (noCache) http.useCaches = false
                    http.connect()
                    if (cancelled.get()) return@submit
                    if (http.contentLength.toLong() > SVGAMemoryLimits.inputBytes) {
                        throw IOException("SVGA download exceeds its size limit")
                    }
                    http.inputStream.use {
                        if (!cancelled.get()) complete(SVGAIO.LimitedInputStream(it, SVGAMemoryLimits.inputBytes))
                    }
                } catch (error: Exception) {
                    if (!cancelled.get()) failure(error)
                } catch (error: OutOfMemoryError) {
                    if (!cancelled.get()) failure(IOException("Insufficient memory to load SVGA", error))
                } finally {
                    connection.set(null)
                    http?.disconnect()
                }
            }
            return {
                cancelled.set(true)
                connection.getAndSet(null)?.disconnect()
                future.cancel(true)
                Unit
            }
        }
    }

    var fileDownloader = FileDownloader()

    companion object {
        private const val TAG = "SVGAParser"
        private val threadNum = AtomicInteger()
        private val mShareParser = SVGAParser(null)

        // Decode on the same worker as the download. Queuing a second task would retain
        // complete downloaded byte arrays behind all the other pending downloads.
        internal var threadPoolExecutor = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(32)) { runnable ->
            Thread(runnable, "SVGAParser-Thread-${threadNum.getAndIncrement()}")
        }

        fun setThreadPoolExecutor(executor: ThreadPoolExecutor) {
            threadPoolExecutor = executor
        }

        fun shareParser(): SVGAParser = mShareParser
    }

    fun init(context: Context) {
        mContext = context.applicationContext
        SVGACache.onCreate(mContext)
    }

    fun setFrameSize(frameWidth: Int, frameHeight: Int) {
        frameSize = frameWidth to frameHeight
    }

    fun decodeFromAssets(name: String, callback: ParseCompletion?, playCallback: PlayCallback? = null) {
        decodeFromAssetsWithCancel(name, callback, playCallback)
    }

    internal fun decodeFromAssetsWithCancel(name: String, callback: ParseCompletion?,
                                            playCallback: PlayCallback? = null): () -> Unit {
        val size = frameSize
        val request = SVGAParseRequest(callback, name)
        submit(request) {
            val context = mContext ?: throw IOException("SVGAParser context is not initialized")
            context.assets.open(name).use {
                decodeStream(it, SVGACache.buildCacheKey("file:///assets/$name"), request, playCallback, size)
            }
        }
        return request::cancel
    }

    fun decodeFromURL(url: URL, callback: ParseCompletion?, playCallback: PlayCallback? = null): (() -> Unit)? {
        val size = frameSize
        val alias = url.toString()
        val key = SVGACache.buildCacheKey(url)
        val request = SVGAParseRequest(callback, alias)
        try {
            if (SVGACache.isCached(key)) {
                submit(request) {
                    if (SVGACache.isDefaultCache()) decodeCached(key, request, playCallback, size)
                    else FileInputStream(SVGACache.buildSvgaFile(key)).use {
                        decodeStream(it, key, request, playCallback, size)
                    }
                }
            } else {
                val cancellation = fileDownloader.resume(url, { input ->
                    runRequest(request) { decodeStream(input, key, request, playCallback, size) }
                }, request::fail)
                request.setDownloadCancellation(cancellation)
            }
        } catch (error: Exception) {
            request.fail(error)
        }
        return request::cancel
    }

    fun decodeFromSVGAFileCacheKey(cacheKey: String, callback: ParseCompletion?,
                                   playCallback: PlayCallback?, alias: String? = null) {
        val size = frameSize
        val request = SVGAParseRequest(callback, alias)
        submit(request) {
            FileInputStream(SVGACache.buildSvgaFile(cacheKey)).use {
                decodeStream(it, cacheKey, request, playCallback, size)
            }
        }
    }

    fun decodeFromInputStream(inputStream: InputStream, cacheKey: String, callback: ParseCompletion?,
                              closeInputStream: Boolean = false, playCallback: PlayCallback? = null,
                              alias: String? = null) {
        val size = frameSize
        val request = SVGAParseRequest(callback, alias)
        submit(request, onFinally = { if (closeInputStream) inputStream.close() }) {
            decodeStream(inputStream, cacheKey, request, playCallback, size)
        }
    }

    private fun submit(request: SVGAParseRequest, onFinally: () -> Unit = {}, block: () -> Unit) {
        try {
            val future = threadPoolExecutor.submit { runRequest(request, onFinally, block) }
            request.setTask(future)
        } catch (error: Exception) {
            try { onFinally() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            request.fail(error)
        }
    }

    private fun runRequest(request: SVGAParseRequest, onFinally: () -> Unit = {}, block: () -> Unit) {
        try {
            // 申请额度时也可能被中断，此时仍须关闭由解析器接管的输入流。
            try {
                request.begin()
                block()
            } finally {
                onFinally()
            }
        } catch (error: Throwable) {
            request.fail(if (error is Exception) error else IOException("Unable to parse SVGA", error))
            if (error !is Exception && error !is OutOfMemoryError) throw error
        } finally {
            request.workerFinished()
        }
    }

    private fun decodeStream(input: InputStream, key: String, request: SVGAParseRequest,
                             playCallback: PlayCallback?, size: Pair<Int, Int>) {
        if (mContext == null) throw IOException("SVGAParser context is not initialized")
        val bytes = SVGAIO.readBytes(input, SVGAMemoryLimits.inputBytes)
        if (bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) {
            synchronized(fileLock) {
                val directory = SVGACache.buildCacheDir(key)
                if (!File(directory, "movie.binary").isFile && !File(directory, "movie.spec").isFile) {
                    unzip(ByteArrayInputStream(bytes), key)
                }
            }
            decodeCached(key, request, playCallback, size)
            return
        }
        if (!SVGACache.isDefaultCache()) {
            val cache = SVGACache.buildSvgaFile(key)
            try {
                FileOutputStream(cache).use { it.write(bytes) }
            } catch (error: Exception) {
                cache.delete()
                LogUtils.error(TAG, "Unable to write SVGA cache", error)
            }
        }
        val inflater = Inflater()
        val movie = try {
            InflaterInputStream(ByteArrayInputStream(bytes), inflater).use {
                MovieEntity.ADAPTER.decode(SVGAIO.LimitedInputStream(it, SVGAMemoryLimits.inflatedBytes))
            }
        } finally {
            inflater.end()
        }
        SVGAIO.checkInterrupted()
        prepareVideo(SVGAVideoEntity(movie, SVGACache.buildCacheDir(key), size.first, size.second),
                request, playCallback)
    }

    private fun decodeCached(key: String, request: SVGAParseRequest, playCallback: PlayCallback?,
                             size: Pair<Int, Int>) {
        if (mContext == null) throw IOException("SVGAParser context is not initialized")
        val directory = SVGACache.buildCacheDir(key)
        val binary = File(directory, "movie.binary")
        val spec = File(directory, "movie.spec")
        val video = when {
            binary.isFile -> FileInputStream(binary).use {
                SVGAVideoEntity(MovieEntity.ADAPTER.decode(SVGAIO.LimitedInputStream(it, SVGAMemoryLimits.inflatedBytes)),
                        directory, size.first, size.second)
            }
            spec.isFile -> FileInputStream(spec).use {
                SVGAVideoEntity(SVGAJsonDecoder.decode(SVGAIO.LimitedInputStream(it, SVGAMemoryLimits.inflatedBytes)),
                        directory, size.first, size.second)
            }
            else -> throw IOException("SVGA cache has no movie data")
        }
        prepareVideo(video, request, playCallback)
    }

    private fun prepareVideo(video: SVGAVideoEntity, request: SVGAParseRequest,
                             playCallback: PlayCallback?) {
        if (!request.attach(video)) return
        try {
            SVGAIO.checkInterrupted()
            if (!request.hasReceiver() && playCallback == null) request.ready()
            else video.prepare(request::ready, playCallback)
        } catch (error: Throwable) {
            video.clear()
            throw error
        }
    }

    private fun unzip(input: InputStream, key: String) {
        val directory = SVGACache.buildCacheDir(key)
        directory.mkdirs()
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                val buffer = ByteArray(8192)
                var expanded = 0L
                var entries = 0
                while (true) {
                    SVGAIO.checkInterrupted()
                    val entry = zip.nextEntry ?: break
                    if (++entries > 4096) throw IOException("Too many SVGA ZIP entries")
                    if (entry.isDirectory || entry.name.contains("/") || entry.name.contains("\\")) continue
                    val file = File(directory, entry.name)
                    if (!file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
                        throw IOException("Invalid SVGA ZIP path")
                    }
                    FileOutputStream(file).use { output ->
                        while (true) {
                            SVGAIO.checkInterrupted()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            expanded += count
                            if (expanded > SVGAMemoryLimits.inflatedBytes) throw IOException("SVGA ZIP exceeds its size limit")
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            SVGACache.clearDir(directory.absolutePath)
            directory.delete()
            throw error
        }
    }

    @Deprecated("Use decodeFromAssets", ReplaceWith("decodeFromAssets(assetsName, callback)"))
    fun parse(assetsName: String, callback: ParseCompletion?) = decodeFromAssets(assetsName, callback)

    @Deprecated("Use decodeFromURL", ReplaceWith("decodeFromURL(url, callback)"))
    fun parse(url: URL, callback: ParseCompletion?) { decodeFromURL(url, callback) }

    @Deprecated("Use decodeFromInputStream", ReplaceWith("decodeFromInputStream(inputStream, cacheKey, callback, closeInputStream)"))
    fun parse(inputStream: InputStream, cacheKey: String, callback: ParseCompletion?, closeInputStream: Boolean = false) {
        decodeFromInputStream(inputStream, cacheKey, callback, closeInputStream)
    }
}
