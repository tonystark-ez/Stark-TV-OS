package io.github.starktvos;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** HDMI and AV inputs, through Android's TV input framework. */
final class Inputs {
    static final class Input {
        final String id;
        final String label;
        final int type;

        Input(String id, String label, int type) {
            this.id = id;
            this.label = label;
            this.type = type;
        }
    }

    private Inputs() {}

    static List<Input> list(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.isHidden(c)) continue;
                CharSequence l = info.loadCustomLabel(c);
                if (l == null || l.length() == 0) l = info.loadLabel(c);
                out.add(new Input(info.getId(), l == null ? "Input" : l.toString(), info.getType()));
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    static boolean open(Context c, Input in) {
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
