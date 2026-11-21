package ru.gase.truba;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Главный экран. Два WebView:
 *   browseWeb — лента, поиск, каналы, шортс;
 *   playerWeb — открытое видео: развёрнуто (EXPANDED) или свёрнуто в мини-плеер (MINI).
 */
public class MainActivity extends Activity {
    static final String HOME = "https://m.youtube.com/";

    private static final int NONE = 0;
    private static final int EXPANDED = 1;
    private static final int MINI = 2;

    private FrameLayout root;
    private YtWebView browseWeb;
    private YtWebView playerWeb;
    private FrameLayout playerBox;
    private View miniTouch;
    private LinearLayout miniInfo;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlay;

    private int mode = NONE;
    private boolean inPip;
    private boolean stopped;
    private boolean pausedByBrowse;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private final Chrome chrome = new Chrome();

    private String injectJs;
    private String scriptTag;
    private String userAgent;

    private volatile boolean adblock = true;
    private volatile boolean background;
    private boolean prefsLoaded;

    private Runnable sleepTask;
    private long sleepAt;

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ctl.activity = new WeakReference<MainActivity>(this);
        Ctl.state = new PlayerState();

        injectJs = readAsset("inject.js");
        scriptTag = "<script>" + injectJs + "</script>";
        adblock = Prefs.adblock(this);
        background = Prefs.background(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F0F0F);
        setContentView(root);

        browseWeb = createWebView(false);
        root.addView(browseWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildPlayerBox();
        setupServiceWorker();
        applyLayout();

        String url = urlFromIntent(getIntent());
        if (url != null && isVideoUrl(Uri.parse(url))) {
            browseWeb.loadUrl(HOME);
            openVideo(url);
        } else {
            browseWeb.loadUrl(url != null ? url : HOME);
        }
        firstLaunch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlFromIntent(intent);
        if (url == null) return;
        hideCustomView(true);
        if (isVideoUrl(Uri.parse(url))) openVideo(url);
        else openBrowse(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopped = false;
        readPrefs();
        browseWeb.onResume();
        if (playerWeb != null) playerWeb.onResume();
        applyLayout();
        updatePip(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        browseJs("pauseAll()");
        if (!background && !isInPictureInPictureMode()) Ctl.pause();
    }

    @Override
    protected void onDestroy() {
        if (Ctl.activity.get() == this) Ctl.activity = new WeakReference<MainActivity>(null);
        cancelSleep();
        if (Flavor.BACKGROUND) PlaybackService.stop(this);
        destroyWebViews();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView(true);
        } else if (mode == EXPANDED) {
            if (!Prefs.backMinimizes(this) && canPlayerGoBack()) playerWeb.goBack();
            else setMode(MINI);
        } else if (browseWeb.canGoBack()) {
            browseWeb.goBack();
        } else if (mode != NONE) {
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }

    private boolean canPlayerGoBack() {
        if (playerWeb == null || !playerWeb.canGoBack()) return false;
        WebBackForwardList list = playerWeb.copyBackForwardList();
        int i = list.getCurrentIndex() - 1;
        if (i < 0) return false;
        String prev = list.getItemAtIndex(i).getUrl();
        return prev != null && !prev.startsWith("about:");
    }

    private void firstLaunch() {
        SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean("hint2_shown", false)) {
            p.edit().putBoolean("hint2_shown", true).apply();
            Toast.makeText(this, "Настройки приложения — шестерёнка в шапке YouTube",
                    Toast.LENGTH_LONG).show();
        }
        if (Flavor.BACKGROUND && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !p.getBoolean("asked_notif", false)) {
            p.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ================================================================ settings

    private void readPrefs() {
        boolean newAdblock = Prefs.adblock(this);
        boolean adblockChanged = prefsLoaded && newAdblock != adblock;
        adblock = newAdblock;
        background = Prefs.background(this);
        prefsLoaded = true;

        if (playerWeb != null) playerWeb.keepAlive = background;
        if (!background && Flavor.BACKGROUND) PlaybackService.stop(this);

        if (adblockChanged) {
            browseWeb.reload();
            if (playerWeb != null && mode != NONE) playerWeb.reload();
        } else {
            String common = "adblock:" + adblock + ",speed:" + Prefs.speed(this);
            browseJs("setCfg({" + common + ",bg:false})");
            runPlayerCommand("setCfg({" + common + ",bg:" + background + "})");
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ================================================================ layout

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private ImageButton iconButton(int icon, String desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(desc);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            b.setBackgroundResource(tv.resourceId);
        } else {
            b.setBackgroundColor(Color.TRANSPARENT);
        }
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        return b;
    }

    private void buildPlayerBox() {
        playerBox = new FrameLayout(this);
        playerBox.setBackgroundColor(Color.BLACK);
        playerBox.setVisibility(View.GONE);
        root.addView(playerBox);

        View.OnClickListener expand = new View.OnClickListener() {
            @Override
            public void onClick(View v) { setMode(EXPANDED); }
        };

        miniTouch = new View(this);
        miniTouch.setOnClickListener(expand);
        playerBox.addView(miniTouch);

        miniInfo = new LinearLayout(this);
        miniInfo.setOrientation(LinearLayout.HORIZONTAL);
        miniInfo.setGravity(Gravity.CENTER_VERTICAL);
        miniInfo.setPadding(dp(12), 0, dp(4), 0);
        miniInfo.setOnClickListener(expand);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        miniTitle = new TextView(this);
        miniTitle.setTextColor(Color.WHITE);
        miniTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = new TextView(this);
        miniArtist.setTextColor(0xFFAAAAAA);
        miniArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(miniTitle);
        texts.addView(miniArtist);
        miniInfo.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        miniPlay = iconButton(R.drawable.ic_play, "Играть/пауза");
        miniPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pausedByBrowse = false;
                if (!Ctl.state.playing()) browseJs("pauseAll()");
                Ctl.toggle();
            }
        });
        miniInfo.addView(miniPlay, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageButton close = iconButton(R.drawable.ic_close, "Закрыть видео");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { closePlayer(); }
        });
        miniInfo.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        playerBox.addView(miniInfo);
    }

