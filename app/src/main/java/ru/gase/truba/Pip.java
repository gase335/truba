package ru.gase.truba;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Rational;

import java.util.ArrayList;

/**
 * Картинка-в-картинке (Android 8+). Вынесено в отдельный класс, чтобы на Android 7
 * эти API даже не загружались.
 */
final class Pip {
    private Pip() {}

    private static String lastKey = "";

    static boolean supported(Activity a) {
        return Flavor.PIP && Build.VERSION.SDK_INT >= 26
                && a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private static RemoteAction remote(Context c, int icon, String title, String action, int rc) {
        return new RemoteAction(Icon.createWithResource(c, icon), title, title,
                ControlReceiver.intent(c, action, rc));
    }

    static PictureInPictureParams build(Activity a, PlayerState s, boolean autoEnter) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        Rational ratio = new Rational(16, 9);
        if (s.width > 0 && s.height > 0) {
            double r = (double) s.width / s.height;
            if (r > 2.38) ratio = new Rational(238, 100);
            else if (r < 1 / 2.38) ratio = new Rational(100, 238);
            else ratio = new Rational(s.width, s.height);
        }
        b.setAspectRatio(ratio);

        ArrayList<RemoteAction> actions = new ArrayList<RemoteAction>();
        boolean track = "track".equals(Prefs.pipActions(a));
        int step = Prefs.seekStep(a);
        if (track) actions.add(remote(a, R.drawable.ic_prev, "Назад", ControlReceiver.PREV, 211));
        else actions.add(remote(a, R.drawable.ic_rewind, "−" + step + " с", ControlReceiver.REWIND, 201));
        actions.add(s.playing()
                ? remote(a, R.drawable.ic_pause, "Пауза", ControlReceiver.PAUSE, 202)
                : remote(a, R.drawable.ic_play, "Играть", ControlReceiver.PLAY, 203));
        if (track) actions.add(remote(a, R.drawable.ic_next, "Далее", ControlReceiver.NEXT, 214));
        else actions.add(remote(a, R.drawable.ic_forward, "+" + step + " с", ControlReceiver.FORWARD, 204));
        b.setActions(actions);

        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(autoEnter);
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    /** Обновляет параметры окна (кнопки, пропорции, автовход), только если что-то изменилось. */
    static void update(Activity a, PlayerState s, boolean autoEnter, boolean force) {
        if (!supported(a)) return;
        String key = s.playing() + "|" + s.width + "x" + s.height + "|" + autoEnter + "|"
                + Prefs.pipActions(a) + "|" + Prefs.seekStep(a);
        if (!force && key.equals(lastKey)) return;
        lastKey = key;
        try {
            a.setPictureInPictureParams(build(a, s, autoEnter));
        } catch (Exception ignored) {
        }
    }

    static boolean enter(Activity a, PlayerState s) {
        if (!supported(a)) return false;
        try {
            lastKey = "";
            return a.enterPictureInPictureMode(build(a, s, Prefs.pipAuto(a) && s.playing()));
        } catch (Exception e) {
            return false;
        }
    }
}
