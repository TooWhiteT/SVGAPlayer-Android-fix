package com.opensource.svgaplayer

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.opensource.svgaplayer.utils.SVGARange
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Plays while attached, visible and the owner's lifecycle is at least STARTED.
 * Visibility/lifecycle pauses retain the animation and audio position. Explicit pauses/stops
 * remain in effect when visibility returns; [resumeAnimation] opts back into automatic playback.
 *
 * The owner is resolved from [lifecycleOwner], the View tree, then the Context. Without an owner,
 * View/window visibility still controls playback. All calls must be made on the main thread.
 *
 * Detaching or destroying the owner always releases SVGA resources, even when
 * [clearsAfterDetached] is false. A recycled/re-attached View needs a newly loaded video.
 */
open class LifecycleSVGAImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SVGAImageView(context, attrs, defStyleAttr) {

    // ImageView may invoke overridden methods before this class's fields are initialized.
    private var initialized = false
    private var attached = false
    private var aggregatedVisible = true
    private var playbackRequested = false
    private var ownerDestroyed = false
    private var explicitOwner = WeakReference<LifecycleOwner>(null)
    private var observedOwner: LifecycleOwner? = null
    // A preloaded View can be abandoned before its first detach. Keep one observer per
    // lifecycle and weakly track its Views so neither the Views nor observers pile up.
    private class PlaybackObserver : LifecycleEventObserver {
        val views = WeakHashMap<LifecycleSVGAImageView, Unit>()

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            // Clear, owner switches and user callbacks can change the registrations during dispatch.
            ArrayList(views.keys).forEach { view ->
                if (view.observedOwner === source) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        view.ownerDestroyed = true
                        view.removeLifecycleObserver()
                        view.clear()
                    } else {
                        view.updatePlayback()
                    }
                }
            }
            removeIfEmpty(source.lifecycle)
        }

        fun removeIfEmpty(lifecycle: Lifecycle) {
            if (views.isEmpty() && observers[lifecycle] === this) {
                observers.remove(lifecycle)
                lifecycle.removeObserver(this)
            }
        }
    }

    private companion object {
        val observers = WeakHashMap<Lifecycle, PlaybackObserver>()
    }

    /** Set a Fragment's viewLifecycleOwner explicitly when no View tree owner is available. */
    var lifecycleOwner: LifecycleOwner?
        get() = explicitOwner.get()
        set(value) {
            explicitOwner = WeakReference(value)
            bindLifecycleOwner(resolveLifecycleOwner())
            updatePlayback()
        }

    /** Use RESUMED to limit playback to the active page in containers such as ViewPager2. */
    var minActiveState: Lifecycle.State = Lifecycle.State.STARTED
        set(value) {
            require(value == Lifecycle.State.STARTED || value == Lifecycle.State.RESUMED) {
                "minActiveState must be STARTED or RESUMED"
            }
            field = value
            updatePlayback()
        }

    init {
        initialized = true
        bindLifecycleOwner(resolveLifecycleOwner())
        if (drawable is SVGADrawable && autoPlayEnabled) startAnimation()
    }

    override fun startAnimation(range: SVGARange?, reverse: Boolean) {
        if (initialized && ownerDestroyed) {
            clear()
            return
        }
        playbackRequested = true
        super.startAnimation(range, reverse)
        updatePlayback()
    }

    override fun pauseAnimation() {
        playbackRequested = false
        super.pauseAnimation()
    }

    override fun resumeAnimation() {
        playbackRequested = true
        updatePlayback()
    }

    override fun stopAnimation(clear: Boolean) {
        playbackRequested = false
        super.stopAnimation(clear)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        val changed = this.drawable !== drawable
        super.setImageDrawable(drawable)
        if (!initialized) return
        if (drawable !is SVGADrawable) {
            if (!attached) removeLifecycleObserver()
        } else if (ownerDestroyed) {
            // A destroyed Fragment view can lose its tree owner before a late result arrives.
            // Only reattachment or an explicit owner switch starts a new lifecycle.
            clear()
        } else if (changed && bindLifecycleOwner(resolveLifecycleOwner()) && autoPlayEnabled) {
            startAnimation()
        }
    }

    internal override fun onSourceLoadFailed() {
        if (!attached && drawable !is SVGADrawable) removeLifecycleObserver()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        bindLifecycleOwner(resolveLifecycleOwner())
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeLifecycleObserver()
        super.onDetachedFromWindow()
        clear()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updatePlayback()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePlayback()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) super.onVisibilityAggregated(isVisible)
        if (initialized) aggregatedVisible = isVisible
        updatePlayback()
    }

    private fun updatePlayback() {
        if (!initialized || !playbackRequested) return
        val lifecycleStarted = observedOwner?.lifecycle?.currentState
            ?.isAtLeast(minActiveState) ?: true
        if (attached && isShown && windowVisibility == VISIBLE && aggregatedVisible &&
            lifecycleStarted && !ownerDestroyed) {
            super.resumeAnimation()
        } else {
            // Call the base methods so automatic pauses preserve the user's playback intent.
            super.pauseAnimation()
        }
    }

    private fun resolveLifecycleOwner(): LifecycleOwner? {
        explicitOwner.get()?.let { return it }
        findViewTreeLifecycleOwner()?.let { return it }
        var ownerContext = context
        while (true) {
            if (ownerContext is LifecycleOwner) return ownerContext
            val base = (ownerContext as? ContextWrapper)?.baseContext ?: return null
            if (base === ownerContext) return null
            ownerContext = base
        }
    }

    private fun bindLifecycleOwner(owner: LifecycleOwner?): Boolean {
        if (ownerDestroyed || observedOwner !== owner) {
            removeLifecycleObserver()
            val lifecycle = owner?.lifecycle
            ownerDestroyed = lifecycle?.currentState == Lifecycle.State.DESTROYED
            if (lifecycle != null && !ownerDestroyed && (attached || drawable is SVGADrawable || hasPendingLoad)) {
                observedOwner = owner
                val existing = observers[lifecycle]
                val observer = existing ?: PlaybackObserver()
                observer.views[this] = Unit
                if (existing == null) {
                    observers[lifecycle] = observer
                    lifecycle.addObserver(observer)
                }
            }
        }
        if (ownerDestroyed) clear()
        return !ownerDestroyed
    }

    private fun removeLifecycleObserver() {
        val lifecycle = observedOwner?.lifecycle
        observedOwner = null
        if (lifecycle != null) {
            observers[lifecycle]?.let { observer ->
                observer.views.remove(this)
                observer.removeIfEmpty(lifecycle)
            }
        }
    }
}
