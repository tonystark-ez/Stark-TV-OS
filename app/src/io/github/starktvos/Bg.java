package io.github.starktvos;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Worker threads, so the UI thread only ever draws. {@link #run} is for slow work (speed tests, cleaning, tiles);
 * {@link #quick} has its own thread for short loads a screen is waiting on, so they never queue behind slow work.
 */
final class Bg {
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> thread(r, "stark-bg",
            Process.THREAD_PRIORITY_BACKGROUND));
    private static final ExecutorService QUICK = Executors.newSingleThreadExecutor(r -> thread(r, "stark-quick",
            Process.THREAD_PRIORITY_DEFAULT));

    private Bg() {}

    private static Thread thread(Runnable r, String name, int priority) {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(priority);
            r.run();
        }, name);
        t.setDaemon(true);
        return t;
    }

    static void run(Runnable r) {
        POOL.execute(guard(r));
    }

    static void quick(Runnable r) {
        QUICK.execute(guard(r));
    }

    private static Runnable guard(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                android.util.Log.w("StarkTV", "background task failed", e);
            }
        };
    }

    static void ui(Runnable r) {
        MAIN.post(r);
    }
}
