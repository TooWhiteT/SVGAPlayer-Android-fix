package com.opensource.svgaplayer

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import com.opensource.svgaplayer.drawer.SVGACanvasDrawer
import com.opensource.svgaplayer.proto.MovieEntity
import com.opensource.svgaplayer.utils.SVGAJsonDecoder
import com.opensource.svgaplayer.utils.SVGAMemoryLimits
import okio.Buffer
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.DeflaterOutputStream

/** Standalone device stress runner: no test-framework dependency and no production entry point. */
class SVGAMemoryStressInstrumentation : Instrumentation() {
    private val report = JSONObject()
    private val measurements = org.json.JSONArray()
    private val phases = org.json.JSONArray()
    private val retiredViews = ArrayList<WeakReference<SVGAImageView>>()
    private val retiredVideos = ArrayList<WeakReference<SVGAVideoEntity>>()
    private var activity: SVGAStressActivity? = null
    private var peakPssKb = 0
    private var peakJavaKb = 0L
    private var peakNativeKb = 0L
    private var audioLoads = 0
    private var maxLoadMs = 0L
    private val started = SystemClock.elapsedRealtime()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var successful = false
        try {
            report.put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                    .put("maxHeapMiB", Runtime.getRuntime().maxMemory() / 1024 / 1024)
                    .put("structureNodes", SVGAMemoryLimits.structureNodes)
                    .put("matteBudgetBytes", SVGAMemoryLimits.matteBytes)
            // Explicit shell launch works on devices that restrict app-initiated background starts.
            // This Activity exists only in the separate test APK.
            val monitor = addMonitor(SVGAStressActivity::class.java.name, null, false)
            ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                    "am start -n ${context.packageName}/${SVGAStressActivity::class.java.name}")).use { it.readBytes() }
            activity = waitForMonitorWithTimeout(monitor, 30_000) as? SVGAStressActivity
            checkNotNull(activity) { "Stress Activity did not start" }
            waitForIdleSync()
            sendStatus(0, Bundle().apply { putString("stream", "START warmup (18)\n") })
            SVGASoundManager.init()
            // Warm parser, ZIP cache, renderer, audio pool, and framework classes before baselining.
            repeat(18) { lifecycleIteration(it, false) }
            settle()
            sample("baseline", 0)
            phase("load_render_detach", 240) {
                repeat(240) { index ->
                    lifecycleIteration(index, true)
                    if ((index + 1) % 30 == 0) { settle(); sample("load_render_detach", index + 1) }
                }
            }
            phase("parse_cancel_bursts", 600) { cancelBursts() }
            phase("dynamic_queued_cancel", 600) { dynamicCancellation() }
            phase("large_matte_render", 360) { matteRendering() }
            phase("oversized_structure_reject", 200) { rejectStructures() }
            onMain { requireNotNull(activity).root.removeAllViews() }
            SVGASoundManager.release()
            settle()
            sample("final", 0)
            val liveViews = retiredViews.count { it.get() != null }
            val liveVideos = retiredVideos.count { it.get() != null }
            report.put("trackedViews", retiredViews.size).put("retainedViews", liveViews)
                    .put("trackedVideos", retiredVideos.size).put("retainedVideos", liveVideos)
                    .put("nativeAudioLoads", audioLoads).put("maxAssetLoadMs", maxLoadMs)
            check(liveViews == 0) { "$liveViews detached views remain reachable after GC" }
            check(liveVideos == 0) { "$liveVideos cleared videos remain reachable after GC" }
            successful = true
        } catch (error: Throwable) {
            report.put("failure", Log.getStackTraceString(error))
            Log.e(TAG, "Stress run failed", error)
        } finally {
            report.put("passed", successful).put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("peakPssKiB", peakPssKb).put("peakJavaKiB", peakJavaKb)
                    .put("peakNativeAllocatedKiB", peakNativeKb)
                    .put("measurements", measurements).put("phases", phases)
            val output = File(targetContext.filesDir, "svga-memory-stress.json")
            output.writeText(report.toString(2))
            SVGASoundManager.release()
            onMain { activity?.finish(); activity = null }
            finish(if (successful) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "\nSVGA_STRESS ${if (successful) "PASS" else "FAIL"}\n${report}\n")
                putString("report", output.absolutePath)
            })
        }
    }

    private fun phase(name: String, iterations: Int, block: () -> Unit) {
        val begin = SystemClock.elapsedRealtime()
        Log.i(TAG, "START $name ($iterations)")
        sendStatus(0, Bundle().apply { putString("stream", "START $name ($iterations)\n") })
        block()
        settle()
        sample(name + "_after_gc", iterations)
        phases.put(JSONObject().put("name", name).put("iterations", iterations)
                .put("durationMs", SystemClock.elapsedRealtime() - begin))
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }

    private fun sample(phase: String, iteration: Int) {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val javaKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024
        peakPssKb = maxOf(peakPssKb, info.totalPss)
        peakJavaKb = maxOf(peakJavaKb, javaKb)
        peakNativeKb = maxOf(peakNativeKb, nativeKb)
        val sample = JSONObject().put("phase", phase).put("iteration", iteration)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("pssKiB", info.totalPss).put("javaUsedKiB", javaKb)
                .put("nativeAllocatedKiB", nativeKb)
                .put("graphicsKiB", info.getMemoryStat("summary.graphics"))
        measurements.put(sample)
        Log.i(TAG, "MEM $sample")
    }

    private fun settle() {
        waitForIdleSync()
        repeat(3) { System.gc(); System.runFinalization(); SystemClock.sleep(150) }
        waitForIdleSync()
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
        failure.get()?.let { throw it }
    }

    private fun load(name: String, expectAudio: Boolean = false): SVGAVideoEntity {
        val done = CountDownLatch(1)
        val result = AtomicReference<SVGAVideoEntity>()
        val parser = SVGAParser(context).apply { setFrameSize(512, 512) }
        val begin = SystemClock.elapsedRealtime()
        val bypassAudio = if (expectAudio) null else object : SVGAParser.PlayCallback {
            override fun onPlay(file: List<File>) { }
        }
        parser.decodeFromAssets(name, object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) { result.set(videoItem); done.countDown() }
            override fun onError() { done.countDown() }
        }, bypassAudio)
        check(done.await(40, TimeUnit.SECONDS)) { "Timed out loading $name" }
        maxLoadMs = maxOf(maxLoadMs, SystemClock.elapsedRealtime() - begin)
        if (expectAudio) audioLoads++
        return checkNotNull(result.get()) { "Failed to load $name" }
    }

    private fun lifecycleIteration(index: Int, track: Boolean) {
        val names = arrayOf("rose_2.0.0.svga", "EmptyState.svga", "matteBitmap.svga",
                "matteBitmap_1.x.svga", "jojo_audio.svga", "Castle.svga")
        val name = names[index % names.size]
        val video = load(name, name == "jojo_audio.svga")
        val target = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            onMain {
                val root = requireNotNull(activity).root
                val view = SVGAImageView(requireNotNull(activity))
                root.addView(view, FrameLayout.LayoutParams(512, 512))
                view.layout(0, 0, 512, 512)
                view.setVideoItem(video)
                view.startAnimation()
                repeat(3) { frame -> view.stepToFrame(frame * (video.frames / 3), false); view.draw(Canvas(target)) }
                if (track && (index + 1) % 30 == 0) sample("load_render_detach_live", index + 1)
                if (track) retiredViews.add(WeakReference(view))
                root.removeView(view)
                check(view.drawable == null && !view.isAnimating && video.isCleared) { "Detach did not release $name" }
                if (track) retiredVideos.add(WeakReference(video))
            }
        } finally { target.recycle(); video.clear() }
    }

    private fun cancelBursts() {
        val parser = SVGAParser(context).apply { setFrameSize(512, 512) }
        val unexpected = AtomicInteger()
        repeat(30) { batch ->
            val cancellations = ArrayList<() -> Unit>()
            onMain {
                repeat(20) {
                    cancellations.add(parser.decodeFromAssetsWithCancel("rose_2.0.0.svga",
                            object : SVGAParser.ParseCompletion {
                                override fun onComplete(videoItem: SVGAVideoEntity) { videoItem.clear(); unexpected.incrementAndGet() }
                                override fun onError() { unexpected.incrementAndGet() }
                            }))
                }
                // Give workers time to parse while delivery is held on the UI thread.
                SystemClock.sleep(40)
                if ((batch + 1) % 10 == 0) sample("parse_cancel_bursts_live", (batch + 1) * 20)
                cancellations.forEach { it() }
            }
            awaitWorkers()
            waitForIdleSync()
            check(unexpected.get() == 0) { "Cancelled parse delivered a callback" }
            if ((batch + 1) % 10 == 0) sample("parse_cancel_bursts", (batch + 1) * 20)
        }
    }

    private fun awaitWorkers() {
        val executor = SVGAParser.threadPoolExecutor
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (executor.activeCount != 0 || executor.queue.isNotEmpty()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Parser workers did not drain" }
            SystemClock.sleep(5)
        }
    }

    private fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun dynamicCancellation() {
        val png = ByteArrayOutputStream().let { output ->
            val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serving = Executors.newSingleThreadExecutor()
        val response = serving.submit {
            repeat(600) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(png); flush()
                    }
                }
            }
        }
        try {
            repeat(24) { batch ->
                onMain {
                    repeat(25) {
                        val dynamic = SVGADynamicEntity()
                        dynamic.setDynamicImage("http://127.0.0.1:${server.localPort}/image", "avatar")
                        val request = (field(dynamic, "imageRequests") as Map<*, *>).values.single()!!
                        (field(request, "task") as Future<*>).get(10, TimeUnit.SECONDS)
                        val bitmap = checkNotNull(field(request, "pendingBitmap") as? Bitmap) { "Image was not queued" }
                        val delivery = field(request, "delivery") as Runnable
                        check(bitmap.allocationByteCount == 4 * 1024 * 1024)
                        if (batch % 4 == 0 && it == 0) sample("dynamic_queued_cancel_live", batch * 25 + 1)
                        dynamic.clearDynamicObjects()
                        check(bitmap.isRecycled && field(request, "pendingBitmap") == null)
                        check((field(dynamic, "imageReservations") as Map<*, *>).isEmpty())
                        check(field(request, "delivery") == null)
                        if (Build.VERSION.SDK_INT >= 29) check(!Handler(Looper.getMainLooper()).hasCallbacks(delivery))
                    }
                }
                if ((batch + 1) % 4 == 0) { settle(); sample("dynamic_queued_cancel", (batch + 1) * 25) }
            }
            response.get(10, TimeUnit.SECONDS)
        } finally { server.close(); serving.shutdownNow() }
    }

    private fun matteVideo(): SVGAVideoEntity = SVGAVideoEntity(JSONObject("""{
        "movie":{"viewBox":{"width":100,"height":100},"fps":20,"frames":1},
        "sprites":[
          {"imageKey":"m.matte","frames":[{"alpha":1,"shapes":[{"type":"rect","args":{"x":20,"y":20,"width":40,"height":40,"cornerRadius":0},"styles":{"fill":[1,1,1,1]}}]}]},
          {"imageKey":"c","matteKey":"m.matte","frames":[{"alpha":1,"shapes":[{"type":"rect","args":{"x":0,"y":0,"width":100,"height":100,"cornerRadius":0},"styles":{"fill":[1,0,0,1]}}]}]}
        ]} """), context.cacheDir)

    private fun matteRendering() {
        val video = matteVideo()
        val drawable = SVGADrawable(video).apply { cleared = false }
        var maximumBuffers = 0L
        try {
            val sizes = arrayOf(512 to 512, 2048 to 2048, 4096 to 2160)
            repeat(36) { batch ->
                val (width, height) = sizes[batch % sizes.size]
                val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    onMain {
                        val canvas = Canvas(target)
                        repeat(10) {
                            target.eraseColor(Color.TRANSPARENT)
                            drawable.draw(canvas)
                            check(!video.isCleared) { "Renderer recovered from OOM by clearing the video" }
                        }
                        // MATRIX scale type fits width, so use a point inside the scaled mask.
                        check(Color.alpha(target.getPixel(width / 3, minOf(height - 1, width / 3))) > 0)
                        check(Color.alpha(target.getPixel(0, 0)) == 0)
                        val drawer = field(drawable, "drawer")!!
                        val values = field(drawer, "sharedValues") as SVGACanvasDrawer.ShareValues
                        val bytes = values.sharedLayerBitmap().allocationByteCount.toLong() + values.sharedMatteBitmap().allocationByteCount
                        maximumBuffers = maxOf(maximumBuffers, bytes)
                        check(bytes <= SVGAMemoryLimits.matteBytes)
                    }
                    sample("large_matte_render", (batch + 1) * 10)
                } finally { target.recycle() }
            }
        } finally { onMain { drawable.clear() } }
        report.put("maxMatteBufferBytes", maximumBuffers)
    }

    private fun rejectStructures() {
        val frameData = Buffer().apply { repeat(100_000) { writeByte(18).writeByte(0) } }.readByteArray()
        fun Buffer.varint(value: Int): Buffer {
            var n = value
            while (n >= 128) { writeByte((n and 127) or 128); n = n ushr 7 }
            return writeByte(n)
        }
        val expanded = Buffer().writeByte(34).varint(frameData.size).write(frameData).readByteArray()
        val compressed = ByteArrayOutputStream().let { output ->
            DeflaterOutputStream(output).use { it.write(expanded) }; output.toByteArray()
        }
        val json = ("{\"sprites\":[" + List(SVGAMemoryLimits.structureNodes + 1) { "{}" }.joinToString(",") + "]}").toByteArray()
        repeat(100) { index ->
            var rejected = false
            try {
                java.util.zip.InflaterInputStream(ByteArrayInputStream(compressed)).use { MovieEntity.ADAPTER.decode(it) }
            } catch (expected: IOException) { rejected = true }
            check(rejected)
            rejected = false
            try { SVGAJsonDecoder.decode(ByteArrayInputStream(json)) } catch (expected: IOException) { rejected = true }
            check(rejected)
            if ((index + 1) % 20 == 0) { settle(); sample("oversized_structure_reject", (index + 1) * 2) }
        }
        report.put("amplificationCompressedBytes", compressed.size)
    }

    companion object { private const val TAG = "SVGAStress" }
}

class SVGAStressActivity : Activity() {
    lateinit var root: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}
