package ru.gase.truba;

import android.content.Context;
import android.content.SharedPreferences;

/** Все настройки приложения в одном месте. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static boolean b(Context c, String k, boolean def) { return get(c).getBoolean(k, def); }

    private static String s(Context c, String k, String def) { return get(c).getString(k, def); }

    // ---- Общие
    static boolean adblock(Context c) { return b(c, "adblock", true); }

    /** Скорость воспроизведения по умолчанию. */
    static float speed(Context c) {
        try { return Float.parseFloat(s(c, "speed", "1")); } catch (Exception e) { return 1f; }
    }

    /** Шаг перемотки в секундах (уведомление, PiP, гарнитура). */
    static int seekStep(Context c) {
        try { return Integer.parseInt(s(c, "seek_step", "10")); } catch (Exception e) { return 10; }
    }

    /** Субтитры выключены по умолчанию (в видео и в шортс). */
    static boolean captionsOff(Context c) { return b(c, "captions_off", true); }

    /** Качество шортс: auto, hd1080, hd720, large (480p), medium (360p). */
    static String shortsQuality(Context c) { return s(c, "shorts_quality", "auto"); }

    /** Продолжать видео в мини-плеере после просмотра шортс. */
    static boolean resumeAfterShorts(Context c) { return b(c, "resume_after_shorts", true); }

    /** Кнопка «Назад» в развёрнутом плеере сворачивает его (иначе — назад по видео). */
    static boolean backMinimizes(Context c) { return b(c, "back_minimizes", true); }

    // ---- Фоновый режим (только Android 8+)
    static boolean background(Context c) { return Flavor.BACKGROUND && b(c, "bg", true); }

    static boolean bgScreenOff(Context c) { return b(c, "bg_screen_off", true); }

    static boolean bgNoisy(Context c) { return b(c, "bg_noisy", true); }

    // ---- Картинка в картинке (только Android 8+)
    static boolean pipAuto(Context c) { return Flavor.PIP && b(c, "pip_auto", false); }

    /** Кнопки в окне PiP: "seek" (перемотка) или "track" (пред./след. видео). */
    static String pipActions(Context c) { return s(c, "pip_actions", "seek"); }

    /** Что делать при закрытии окна PiP: "pause" или "background". */
    static String pipClose(Context c) { return s(c, "pip_close", "pause"); }
}
