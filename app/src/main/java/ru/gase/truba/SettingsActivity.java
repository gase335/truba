package ru.gase.truba;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(false);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);

        addSwitch("bg", true, "Фоновое воспроизведение",
                "Видео продолжает играть (звук), когда приложение свёрнуто или экран выключен. "
                        + "Управление — из уведомления, с экрана блокировки и кнопками гарнитуры.");
        addSwitch("pip", false, "Картинка в картинке",
                "При сворачивании во время воспроизведения видео уходит в маленькое окно поверх "
                        + "других приложений. Если выключено — работает обычный фоновый режим.");
        addSwitch("adblock", true, "Блокировка рекламы",
                "Вырезает рекламу из видео и ленты, блокирует рекламные и трекинговые запросы. "
                        + "При изменении страница перезагрузится.");

        Button clear = new Button(this);
        clear.setText("Выйти из аккаунта и очистить данные");
        clear.setAllCaps(false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CookieManager.getInstance().removeAllCookies(null);
                CookieManager.getInstance().flush();
                WebStorage.getInstance().deleteAllData();
                new WebView(SettingsActivity.this).clearCache(true);
                Toast.makeText(SettingsActivity.this, "Готово. Перезапустите приложение.",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        list.addView(clear, lp);

        TextView about = new TextView(this);
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        about.setText("Труба " + version + "\nОболочка m.youtube.com на системном WebView (Chromium).");
        about.setTextColor(Color.GRAY);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        about.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(24);
        list.addView(about, lp2);
    }

    private void addSwitch(final String key, boolean def, String title, String desc) {
        final SharedPreferences p = Prefs.get(this);
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        sw.setTextColor(Color.WHITE);
        sw.setChecked(p.getBoolean(key, def));
        sw.setPadding(0, dp(12), 0, dp(4));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                p.edit().putBoolean(key, checked).apply();
            }
        });
        list.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(0xFFAAAAAA);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        d.setPadding(0, 0, dp(56), dp(12));
        list.addView(d);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
