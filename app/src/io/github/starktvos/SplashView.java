package io.github.starktvos;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * The startup animation, played over the home screen on boot and on wake. It's a 4 s silent clip decoded in
 * hardware; any key skips it and a timeout guarantees it can never keep the home screen hidden.
 */
final class SplashView extends FrameLayout implements TextureView.SurfaceTextureListener {

    interface Listener {
        void onSplashDone();
    }

    private static final long SAFETY_MS = 7000;

    private final TextureView video;
    private final Listener listener;
    private MediaPlayer player;
    private Surface surface;
    private boolean finished, started;
    private final Runnable timeout = this::finish;

    SplashView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setBackgroundColor(Theme.BG);
        video = new TextureView(c);
        video.setAlpha(0f);
        video.setSurfaceTextureListener(this);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        postDelayed(timeout, SAFETY_MS);
    }

    boolean isFinished() {
        return finished;
    }

    boolean hasStarted() {
        return started;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (finished) return;
        surface = new Surface(st);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(getContext(), Uri.parse("android.resource://" + getContext().getPackageName() + "/" + R.raw.stark_boot));
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (finished) return;
                started = true;
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) video.animate().alpha(1f).setDuration(120);
                return false;
            });
            mp.setOnCompletionListener(p -> finish());
            mp.setOnErrorListener((p, what, extra) -> {
                finish();
                return true;
            });
            mp.prepareAsync();
        } catch (IOException | RuntimeException e) {
            finish();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    /** Fades out to the home screen. */
    void finish() {
        if (finished) return;
        finished = true;
        removeCallbacks(timeout);
        animate().alpha(0f).setDuration(380).withEndAction(this::remove);
    }

    /** Gone at once: the activity is leaving the screen. */
    void finishNow() {
        finished = true;
        removeCallbacks(timeout);
        animate().cancel();
        remove();
    }

    private void remove() {
        release();
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return;
        parent.removeView(this);
        listener.onSplashDone();
    }

    private void release() {
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