    private void ensurePlayer() {
        if (playerWeb != null) return;
        playerWeb = createWebView(true);
        playerBox.addView(playerWeb, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void applyLayout() {
        boolean mini = mode == MINI && !inPip;
        int miniH = dp(64);
        int miniW = Math.round(miniH * 16f / 9f);

        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) browseWeb.getLayoutParams();
        int bottom = mini ? miniH : 0;
        if (blp.bottomMargin != bottom) {
            blp.bottomMargin = bottom;
            browseWeb.setLayoutParams(blp);
        }
        browseWeb.setVisibility(inPip && mode != NONE ? View.INVISIBLE : View.VISIBLE);

        if (mode == NONE || playerWeb == null) {
            playerBox.setVisibility(View.GONE);
            return;
        }
        playerBox.setVisibility(View.VISIBLE);
        playerBox.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                mini ? miniH : ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM));
        playerBox.setBackgroundColor(mini ? 0xFF212121 : Color.BLACK);
        playerBox.setElevation(mini ? dp(8) : 0);

        playerWeb.setLayoutParams(mini
                ? new FrameLayout.LayoutParams(miniW, miniH, Gravity.START | Gravity.CENTER_VERTICAL)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        miniTouch.setLayoutParams(new FrameLayout.LayoutParams(miniW, miniH, Gravity.START));
        miniTouch.setVisibility(mini ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ilp.leftMargin = miniW;
        miniInfo.setLayoutParams(ilp);
        miniInfo.setVisibility(mini ? View.VISIBLE : View.GONE);

        runPlayerCommand("setVideoOnly(" + (mini || inPip) + ")");
    }

    private void setMode(int m) {
        if (m != NONE && playerWeb == null) return;
        mode = m;
        if (m == EXPANDED) {
            browseJs("pauseAll()");
            if (pausedByBrowse && Prefs.resumeAfterShorts(this)) Ctl.play();
            pausedByBrowse = false;
        }
        applyLayout();
        updatePip(false);
    }

    private void updateMini(PlayerState s) {
        miniTitle.setText(s.title.isEmpty() ? "Загрузка…" : s.title);
        miniArtist.setText(s.artist);
        miniPlay.setImageResource(s.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    // ================================================================ navigation between windows

    void openVideo(String url) {
        String u = normalize(Uri.parse(url));
        if (u == null) return;
        ensurePlayer();
        browseJs("pauseAll()");
        pausedByBrowse = false;
        Ctl.state = new PlayerState();
        updateMini(Ctl.state);
        playerWeb.loadUrl(u);
        setMode(EXPANDED);
    }

    void openBrowse(String url) {
        Uri u = Uri.parse(url);
        if (!Net.isAllowedInApp(u)) return;
        if (mode == EXPANDED) setMode(MINI);
        String path = u.getPath();
        if (Net.isYoutubeHost(u.getHost()) && (path == null || path.isEmpty() || path.equals("/"))
                && u.getQuery() == null) {
            return; // логотип/главная — просто показываем ленту
        }
        String n = normalize(u);
        browseWeb.loadUrl(n != null ? n : url);
    }

    private void onBrowsePlay() {
        if (mode != NONE && Ctl.state.playing()) {
            pausedByBrowse = true;
            Ctl.pause();
        }
    }

    private void closePlayer() {
        if (playerWeb != null) {
            runPlayerCommand("pause()");
            playerWeb.loadUrl("about:blank");
        }
        Ctl.state = new PlayerState();
        pausedByBrowse = false;
        if (Flavor.BACKGROUND) PlaybackService.stop(this);
        setMode(NONE);
    }

    private void uiAction(String a) {
        if ("settings".equals(a)) openSettings();
        else if ("minimize".equals(a)) setMode(MINI);
        else if ("menu".equals(a)) showPlayerMenu();
    }

    // ================================================================ player menu

    private void showPlayerMenu() {
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<Runnable> acts = new ArrayList<Runnable>();
        labels.add("Свернуть в мини-плеер");
        acts.add(new Runnable() { public void run() { setMode(MINI); } });
        if (Pip.supported(this)) {
            labels.add("Картинка в картинке");
            acts.add(new Runnable() { public void run() { enterPipNow(); } });
        }
        labels.add("Скорость: " + fmtSpeed(Ctl.state.rate));
        acts.add(new Runnable() { public void run() { showSpeedDialog(); } });
        labels.add(sleepLabel());
        acts.add(new Runnable() { public void run() { showSleepDialog(); } });
        labels.add("Закрыть видео");
        acts.add(new Runnable() { public void run() { closePlayer(); } });
        labels.add("Настройки");
        acts.add(new Runnable() { public void run() { openSettings(); } });

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) { acts.get(which).run(); }
                })
                .show();
    }

