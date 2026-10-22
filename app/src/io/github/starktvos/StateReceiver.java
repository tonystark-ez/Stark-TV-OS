package io.github.starktvos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives what `./stark` changed on the TV. Only senders holding WRITE_SECURE_SETTINGS (ADB shell) get through. */
public final class StateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.hasExtra("disabled")) Prefs.setDisabledByStark(context, intent.getStringExtra("disabled"));
        if (intent.hasExtra("orig_screensaver")) Prefs.setOriginalScreensaver(context, intent.getStringExtra("orig_screensaver"));
    }
}
