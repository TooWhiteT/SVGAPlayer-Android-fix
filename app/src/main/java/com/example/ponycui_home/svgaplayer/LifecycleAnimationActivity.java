package com.example.ponycui_home.svgaplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.opensource.svgaplayer.LifecycleSVGAImageView;
import com.opensource.svgaplayer.SVGACallback;
import com.opensource.svgaplayer.SVGADrawable;

import org.jetbrains.annotations.NotNull;

/**
 * A framework Activity keeps this sample compatible with the existing support-v7 demos.
 * Forward real Activity events to AndroidX; ComponentActivity/FragmentActivity do this for you.
 */
public class LifecycleAnimationActivity extends Activity implements LifecycleOwner {
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private FrameLayout preview;
    private TextView status;
    private TextView emptyPreview;
    private Button visibilityButton;
    private Button pauseButton;
    private Button attachmentButton;
    private Switch resumedOnly;
    private LifecycleSVGAImageView animationView;
    private boolean manuallyPaused;
    private boolean lastRemovalReleased = true;
    private int lastPausedFrame = -1;
    private long lastStatusUpdate;

    @NotNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        setContentView(R.layout.activity_lifecycle_animation);
        preview = findViewById(R.id.lifecycle_preview);
        status = findViewById(R.id.lifecycle_status);
        emptyPreview = findViewById(R.id.lifecycle_empty);
        visibilityButton = findViewById(R.id.lifecycle_toggle_visibility);
        pauseButton = findViewById(R.id.lifecycle_toggle_pause);
        attachmentButton = findViewById(R.id.lifecycle_toggle_attachment);
        resumedOnly = findViewById(R.id.lifecycle_resumed_only);

        visibilityButton.setOnClickListener(v -> {
            preview.setVisibility(preview.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
            renderStatus();
        });
        pauseButton.setOnClickListener(v -> {
            if (animationView == null) return;
            manuallyPaused = !manuallyPaused;
            if (manuallyPaused) animationView.pauseAnimation();
            else animationView.resumeAnimation();
            renderStatus();
        });
        attachmentButton.setOnClickListener(v -> {
            if (animationView == null) {
                createAnimationView();
            } else {
                // Detach alone releases the video, cancels its XML load, and removes its observer.
                LifecycleSVGAImageView removed = animationView;
                preview.removeView(removed);
                lastRemovalReleased = removed.getDrawable() == null && !removed.isAnimating();
                animationView = null;
                preview.setVisibility(View.VISIBLE);
                emptyPreview.setText(lastRemovalReleased
                        ? R.string.lifecycle_sample_empty : R.string.lifecycle_sample_release_failed);
                emptyPreview.setVisibility(View.VISIBLE);
                renderStatus();
            }
        });
        findViewById(R.id.lifecycle_open_page).setOnClickListener(v ->
                startActivity(new Intent(this, AnimationFromLayoutActivity.class)));
        resumedOnly.setOnCheckedChangeListener((button, checked) -> {
            if (animationView != null) animationView.setMinActiveState(checked
                    ? Lifecycle.State.RESUMED : Lifecycle.State.STARTED);
            renderStatus();
        });
        createAnimationView();
    }

    private void createAnimationView() {
        manuallyPaused = false;
        lastPausedFrame = -1;
        preview.setVisibility(View.VISIBLE);
        emptyPreview.setVisibility(View.GONE);
        final LifecycleSVGAImageView player = (LifecycleSVGAImageView) getLayoutInflater()
                .inflate(R.layout.view_lifecycle_animation, preview, false);
        animationView = player;
        player.setMinActiveState(resumedOnly.isChecked()
                ? Lifecycle.State.RESUMED : Lifecycle.State.STARTED);
        // The View discovers this Activity's LifecycleOwner through its Context automatically.
        player.setCallback(new SVGACallback() {
            @Override
            public void onPause() {
                if (animationView != player) return;
                lastPausedFrame = currentFrame();
                renderStatus();
            }

            @Override
            public void onFinished() {
                renderStatus();
            }

            @Override
            public void onRepeat() {
                renderStatus();
            }

            @Override
            public void onStep(int frame, double percentage) {
                long now = SystemClock.uptimeMillis();
                if (now - lastStatusUpdate >= 100) {
                    lastStatusUpdate = now;
                    renderStatus();
                }
            }
        });
        preview.addView(player);
        renderStatus();
    }

    private int currentFrame() {
        if (animationView != null && animationView.getDrawable() instanceof SVGADrawable) {
            return ((SVGADrawable) animationView.getDrawable()).getCurrentFrame();
        }
        return -1;
    }

    private void renderStatus() {
        if (status == null) return;
        boolean attached = animationView != null;
        boolean loaded = attached && animationView.getDrawable() instanceof SVGADrawable;
        int playback = !attached ? R.string.lifecycle_sample_released
                : !loaded ? R.string.lifecycle_sample_loading
                : animationView.isAnimating() ? R.string.lifecycle_sample_playing
                : animationView.isPaused() ? R.string.lifecycle_sample_paused
                : R.string.lifecycle_sample_stopped;
        int resources = !attached ? (lastRemovalReleased
                ? R.string.lifecycle_sample_released : R.string.lifecycle_sample_retained)
                : loaded ? R.string.lifecycle_sample_loaded : R.string.lifecycle_sample_loading;
        int frame = currentFrame();
        status.setText(getString(R.string.lifecycle_sample_status,
                lifecycleRegistry.getCurrentState().name(), getString(playback),
                frame < 0 ? "—" : Integer.toString(frame),
                lastPausedFrame < 0 ? "—" : Integer.toString(lastPausedFrame), getString(resources)));
        visibilityButton.setEnabled(attached);
        visibilityButton.setText(preview.getVisibility() == View.VISIBLE
                ? R.string.lifecycle_sample_hide : R.string.lifecycle_sample_show);
        pauseButton.setEnabled(loaded);
        pauseButton.setText(manuallyPaused ? R.string.lifecycle_sample_resume : R.string.lifecycle_sample_pause);
        attachmentButton.setText(attached ? R.string.lifecycle_sample_remove : R.string.lifecycle_sample_create);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        renderStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        renderStatus();
    }

    @Override
    protected void onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
        renderStatus();
    }

    @Override
    protected void onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
        renderStatus();
    }

    @Override
    protected void onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }
}
