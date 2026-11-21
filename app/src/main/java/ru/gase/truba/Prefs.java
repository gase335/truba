package ru.gase.truba;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Фоновое воспроизведение (по умолчанию включено). */
    static boolean background(Context c) { return get(c).getBoolean("bg", true); }

    /** Картинка-в-картинке при сворачивании (по умолчанию выключено). */
    static boolean pip(Context c) { return get(c).getBoolean("pip", false); }

    /** Блокировка рекламы (по умолчанию включена). */
    static boolean adblock(Context c) { return get(c).getBoolean("adblock", true); }
}
