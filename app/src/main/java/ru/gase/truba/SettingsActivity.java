package ru.gase.truba;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** Экран настроек, собранный кодом (без XML-разметки). */
public class SettingsActivity extends Activity {
    private static final int ACCENT = 0xFFFF3B30;

    private LinearLayout list;
    private SharedPreferences prefs;
    /** Пункты, которые активны только при включённом фоновом режиме. */
    private final ArrayList<View> bgDependents = new ArrayList<View>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(list);
        setContentView(scroll);

        // ------------------------------------------------ Общие
        section("Общие");
        toggle("adblock", true, "Блокировка рекламы",
                "Вырезает рекламу из видео и ленты, блокирует рекламные и трекинговые запросы. "
                        + "При изменении страницы перезагрузятся.", null);
        choice("speed", "1", "Скорость по умолчанию",
                new String[]{"0,75×", "1× (обычная)", "1,25×", "1,5×", "1,75×", "2×"},
                new String[]{"0.75", "1", "1.25", "1.5", "1.75", "2"});
        choice("seek_step", "10", "Шаг перемотки",
                new String[]{"5 секунд", "10 секунд", "15 секунд", "30 секунд"},
                new String[]{"5", "10", "15", "30"});
        toggle("resume_after_shorts", true, "Продолжать видео после шортс",
                "Когда смотрите шортс, видео в мини-плеере ставится на паузу. "
                        + "При разворачивании мини-плеера оно продолжится с того же места.", null);
        toggle("back_minimizes", true, "«Назад» сворачивает видео",
                "Кнопка «Назад» в открытом видео сворачивает его в мини-плеер. "
                        + "Если выключить — возвращает к предыдущему видео.", null);

        // ------------------------------------------------ Фон
        if (Flavor.BACKGROUND) {
            section("Фоновое воспроизведение");
            toggle("bg", true, "Фоновое воспроизведение",
                    "Звук продолжает играть, когда приложение свёрнуто. Управление — из уведомления, "
                            + "с экрана блокировки и кнопками гарнитуры.",
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b, boolean on) { refreshBgDependents(on); }
                    });
            bgDependents.add(toggle("bg_screen_off", true, "Играть при выключенном экране",
                    "Если выключить — воспроизведение остановится при блокировке экрана, "
                            + "но продолжится при сворачивании.", null));
            bgDependents.add(toggle("bg_noisy", true, "Пауза при отключении наушников",
                    "Чтобы звук внезапно не пошёл в динамик.", null));
            bgDependents.add(toggle("bg_focus", true, "Пауза при звонках и других звуках",
                    "Останавливается, когда звонят или включается музыка в другом приложении; "
                            + "после короткого прерывания продолжает сама.", null));
            refreshBgDependents(prefs.getBoolean("bg", true));
        }

        // ------------------------------------------------ PiP
        if (Flavor.PIP) {
            section("Картинка в картинке");
            boolean supported = Build.VERSION.SDK_INT >= 26
                    && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
            if (!supported) {
                note("Это устройство не поддерживает режим «картинка в картинке».");
            } else {
                toggle("pip_auto", false, "Автоматически при сворачивании",
                        "Если видео играет, при выходе на рабочий стол оно уходит в маленькое окно. "
                                + "Вручную — через меню ⋮ в плеере.", null);
                choice("pip_actions", "seek", "Кнопки в окне",
                        new String[]{"Перемотка назад / вперёд", "Предыдущее / следующее видео"},
                        new String[]{"seek", "track"});
                choice("pip_close", "pause", "При закрытии окна",
                        new String[]{"Остановить видео", "Продолжить в фоне (звук)"},
                        new String[]{"pause", "background"});
            }
        }

        // ------------------------------------------------ Аккаунт
        section("Данные");
        action("Выйти из аккаунта и очистить данные", "Удаляет cookies, кэш и сохранённые данные сайтов.",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { confirmClear(); }
                });

        TextView about = new TextView(this);
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        about.setText(getString(R.string.app_name) + " " + version
                + (Flavor.LITE ? " · версия для Android 7" : " · версия для Android 8+")
                + "\nОболочка m.youtube.com на системном WebView (Chromium)");
        about.setTextColor(Color.GRAY);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(24), 0, 0);
        list.addView(about);
    }

    // ================================================================ builders

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(20), 0, dp(4));
        list.addView(t);
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(8), 0, dp(8));
        list.addView(t);
    }

    private View toggle(final String key, boolean def, String title, String desc,
                        final CompoundButton.OnCheckedChangeListener extra) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setTextColor(Color.WHITE);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
                if (extra != null) extra.onCheckedChanged(b, checked);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(0xFFAAAAAA);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        d.setPadding(0, dp(2), dp(56), 0);
        row.addView(d);
        list.addView(row);
        return row;
    }

    private void choice(final String key, final String def, final String title,
                        final String[] labels, final String[] values) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        clickable(row);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(t);

        final TextView v = new TextView(this);
        v.setTextColor(0xFFAAAAAA);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setText(labels[indexOf(values, prefs.getString(key, def))]);
        row.addView(v);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int checked = indexOf(values, prefs.getString(key, def));
                new AlertDialog.Builder(SettingsActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(title)
                        .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                prefs.edit().putString(key, values[which]).apply();
                                v.setText(labels[which]);
                                d.dismiss();
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            }
        });
        list.addView(row);
    }

    private void action(String title, String desc, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        clickable(row);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(0xFFAAAAAA);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(d);
        row.setOnClickListener(click);
        list.addView(row);
    }

    private void clickable(View v) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    private void refreshBgDependents(boolean on) {
        for (View row : bgDependents) setEnabledDeep(row, on);
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setEnabled(on);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Очистить данные?")
                .setMessage("Вы выйдете из аккаунта Google, история и настройки сайта YouTube будут удалены.")
                .setPositiveButton("Очистить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                        new WebView(SettingsActivity.this).clearCache(true);
                        Toast.makeText(SettingsActivity.this, "Готово. Перезапустите приложение.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
