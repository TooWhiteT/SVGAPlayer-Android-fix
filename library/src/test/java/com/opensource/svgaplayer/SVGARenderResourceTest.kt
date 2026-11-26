package com.opensource.svgaplayer

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PathMeasure
import android.media.SoundPool
import com.opensource.svgaplayer.entities.SVGAAudioEntity
import com.opensource.svgaplayer.entities.sharedPath
import com.opensource.svgaplayer.proto.AudioEntity
import com.opensource.svgaplayer.utils.SVGARange
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSoundPool
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SVGARenderResourceTest.RecordingSoundPool::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SVGARenderResourceTest {
    @Implements(SoundPool::class)
    class RecordingSoundPool : ShadowSoundPool() {
        val played = mutableListOf<Int>()
        val paused = mutableListOf<Int>()
        val resumed = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()

        @Implementation
        public override fun play(soundID: Int, leftVolume: Float, rightVolume: Float,
                                 priority: Int, loop: Int, rate: Float): Int {
            val streamID = played.size + 1
            played.add(streamID)
            return streamID
        }

        @Implementation fun pause(streamID: Int) { paused.add(streamID) }
        @Implementation fun resume(streamID: Int) { resumed.add(streamID) }
        @Implementation fun stop(streamID: Int) { stopped.add(streamID) }
    }

    private class Player : AutoCloseable {
        val view = SVGAImageView(RuntimeEnvironment.getApplication())
        private val video = SVGAVideoEntity(JSONObject("""{
            "movie":{"viewBox":{"width":10,"height":10},"fps":10,"frames":10}
        }"""), File("."))
        private val pool = SoundPool.Builder().setMaxStreams(4).build()
        val calls: RecordingSoundPool = Shadow.extract(pool)
        private val canvas = Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        val drawable: SVGADrawable get() = view.drawable as SVGADrawable

        init {
            video.soundPool = pool
            video.audioList = listOf(SVGAAudioEntity(AudioEntity("sound", 0, 4, 0, 400)).apply { soundID = 8 })
            view.setVideoItem(video)
        }

        fun draw() = drawable.draw(canvas)
        override fun close() = view.clear()
    }

    @Test fun redrawAndResumeKeepOneStreamUntilTheFrameChanges() {
        Player().use { player ->
            player.view.startAnimation()
            player.draw()
            player.draw()
            player.view.pauseAnimation()
            player.draw()
            player.view.resumeAnimation()
            player.draw()
            assertEquals(listOf(1), player.calls.played)
            assertEquals(listOf(1), player.calls.paused)
            assertEquals(listOf(1), player.calls.resumed)

            // Jump over the end frame, then re-enter the start frame.
            player.drawable.currentFrame = 6
            player.draw()
            assertEquals(listOf(1), player.calls.stopped)
            player.drawable.currentFrame = 0
            player.draw()
            player.view.clear()
            assertEquals(listOf(1, 2), player.calls.played)
            assertEquals(listOf(1, 2), player.calls.stopped)
        }
    }

    @Test fun oneFrameLoopAndRestartCanReplayTheSameSound() {
        Player().use { player ->
            player.view.startAnimation(SVGARange(0, 1))
            val animator = ReflectionHelpers.getField<ValueAnimator>(player.view, "mAnimator")
            fun advanceTo(time: Long) {
                ReflectionHelpers.callInstanceMethod<Boolean>(animator, "doAnimationFrame",
                    ClassParameter.from(Long::class.javaPrimitiveType, time))
            }
            advanceTo(1000L)
            player.draw()
            advanceTo(1000L + animator.duration + 1)
            player.draw()
            player.draw()
            assertEquals(listOf(1, 2), player.calls.played)
            assertEquals(listOf(1), player.calls.stopped)

            player.view.stepToFrame(0, false)
            player.draw()
            assertEquals(listOf(1, 2), player.calls.stopped)
            assertEquals(listOf(1, 2), player.calls.played)
            player.view.startAnimation()
            player.draw()
            assertEquals(listOf(1, 2, 3), player.calls.played)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun renderedContourBelongsToItsShapeAndLeavesNoGlobalGeometry() {
        val video = SVGAVideoEntity(JSONObject("""{
            "movie":{"viewBox":{"width":10,"height":10},"fps":10,"frames":1},
            "sprites":[{"imageKey":"triangle","frames":[{"alpha":1,"shapes":[
                {"type":"shape","args":{"d":"M0 0 L3 0 L3 4 Z"},"styles":{"fill":[1,0,0,1]}}
            ]}]}]
        }"""), File("."))
        val drawable = SVGADrawable(video)
        drawable.cleared = false
        try {
            drawable.draw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)))
            val shape = video.spriteList.single().frames.single().shapes.single()
            assertEquals(12f, PathMeasure(shape.shapePath, false).length, 0.01f)
        } finally {
            drawable.clear()
        }
        assertTrue(sharedPath.isEmpty)
    }
}
