package io.github.starktvos;

import android.app.Application;

/** Loads the display font and records crashes, so a crash loop drops the launcher into safe mode. */
public final class StarkApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Theme.init(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Prefs.recordCrash(this);
            } catch (RuntimeException ignored) {
                // never mask the original crash
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}
