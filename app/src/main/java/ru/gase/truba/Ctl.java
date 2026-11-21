package ru.gase.truba;

import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.Locale;

/** Общая точка управления плеером: уведомление, PiP, гарнитура → JS на странице. */
final class Ctl {
    private Ctl() {}

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static WeakReference<MainActivity> activity = new WeakReference<MainActivity>(null);
    static volatile PlayerState state = new PlayerState();

    static void cmd(final String call) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                MainActivity a = activity.get();
                if (a != null) a.runPlayerCommand(call);
            }
        });
    }

    static void play() { cmd("play()"); }

    static void pause() { cmd("pause()"); }

    static void toggle() { cmd("toggle()"); }

    static void next() { cmd("next()"); }

    static void prev() { cmd("prev()"); }

    static void seekBy(double s) { cmd(String.format(Locale.US, "seekBy(%.3f)", s)); }

    static void seekTo(double s) { cmd(String.format(Locale.US, "seekTo(%.3f)", s)); }
}
