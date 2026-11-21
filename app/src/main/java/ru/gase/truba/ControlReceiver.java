package ru.gase.truba;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Кнопки из уведомления и окна картинка-в-картинке. */
public class ControlReceiver extends BroadcastReceiver {
    static final String PLAY = "ru.gase.truba.PLAY";
    static final String PAUSE = "ru.gase.truba.PAUSE";
    static final String NEXT = "ru.gase.truba.NEXT";
    static final String PREV = "ru.gase.truba.PREV";
    static final String REWIND = "ru.gase.truba.REWIND";
    static final String FORWARD = "ru.gase.truba.FORWARD";
    static final String CLOSE = "ru.gase.truba.CLOSE";

    static PendingIntent intent(Context c, String action, int requestCode) {
        Intent i = new Intent(c, ControlReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case PLAY: Ctl.play(); break;
            case PAUSE: Ctl.pause(); break;
            case NEXT: Ctl.next(); break;
            case PREV: Ctl.prev(); break;
            case REWIND: Ctl.seekBy(-Prefs.seekStep(context)); break;
            case FORWARD: Ctl.seekBy(Prefs.seekStep(context)); break;
            case CLOSE:
                Ctl.pause();
                if (Flavor.BACKGROUND) PlaybackService.stop(context);
                break;
            default: break;
        }
    }
}
