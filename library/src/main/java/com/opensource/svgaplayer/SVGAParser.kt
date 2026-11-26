package com.opensource.svgaplayer

import android.content.Context
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.utils.SVGAIO
import com.opensource.svgaplayer.utils.SVGAJsonDecoder
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import com.opensource.svgaplayer.utils.log.LogUtils
import okio.AsyncTimeout
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
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
    internal var ownedInputReadTimeoutMillis = 20_000L

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
        @Volatile
        internal var threadPoolExecutor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                ArrayBlockingQueue(32)) { runnable ->
            Thread(runnable, "SVGAParser-Thread-${threadNum.getAndIncrement()}")
        }.apply {
            // A worker may still be submitting to the old pool during a swap. Let it drain
            // and retire on idle, leaving caller-supplied executors under their owner's control.
            allowCoreThreadTimeOut(true)
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
                request.setSourceCancellation(cancellation)
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

    /** See [decodeFromInputStreamWithCancel] for stream ownership and read timeouts. */
    fun decodeFromInputStream(inputStream: InputStream, cacheKey: String, callback: ParseCompletion?,
                              closeInputStream: Boolean = false, playCallback: PlayCallback? = null,
                              alias: String? = null) {
        decodeFromInputStreamWithCancel(inputStream, cacheKey, callback, closeInputStream, playCallback, alias)
    }

    /**
     * Starts parsing and returns a function that cancels this request. Invoke it directly in Kotlin
     * or call its `invoke()` method in Java. Cancellation suppresses pending callbacks.
     *
     * With [closeInputStream] enabled, the parser closes the input exactly once on completion,
     * cancellation or failure. A read that stalls for 20 seconds also closes it and reports an
     * error. Supply a stream whose close wakes a blocked read, such as a socket input stream.
     * With borrowed inputs, the caller controls timeouts and must unblock reads that ignore
     * thread interruption.
     */
    @JvmOverloads
    fun decodeFromInputStreamWithCancel(inputStream: InputStream, cacheKey: String, callback: ParseCompletion?,
                                        closeInputStream: Boolean = false, playCallback: PlayCallback? = null,
                                        alias: String? = null): () -> Unit {
        val size = frameSize
        val request = SVGAParseRequest(callback, alias)
        val ownedInput = if (closeInputStream) OwnedInput(inputStream, ownedInputReadTimeoutMillis) else null
        submit(request, onFinally = { ownedInput?.close() }) {
            decodeStream(ownedInput ?: inputStream, cacheKey, request, playCallback, size,
                    closeAfterRead = closeInputStream)
        }
        return request::cancel
    }

    private class OwnedInput(input: InputStream, timeoutMillis: Long) : InputStream() {
        private companion object {
            // A custom close() may block. Keep it off Okio's process-wide watchdog.
            val timeoutClosers = ThreadPoolExecutor(SVGAMemoryLimits.MAX_PENDING_VIDEOS,
                    SVGAMemoryLimits.MAX_PENDING_VIDEOS, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) {
                runnable -> Thread(runnable, "SVGAParser-InputCloser").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }

        private val source = AtomicReference<InputStream?>(input)
        private val closeOnTimeout = Runnable {
            try { close() } catch (error: Exception) {
                LogUtils.error(TAG, "Unable to close stalled SVGA input", error)
            }
        }
        private val readTimeout = object : AsyncTimeout() {
            override fun timedOut() {
                timeoutClosers.execute(closeOnTimeout)
                if (source.get() == null) timeoutClosers.remove(closeOnTimeout)
            }
        }.apply { timeout(timeoutMillis, TimeUnit.MILLISECONDS) }

        private fun source(): InputStream = source.get() ?: throw IOException("SVGA input closed")

        override fun read(): Int = readTimeout.withTimeout { source().read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                readTimeout.withTimeout { source().read(buffer, offset, length) }

        override fun close() {
            val input = source.getAndSet(null) ?: return
            timeoutClosers.remove(closeOnTimeout)
            input.close()
        }
    }

    private fun submit(request: SVGAParseRequest, onFinally: () -> Unit = {}, block: () -> Unit) {
        try {
            val executor = threadPoolExecutor
            val future = executor.submit { runRequest(request, onFinally, block) }
            request.setSourceCancellation {
                future.cancel(true)
                if (future is Runnable) executor.remove(future)
                try { onFinally() } catch (error: Exception) {
                    LogUtils.error(TAG, "Unable to close cancelled SVGA input", error)
                }
            }
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
                             playCallback: PlayCallback?, size: Pair<Int, Int>, closeAfterRead: Boolean = false) {
        if (mContext == null) throw IOException("SVGAParser context is not initialized")
        val bytes = SVGAIO.readBytes(input, SVGAMemoryLimits.inputBytes)
        // Finish owned I/O before preparing or posting the video, including any close failure.
        if (closeAfterRead) input.close()
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
