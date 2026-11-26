package com.opensource.svgaplayer

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.SoundPool
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.opensource.svgaplayer.entities.SVGAAudioEntity
import com.opensource.svgaplayer.proto.AudioEntity
import com.opensource.svgaplayer.utils.SVGARange
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSoundPool
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LifecycleSVGAImageViewTest {
    private class Owner(state: Lifecycle.State = Lifecycle.State.STARTED) : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    class OwnerActivity : Activity(), LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class WindowView(context: Context) : LifecycleSVGAImageView(context) {
        private var reportedVisibility = VISIBLE
        override fun getWindowVisibility(): Int = reportedVisibility
        fun changeWindowVisibility(visibility: Int) {
            reportedVisibility = visibility
            onWindowVisibilityChanged(visibility)
        }
    }

    private class Fixture(state: Lifecycle.State = Lifecycle.State.STARTED,
                          autoPlay: Boolean = true) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val owner = Owner(state)
        val parent = FrameLayout(activity).apply { setViewTreeLifecycleOwner(owner) }
        val view = LifecycleSVGAImageView(activity, Robolectric.buildAttributeSet()
            .addAttribute(R.attr.autoPlay, autoPlay.toString()).build())

        init {
            activity.setContentView(parent)
            parent.addView(view, FrameLayout.LayoutParams(100, 100))
            // Simulate ActivityThread's app-visibility dispatch; visible() alone leaves it GONE.
            val root = ReflectionHelpers.callInstanceMethod<Any>(activity.window.decorView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Unit>(root, "handleAppVisibility", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
            shadowOf(Looper.getMainLooper()).idle()
        }

        override fun close() {
            parent.removeAllViews()
            controller.pause().stop().destroy()
        }
    }

    private fun video() = SVGAVideoEntity(JSONObject("""{
        "movie":{"viewBox":{"width":100,"height":100},"fps":20,"frames":100}
    }"""), File("."))

    private fun animator(view: SVGAImageView): ValueAnimator? = SVGAImageView::class.java
        .getDeclaredField("mAnimator").apply { isAccessible = true }.get(view) as? ValueAnimator

    @Test fun waitsForStartedOwnerAndAutomaticallyContinuesFromPausedPosition() {
        Fixture(Lifecycle.State.CREATED).use { f ->
            f.view.setVideoItem(video())
            assertTrue(f.view.isPaused)
            assertFalse(f.view.isAnimating)
            val animation = requireNotNull(animator(f.view))
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue("attached=${f.view.isAttachedToWindow}, shown=${f.view.isShown}, " +
                "window=${f.view.windowVisibility}, paused=${f.view.isPaused}, " +
                "started=${animation.isStarted}, running=${animation.isRunning}", f.view.isAnimating)
            animation.currentPlayTime = 350
            val frame = (f.view.drawable as SVGADrawable).currentFrame

            f.owner.registry.currentState = Lifecycle.State.CREATED
            assertTrue(f.view.isPaused)
            shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
            assertEquals(frame, (f.view.drawable as SVGADrawable).currentFrame)
            assertEquals(350L, animation.currentPlayTime)

            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(f.view.isAnimating)
            assertFalse(f.view.isPaused)
            assertSame(animation, animator(f.view))
            assertEquals(frame, (f.view.drawable as SVGADrawable).currentFrame)
        }
    }

    @Test fun automaticPausePreservesRangeReverseAndLoopConfiguration() {
        Fixture().use { f ->
            f.view.loops = 3
            f.view.setVideoItem(video())
            f.view.startAnimation(SVGARange(10, 20), reverse = true)
            val animation = requireNotNull(animator(f.view))
            animation.currentPlayTime = 200
            val frame = (f.view.drawable as SVGADrawable).currentFrame
            f.view.visibility = View.GONE
            assertTrue(f.view.isPaused)
            f.view.visibility = View.VISIBLE
            assertTrue(f.view.isAnimating)
            assertSame(animation, animator(f.view))
            assertEquals(2, animation.repeatCount)
            assertEquals(1000L, animation.duration)
            assertEquals(frame, (f.view.drawable as SVGADrawable).currentFrame)
        }
    }

    @Test fun ownAndAncestorVisibilityPauseWithoutReleasingVideo() {
        Fixture().use { f ->
            val video = video()
            f.view.setVideoItem(video)
            assertTrue(f.view.isAnimating)
            f.view.visibility = View.INVISIBLE
            assertTrue(f.view.isPaused)
            assertFalse(video.isCleared)
            f.view.visibility = View.VISIBLE
            assertTrue(f.view.isAnimating)
            f.parent.visibility = View.GONE
            assertTrue(f.view.isPaused)
            f.parent.visibility = View.VISIBLE
            assertTrue(f.view.isAnimating)
        }
    }

    @Test fun windowVisibilityWorksWithoutLifecycleOwner() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = WindowView(controller.get())
        try {
            controller.get().setContentView(view)
            view.setVideoItem(video())
            assertTrue(view.isAnimating)
            view.changeWindowVisibility(View.GONE)
            assertTrue(view.isPaused)
            view.changeWindowVisibility(View.VISIBLE)
            assertTrue(view.isAnimating)
        } finally { view.clear(); controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [21])
    fun visibilityAndLifecyclePlaybackAlsoWorkOnMinimumSdk() {
        Fixture().use { f ->
            f.view.setVideoItem(video())
            assertTrue(f.view.isAnimating)
            f.parent.visibility = View.GONE
            assertTrue(f.view.isPaused)
            f.parent.visibility = View.VISIBLE
            assertTrue(f.view.isAnimating)
            f.owner.registry.currentState = Lifecycle.State.CREATED
            assertTrue(f.view.isPaused)
        }
    }

    @Test fun explicitOwnerOverridesTreeOwnerAndSwitchingRemovesOldObserver() {
        Fixture().use { f ->
            val explicit = Owner(Lifecycle.State.CREATED)
            assertEquals(1, f.owner.registry.observerCount)
            f.view.lifecycleOwner = explicit
            assertEquals(0, f.owner.registry.observerCount)
            assertEquals(1, explicit.registry.observerCount)
            f.view.setVideoItem(video())
            assertTrue(f.view.isPaused)
            f.owner.registry.currentState = Lifecycle.State.DESTROYED
            assertNotNull(f.view.drawable)
            explicit.registry.currentState = Lifecycle.State.STARTED
            assertTrue(f.view.isAnimating)
            f.view.lifecycleOwner = null
            assertEquals(0, explicit.registry.observerCount)
            assertNull(f.view.drawable)
        }
    }

    @Test fun wrappedContextOwnerIsObservedEvenBeforeFirstAttach() {
        val controller = Robolectric.buildActivity(OwnerActivity::class.java).setup().visible()
        val activity = controller.get()
        activity.registry.currentState = Lifecycle.State.STARTED
        val view = LifecycleSVGAImageView(ContextWrapper(activity))
        val video = video()
        try {
            view.setVideoItem(video)
            assertTrue(view.isPaused)
            assertEquals(1, activity.registry.observerCount)
            activity.registry.currentState = Lifecycle.State.DESTROYED
            assertTrue(video.isCleared)
            assertNull(view.drawable)
            assertEquals(0, activity.registry.observerCount)
        } finally { view.clear(); controller.pause().stop().destroy() }
    }

    @Test fun preloadedViewsCanBeClearedAndReboundBeforeFirstAttach() {
        val controller = Robolectric.buildActivity(OwnerActivity::class.java).setup()
        val activity = controller.get()
        activity.registry.currentState = Lifecycle.State.STARTED
        try {
            val views = List(5) { LifecycleSVGAImageView(activity) }
            assertEquals(0, activity.registry.observerCount)
            val videos = views.map { view -> video().also { view.setVideoItem(it) } }
            assertEquals(1, activity.registry.observerCount)
            views.forEach { it.clear() }
            assertTrue(videos.all { it.isCleared })
            assertEquals(0, activity.registry.observerCount)

            val reloaded = video()
            views.first().setVideoItem(reloaded)
            activity.registry.currentState = Lifecycle.State.DESTROYED
            assertTrue(reloaded.isCleared)
            assertNull(views.first().drawable)
            assertEquals(0, activity.registry.observerCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ownerDoesNotKeepAnAbandonedPreloadedViewAlive() {
        val owner = Owner()
        val collected = ReferenceQueue<LifecycleSVGAImageView>()
        fun preload(): WeakReference<LifecycleSVGAImageView> {
            val attrs = Robolectric.buildAttributeSet().addAttribute(R.attr.autoPlay, "false").build()
            val view = LifecycleSVGAImageView(RuntimeEnvironment.getApplication(), attrs)
            view.lifecycleOwner = owner
            view.setVideoItem(video())
            return WeakReference(view, collected)
        }

        val reference = preload()
        assertEquals(1, owner.registry.observerCount)
        for (attempt in 1..20) {
            System.gc()
            if (collected.remove(100) === reference) break
        }
        assertNull(reference.get())
        owner.registry.currentState = Lifecycle.State.CREATED
        assertEquals(0, owner.registry.observerCount)
    }

    @Test fun autoPlayFalseRequiresExplicitStartAndStillHonorsVisibility() {
        Fixture(autoPlay = false).use { f ->
            f.view.setVideoItem(video())
            f.owner.registry.currentState = Lifecycle.State.CREATED
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertFalse(f.view.isAnimating)
            assertNull(animator(f.view))
            f.view.visibility = View.GONE
            f.view.startAnimation(SVGARange(10, 20), reverse = true)
            assertTrue(f.view.isPaused)
            f.view.visibility = View.VISIBLE
            assertTrue(f.view.isAnimating)
        }
    }

    @Test fun manualPauseRemainsPausedUntilExplicitResume() {
        Fixture().use { f ->
            f.view.setVideoItem(video())
            val animation = animator(f.view)
            f.view.pauseAnimation()
            f.parent.visibility = View.GONE
            f.parent.visibility = View.VISIBLE
            f.owner.registry.currentState = Lifecycle.State.CREATED
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(f.view.isPaused)
            assertFalse(f.view.isAnimating)
            f.view.resumeAnimation()
            assertTrue(f.view.isAnimating)
            assertSame(animation, animator(f.view))
        }
    }

    @Test fun allVisibilityConditionsMustRecoverBeforePlaybackResumes() {
        Fixture().use { f ->
            f.view.setVideoItem(video())
            f.parent.visibility = View.GONE
            f.owner.registry.currentState = Lifecycle.State.CREATED
            f.parent.visibility = View.VISIBLE
            assertTrue(f.view.isPaused)
            f.view.resumeAnimation()
            assertTrue(f.view.isPaused)
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(f.view.isAnimating)
        }
    }

    @Test fun resumedThresholdPausesStartedOffscreenPages() {
        Fixture().use { f ->
            f.view.setVideoItem(video())
            assertTrue(f.view.isAnimating)
            f.view.minActiveState = Lifecycle.State.RESUMED
            assertTrue(f.view.isPaused)
            f.owner.registry.currentState = Lifecycle.State.RESUMED
            assertTrue(f.view.isAnimating)
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertTrue(f.view.isPaused)
        }
    }

    @Test fun manualStopAndClearDoNotRestartWhenVisibleAgain() {
        Fixture().use { f ->
            val video = video()
            f.view.setVideoItem(video)
            f.view.stopAnimation()
            f.view.visibility = View.GONE
            f.view.visibility = View.VISIBLE
            assertFalse(f.view.isAnimating)
            assertNull(animator(f.view))
            f.view.startAnimation()
            assertTrue(f.view.isAnimating)
            f.view.clear()
            f.owner.registry.currentState = Lifecycle.State.CREATED
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertNull(f.view.drawable)
            assertTrue(video.isCleared)
        }
    }

    @Test fun completedFiniteAnimationDoesNotRestartWhenVisibleAgain() {
        Fixture().use { f ->
            f.view.loops = 1
            f.view.setVideoItem(video())
            requireNotNull(animator(f.view)).end()
            assertFalse(f.view.isAnimating)
            f.view.visibility = View.GONE
            f.view.visibility = View.VISIBLE
            assertFalse(f.view.isAnimating)
            assertNull(animator(f.view))
        }
    }

    @Test fun detachAlwaysReleasesResourcesAndRebindingUsesNewVideo() {
        Fixture().use { f ->
            val video = video()
            val dynamic = SVGADynamicEntity()
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            dynamic.setDynamicImage(bitmap, "avatar")
            f.view.clearsAfterDetached = false
            f.view.setVideoItem(video, dynamic)
            f.parent.removeView(f.view)
            assertTrue(video.isCleared)
            assertTrue(dynamic.dynamicImage.isEmpty())
            assertFalse(bitmap.isRecycled)
            assertNull(f.view.drawable)
            assertFalse(f.view.isAnimating)
            assertEquals(0, f.owner.registry.observerCount)

            val replacement = video()
            f.view.setVideoItem(replacement)
            assertTrue(f.view.isPaused)
            f.parent.addView(f.view)
            assertTrue(f.view.isAnimating)
            assertFalse(replacement.isCleared)
            assertEquals(1, f.owner.registry.observerCount)
        }
    }

    @Test fun destroyedOwnerReleasesVideoAndRejectsLateDeliveredVideo() {
        Fixture().use { f ->
            val first = video()
            f.view.setVideoItem(first)
            f.owner.registry.currentState = Lifecycle.State.DESTROYED
            assertTrue(first.isCleared)
            assertNull(f.view.drawable)
            assertEquals(0, f.owner.registry.observerCount)
            val late = video()
            f.view.setVideoItem(late)
            assertTrue(late.isCleared)
            assertNull(f.view.drawable)

            f.parent.removeView(f.view)
            f.parent.setViewTreeLifecycleOwner(null)
            f.parent.addView(f.view)
            val replacement = video()
            f.view.setVideoItem(replacement)
            assertFalse(replacement.isCleared)
            assertTrue(f.view.isAnimating)
        }
    }

    @Test fun losingDestroyedTreeOwnerCannotDeliverLateVideoToActivityOwner() {
        val controller = Robolectric.buildActivity(OwnerActivity::class.java).setup().visible()
        val activity = controller.get()
        activity.registry.currentState = Lifecycle.State.STARTED
        val treeOwner = Owner()
        val parent = FrameLayout(activity).apply { setViewTreeLifecycleOwner(treeOwner) }
        val view = LifecycleSVGAImageView(activity)
        try {
            activity.setContentView(parent)
            parent.addView(view)
            view.setVideoItem(video())
            treeOwner.registry.currentState = Lifecycle.State.DESTROYED
            parent.setViewTreeLifecycleOwner(null)
            val late = video()
            view.setVideoItem(late)
            assertTrue(late.isCleared)
            assertNull(view.drawable)
            assertEquals(0, activity.registry.observerCount)

            parent.removeView(view)
            parent.addView(view)
            val replacement = video()
            view.setVideoItem(replacement)
            assertFalse(replacement.isCleared)
            assertEquals(1, activity.registry.observerCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun destructionCancelsPendingXmlAssetLoad() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val gate = CountDownLatch(1)
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            executor.submit { gate.await() }
            Fixture().use { f ->
                val attrs = Robolectric.buildAttributeSet().addAttribute(R.attr.source, "rose_2.0.0.svga").build()
                val loading = LifecycleSVGAImageView(f.activity, attrs)
                f.parent.addView(loading)
                f.owner.registry.currentState = Lifecycle.State.DESTROYED
                gate.countDown()
                executor.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(loading.drawable)
                assertFalse(loading.isAnimating)
                assertEquals(0, f.owner.registry.observerCount)
            }
        } finally {
            gate.countDown()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }

    @Test fun failedXmlPreloadReleasesObserverAndKeepsPlaceholder() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val controller = Robolectric.buildActivity(OwnerActivity::class.java).setup()
        val activity = controller.get()
        activity.registry.currentState = Lifecycle.State.STARTED
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            val attrs = Robolectric.buildAttributeSet()
                .addAttribute(android.R.attr.src, "@android:drawable/ic_delete")
                .addAttribute(R.attr.source, "missing-lifecycle-preload.svga").build()
            val view = LifecycleSVGAImageView(activity, attrs)
            val placeholder = requireNotNull(view.drawable)
            assertTrue(view.hasPendingLoad)
            assertEquals(1, activity.registry.observerCount)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(view.hasPendingLoad)
            assertEquals(0, activity.registry.observerCount)
            assertSame(placeholder, view.drawable)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            SVGAParser.setThreadPoolExecutor(previous)
            controller.pause().stop().destroy()
        }
    }

    @Test fun xmlSourceLoadsOnceAndWaitsUntilOwnerIsStarted() {
        val previous = SVGAParser.threadPoolExecutor
        val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        SVGAParser.setThreadPoolExecutor(executor)
        try {
            Fixture(Lifecycle.State.CREATED).use { f ->
                val attrs = Robolectric.buildAttributeSet().addAttribute(R.attr.source, "rose_2.0.0.svga").build()
                val loading = LifecycleSVGAImageView(f.activity, attrs)
                f.parent.addView(loading)
                executor.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(loading.drawable)
                assertTrue(loading.isPaused)
                val animation = animator(loading)
                f.owner.registry.currentState = Lifecycle.State.STARTED
                assertTrue(loading.isAnimating)
                assertSame(animation, animator(loading))
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            SVGAParser.setThreadPoolExecutor(previous)
        }
    }

    @Test
    @Config(shadows = [RecordingSoundPool::class])
    fun automaticPauseAndResumeAlsoControlExistingAudioStreams() {
        Fixture().use { f ->
            val video = video()
            f.view.setVideoItem(video)
            val pool = SoundPool.Builder().setMaxStreams(1).build()
            video.soundPool = pool
            video.audioList = listOf(SVGAAudioEntity(AudioEntity("audio", 0, 100, 0, 100)).apply { playID = 7 })
            val recording = Shadow.extract<RecordingSoundPool>(pool)
            f.owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(listOf(7), recording.paused)
            f.owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(listOf(7), recording.resumed)
            f.view.clear()
            assertTrue(recording.stopped.contains(7))
        }
    }

    @Test fun pausedOrStoppedRedrawCannotStartAudioAgain() {
        Fixture().use { f ->
            val video = video()
            f.view.setVideoItem(video)
            val pool = SoundPool.Builder().setMaxStreams(1).build()
            video.soundPool = pool
            val sound = pool.load("paused-probe.mp3", 1)
            video.audioList = listOf(SVGAAudioEntity(AudioEntity("audio", 0, 100, 0, 100)).apply { soundID = sound })
            val drawable = f.view.drawable as SVGADrawable
            val canvas = Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))

            f.view.pauseAnimation()
            drawable.draw(canvas)
            assertEquals(0, shadowOf(pool).getPathPlaybacks("paused-probe.mp3").size)
            f.view.resumeAnimation()
            drawable.draw(canvas)
            assertEquals(1, shadowOf(pool).getPathPlaybacks("paused-probe.mp3").size)
            f.owner.registry.currentState = Lifecycle.State.CREATED
            drawable.draw(canvas)
            assertEquals(1, shadowOf(pool).getPathPlaybacks("paused-probe.mp3").size)
            f.view.stopAnimation()
            drawable.draw(canvas)
            assertEquals(1, shadowOf(pool).getPathPlaybacks("paused-probe.mp3").size)
            assertNull(video.audioList.single().playID)
        }
    }

    @Implements(SoundPool::class)
    class RecordingSoundPool : ShadowSoundPool() {
        val paused = mutableListOf<Int>()
        val resumed = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        @Implementation fun pause(streamId: Int) { paused.add(streamId) }
        @Implementation fun resume(streamId: Int) { resumed.add(streamId) }
        @Implementation fun stop(streamId: Int) { stopped.add(streamId) }
    }
}