    static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    static String fmtSpeed(double r) {
        String s = String.format(Locale.US, "%.2f", r).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',') + "×";
    }

    private void showSpeedDialog() {
        String[] items = new String[SPEEDS.length];
        int checked = 3;
        for (int i = 0; i < SPEEDS.length; i++) {
            items[i] = fmtSpeed(SPEEDS[i]) + (SPEEDS[i] == 1f ? " (обычная)" : "");
            if (Math.abs(SPEEDS[i] - Ctl.state.rate) < 0.01) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Скорость")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        runPlayerCommand(String.format(Locale.US, "setSpeed(%.2f)", SPEEDS[which]));
                        d.dismiss();
                    }
                })
                .show();
    }

    private static final int[] SLEEP_MIN = {0, 10, 15, 30, 45, 60, 90, 120};

    private String sleepLabel() {
        if (sleepTask == null) return "Таймер сна";
        long left = Math.max(1, (sleepAt - SystemClock.elapsedRealtime() + 59999) / 60000);
        return "Таймер сна: осталось " + left + " мин";
    }

    private void showSleepDialog() {
        String[] items = {"Выключить", "10 минут", "15 минут", "30 минут", "45 минут", "1 час", "1,5 часа", "2 часа"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Остановить воспроизведение через")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) { setSleep(SLEEP_MIN[which]); }
                })
                .show();
    }

    private void setSleep(int minutes) {
        cancelSleep();
        if (minutes <= 0) {
            Toast.makeText(this, "Таймер сна выключен", Toast.LENGTH_SHORT).show();
            return;
        }
        long ms = minutes * 60000L;
        sleepAt = SystemClock.elapsedRealtime() + ms;
        sleepTask = new Runnable() {
            @Override
            public void run() {
                sleepTask = null;
                Ctl.pause();
                browseJs("pauseAll()");
                Toast.makeText(MainActivity.this, "Таймер сна: воспроизведение остановлено",
                        Toast.LENGTH_LONG).show();
            }
        };
        Ctl.MAIN.postDelayed(sleepTask, ms);
        Toast.makeText(this, "Остановлю через " + minutes + " мин", Toast.LENGTH_SHORT).show();
    }

    private void cancelSleep() {
        if (sleepTask != null) Ctl.MAIN.removeCallbacks(sleepTask);
        sleepTask = null;
    }

    // ================================================================ WebView

    private YtWebView createWebView(boolean player) {
        YtWebView w = new YtWebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);

        if (userAgent == null) {
            // Убираем пометки WebView — YouTube и Google видят обычный мобильный Chrome.
            userAgent = s.getUserAgentString()
                    .replace("; wv)", ")")
                    .replaceAll("Version/\\d+(\\.\\d+)*\\s*", "");
            Net.userAgent = userAgent;
        }
        s.setUserAgentString(userAgent);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.setBackgroundColor(0xFF0F0F0F);
        w.keepAlive = player && background;
        if (player && Build.VERSION.SDK_INT >= 26) {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect с ПК по USB

        w.addJavascriptInterface(new Bridge(player), "YTXBridge");
        w.setWebViewClient(new Client(player));
        w.setWebChromeClient(chrome);
        return w;
    }

    private void setupServiceWorker() {
        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) {
                    if (adblock && Net.isAd(r.getUrl())) return Net.empty();
                    return null;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void destroyWebViews() {
        if (browseWeb != null) {
            root.removeView(browseWeb);
            browseWeb.destroy();
            browseWeb = null;
        }
        if (playerWeb != null) {
            playerBox.removeView(playerWeb);
            playerWeb.destroy();
            playerWeb = null;
        }
    }

    void runPlayerCommand(String call) {
        if (playerWeb == null) return;
        playerWeb.evaluateJavascript("window.__ytx&&window.__ytx." + call, null);
    }

    private void browseJs(String call) {
        if (browseWeb == null) return;
        browseWeb.evaluateJavascript("window.__ytx&&window.__ytx." + call, null);
    }

    private void injectFallback(WebView v) {
        String url = v.getUrl();
        if (url == null) return;
        if (!Net.isYoutubeHost(Uri.parse(url).getHost())) return;
        v.evaluateJavascript(injectJs, null);
    }

    private final class Client extends WebViewClient {
        private final boolean player;

        Client(boolean player) { this.player = player; }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (!r.isForMainFrame()) return false;
            Uri u = r.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) {
                if ("youtu.be".equalsIgnoreCase(u.getHost())) {
                    String n = normalize(u);
                    if (n != null) openVideo(n);
                    return true;
                }
                if (!Net.isAllowedInApp(u)) {
                    openExternal(u);
                    return true;
                }
                boolean video = isVideoUrl(u);
                if (!player && video) { openVideo(u.toString()); return true; }
                if (player && !video) { openBrowse(u.toString()); return true; }
                return false;
            }
            if (scheme.equals("intent")) {
                // «Открыть в приложении YouTube» — игнорируем, пробуем запасную ссылку
                try {
                    Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null && Net.isAllowedInApp(Uri.parse(fb))) v.loadUrl(fb);
                } catch (Exception ignored) {
                }
                return true;
            }
            if (scheme.equals("vnd.youtube") || scheme.equals("youtube")) return true;
            openExternal(u);
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (Net.isServiceWorkerScript(u)) return Net.notFound();
            if (adblock && Net.isAd(u)) return Net.empty();
            if (r.isForMainFrame() && "GET".equalsIgnoreCase(r.getMethod())
                    && "https".equalsIgnoreCase(u.getScheme()) && Net.isYoutubeHost(u.getHost())) {
                return Net.fetchWithInjection(u.toString(), r.getRequestHeaders(), scriptTag);
            }
            return null;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            injectFallback(v);
        }

        @Override
        public void onPageCommitVisible(WebView v, String url) {
            injectFallback(v);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            injectFallback(v);
            if (player) applyLayout();
            CookieManager.getInstance().flush();
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
            // Система убила процесс отрисовки (например, нехватка памяти) — пересоздаём экран.
            if (browseWeb == v) { root.removeView(browseWeb); browseWeb = null; }
            if (playerWeb == v) { playerBox.removeView(playerWeb); playerWeb = null; }
            v.destroy();
            Ctl.state = new PlayerState();
            if (Flavor.BACKGROUND) PlaybackService.stop(MainActivity.this);
            recreate();
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            view.setBackgroundColor(Color.BLACK);
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setFullscreenBars(true);
            PlayerState s = Ctl.state;
            boolean portraitVideo = s.width > 0 && s.height > 0 && s.width < s.height;
            setRequestedOrientation(portraitVideo
                    ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView(false);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private void hideCustomView(boolean notifyPage) {
        if (customView == null) return;
        View v = customView;
        WebChromeClient.CustomViewCallback cb = customCallback;
        customView = null;
        customCallback = null;
        root.removeView(v);
        setFullscreenBars(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (notifyPage && cb != null) cb.onCustomViewHidden();
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenBars(boolean on) {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = on
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(on
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : 0);
        }
    }

    // ================================================================ JS bridge

    /** Мост для страницы. У каждого WebView свой экземпляр: role = player или browse. */
    private final class Bridge {
        private final boolean player;

        Bridge(boolean player) { this.player = player; }

        @JavascriptInterface
        public String config() {
            return "{\"role\":\"" + (player ? "player" : "browse") + "\""
                    + ",\"adblock\":" + adblock
                    + ",\"bg\":" + (player && background)
                    + ",\"speed\":" + Prefs.speed(MainActivity.this) + "}";
        }

        @JavascriptInterface
        public void onState(String json) {
            if (!player) return;
            final PlayerState s = PlayerState.parse(json);
            post(new Runnable() { public void run() { handleState(s); } });
        }

        @JavascriptInterface
        public void openVideo(final String url) {
            post(new Runnable() { public void run() { MainActivity.this.openVideo(url); } });
        }

        @JavascriptInterface
        public void openBrowse(final String url) {
            if (!player) return;
            post(new Runnable() { public void run() { MainActivity.this.openBrowse(url); } });
        }

        @JavascriptInterface
        public void onBrowsePlay(String ignored) {
            if (player) return;
            post(new Runnable() { public void run() { MainActivity.this.onBrowsePlay(); } });
        }

        @JavascriptInterface
        public void uiAction(final String action) {
            post(new Runnable() { public void run() { MainActivity.this.uiAction(action); } });
        }

        private void post(Runnable r) { Ctl.MAIN.post(r); }
    }

    private void handleState(PlayerState s) {
        if (mode == NONE) return; // запоздалый отчёт после закрытия плеера
        Ctl.state = s;
        updateMini(s);
        if (background) {
            if (s.playing()) PlaybackService.startIfNeeded(this);
            PlaybackService.onState(s);
        }
        updatePip(false);
    }

    // ================================================================ Picture-in-Picture

    private boolean autoEnterWanted() {
        return Prefs.pipAuto(this) && mode != NONE && Ctl.state.playing();
    }

    private void updatePip(boolean force) {
        if (!Pip.supported(this)) return;
        Pip.update(this, Ctl.state, autoEnterWanted(), force);
    }

    private void enterPipNow() {
        if (mode == NONE || !Pip.supported(this)) return;
        hideCustomView(true);
        runPlayerCommand("setVideoOnly(true)");
        Pip.enter(this, Ctl.state);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!Pip.supported(this) || isInPictureInPictureMode() || !autoEnterWanted()) return;
        runPlayerCommand("setVideoOnly(true)");
        if (Build.VERSION.SDK_INT < 31) Pip.enter(this, Ctl.state);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        inPip = isInPip;
        if (!isInPip) {
            if (stopped) {
                // окно PiP закрыли
                if (!("background".equals(Prefs.pipClose(this)) && background)) Ctl.pause();
            } else if (mode == MINI) {
                mode = EXPANDED; // вернулись из окна — разворачиваем видео
            }
        }
        applyLayout();
        updatePip(true);
    }

    // ================================================================ helpers

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    static boolean isVideoUrl(Uri u) {
        if (!Net.isYoutubeHost(u.getHost())) return false;
        String p = u.getPath();
        return p != null && (p.equals("/watch") || p.startsWith("/live/"));
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    static String urlFromIntent(Intent i) {
        if (i == null || i.getAction() == null) return null;
        String raw = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            raw = i.getData().toString();
        } else if (Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                Matcher m = URL_IN_TEXT.matcher(t);
                if (m.find()) raw = m.group();
            }
        }
        if (raw == null) return null;
        return normalize(Uri.parse(raw));
    }

    /** Приводит ссылку YouTube к m.youtube.com; youtu.be/ID → /watch?v=ID. Чужие ссылки → null. */
    static String normalize(Uri u) {
        String host = u.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("youtu.be")) {
            String id = u.getLastPathSegment();
            if (id == null) return HOME;
            Uri.Builder b = Uri.parse("https://m.youtube.com/watch").buildUpon().appendQueryParameter("v", id);
            String t = u.getQueryParameter("t");
            if (t != null) b.appendQueryParameter("t", t);
            String list = u.getQueryParameter("list");
            if (list != null) b.appendQueryParameter("list", list);
            return b.build().toString();
        }
        if (host.equals("youtube.com") || host.equals("www.youtube.com")) {
            return u.buildUpon().scheme("https").encodedAuthority("m.youtube.com").build().toString();
        }
        if (Net.isYoutubeHost(host)) return u.buildUpon().scheme("https").build().toString();
        return null;
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
