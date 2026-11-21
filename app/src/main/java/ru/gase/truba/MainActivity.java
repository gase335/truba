package ru.gase.truba;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com/";

    private FrameLayout root;
    private YtWebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private String injectJs;
    private String scriptTag;

    private volatile boolean adblock = true;
    private volatile boolean background = true;
    private boolean pipPref;
    private boolean prefsLoaded;

    private boolean stopped;
    private boolean autoEnterArmed;
    private String pipKey = "";

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ctl.activity = new WeakReference<MainActivity>(this);
        Ctl.state = new PlayerState();

        injectJs = readAsset("inject.js");
        scriptTag = "<script>" + injectJs + "</script>";

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F0F0F);
        setContentView(root);
        createWebView();
        readPrefs();

        String url = urlFromIntent(getIntent());
        web.loadUrl(url != null ? url : HOME);

        firstLaunchHints();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null && web != null) {
            hideCustomView(true);
            web.loadUrl(url);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        stopped = false;
        readPrefs();
        if (web != null) web.onResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        if (!background && !isInPictureInPictureMode()) Ctl.pause();
    }

    @Override
    protected void onDestroy() {
        if (Ctl.activity.get() == this) Ctl.activity = new WeakReference<MainActivity>(null);
        PlaybackService.stop(this);
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView(true);
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else if (background && Ctl.state.active()) {
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================ settings

    private void readPrefs() {
        boolean newAdblock = Prefs.adblock(this);
        boolean adblockChanged = prefsLoaded && newAdblock != adblock;
        adblock = newAdblock;
        background = Prefs.background(this);
        pipPref = Prefs.pip(this);
        prefsLoaded = true;

        if (web != null) web.keepAlive = background;
        if (!background) PlaybackService.stop(this);

        if (adblockChanged && web != null) {
            web.reload();
        } else {
            runPlayerCommand("setCfg({adblock:" + adblock + ",bg:" + background + "})");
        }
        pipKey = "";
        updatePipParams();
    }

    private void firstLaunchHints() {
        android.content.SharedPreferences p = Prefs.get(this);
        if (!p.getBoolean("hint_shown", false)) {
            p.edit().putBoolean("hint_shown", true).apply();
            Toast.makeText(this, "Настройки: удерживайте иконку приложения → «Настройки»",
                    Toast.LENGTH_LONG).show();
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !p.getBoolean("asked_notif", false)) {
            p.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    // ================================================================ WebView

    private void createWebView() {
        web = new YtWebView(this);
        root.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
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

        // Убираем пометки WebView из User-Agent — YouTube и Google видят обычный мобильный Chrome.
        String ua = s.getUserAgentString()
                .replace("; wv)", ")")
                .replaceAll("Version/\\d+(\\.\\d+)*\\s*", "");
        s.setUserAgentString(ua);
        Net.userAgent = ua;

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setBackgroundColor(0xFF0F0F0F);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.keepAlive = background;
        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect с ПК по USB

        web.addJavascriptInterface(new Bridge(), "YTXBridge");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        try {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) {
                    if (adblock && Net.isAd(r.getUrl())) return Net.empty();
                    return null;
                }
            });
        } catch (Exception ignored) {
        }
    }

    void runPlayerCommand(String call) {
        if (web == null) return;
        web.evaluateJavascript("window.__ytx&&window.__ytx." + call, null);
    }

    private void injectFallback(WebView v) {
        String url = v.getUrl();
        if (url == null) return;
        if (!Net.isYoutubeHost(Uri.parse(url).getHost())) return;
        v.evaluateJavascript(injectJs, null);
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (!r.isForMainFrame()) return false;
            Uri u = r.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.equals("http") || scheme.equals("https")) {
                if ("youtu.be".equalsIgnoreCase(u.getHost())) {
                    String n = normalize(u);
                    if (n != null) { v.loadUrl(n); return true; }
                }
                if (Net.isAllowedInApp(u)) return false;
                openExternal(u);
                return true;
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
            CookieManager.getInstance().flush();
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
            // Система убила процесс отрисовки (например, нехватка памяти) — пересоздаём экран.
            if (web == v) {
                root.removeView(web);
                web.destroy();
                web = null;
            }
            Ctl.state = new PlayerState();
            PlaybackService.stop(MainActivity.this);
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

    private final class Bridge {
        @JavascriptInterface
        public String config() {
            return "{\"adblock\":" + adblock + ",\"bg\":" + background + "}";
        }

        @JavascriptInterface
        public void onState(String json) {
            final PlayerState s = PlayerState.parse(json);
            Ctl.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    handleState(s);
                }
            });
        }
    }

    private void handleState(PlayerState s) {
        Ctl.state = s;
        if (background) {
            if (s.playing()) PlaybackService.startIfNeeded(this);
            PlaybackService.onState(s);
        }
        updatePipParams();
    }

    // ================================================================ Picture-in-Picture

    private boolean supportsPip() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private RemoteAction remote(int icon, String title, String action, int rc) {
        return new RemoteAction(Icon.createWithResource(this, icon), title, title,
                ControlReceiver.intent(this, action, rc));
    }

    private PictureInPictureParams buildPipParams() {
        PlayerState s = Ctl.state;
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
        actions.add(remote(R.drawable.ic_rewind, "−10 с", ControlReceiver.REWIND, 201));
        actions.add(s.playing()
                ? remote(R.drawable.ic_pause, "Пауза", ControlReceiver.PAUSE, 202)
                : remote(R.drawable.ic_play, "Играть", ControlReceiver.PLAY, 203));
        actions.add(remote(R.drawable.ic_forward, "+10 с", ControlReceiver.FORWARD, 204));
        b.setActions(actions);
        if (Build.VERSION.SDK_INT >= 31) {
            autoEnterArmed = pipPref && s.playing();
            b.setAutoEnterEnabled(autoEnterArmed);
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    private void updatePipParams() {
        if (!supportsPip()) return;
        PlayerState s = Ctl.state;
        String key = s.playing() + "|" + s.width + "x" + s.height + "|" + pipPref;
        if (key.equals(pipKey)) return;
        pipKey = key;
        try {
            setPictureInPictureParams(buildPipParams());
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!pipPref || !supportsPip() || isInPictureInPictureMode()) return;
        if (!Ctl.state.playing()) return;
        runPlayerCommand("setPip(true)");
        if (Build.VERSION.SDK_INT < 31 || !autoEnterArmed) {
            try {
                enterPictureInPictureMode(buildPipParams());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        runPlayerCommand("setPip(" + isInPip + ")");
        if (!isInPip && stopped) {
            // окно PiP закрыли крестиком/смахиванием — останавливаем
            Ctl.pause();
        }
    }

    // ================================================================ helpers

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
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
