package ru.gase.truba;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сетевой уровень: блок-лист и внедрение скрипта в HTML до запуска скриптов YouTube. */
final class Net {
    private Net() {}

    static volatile String userAgent;

    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "google-analytics.com", "googletagservices.com", "googletagmanager.com",
            "imasdk.googleapis.com", "adservice.google.com", "ads.youtube.com",
            "pagead2.googlesyndication.com", "static.doubleclick.net", "app-measurement.com"
    };

    private static final String[] AD_PATHS = {
            "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/pcs/activeview",
            "/youtubei/v1/player/ad_break", "/ad_data_204"
    };

    static boolean isYoutubeHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("youtube.com") || host.endsWith(".youtube.com");
    }

    static boolean isAllowedInApp(Uri u) {
        String host = u.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return isYoutubeHost(host) || host.equals("youtu.be")
                || host.equals("accounts.google.com") || host.endsWith(".google.com")
                || host.equals("google.com") || host.endsWith(".google.ru")
                || host.equals("accounts.youtube.com") || host.endsWith(".gstatic.com")
                || host.endsWith(".googleusercontent.com");
    }

    static boolean isAd(Uri u) {
        String host = u.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String h : AD_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        if (isYoutubeHost(host) || host.endsWith(".googlevideo.com")) {
            String path = u.getPath();
            if (path != null) {
                for (String p : AD_PATHS) if (path.startsWith(p)) return true;
            }
        }
        return false;
    }

    /** Service worker YouTube перехватывает навигацию и мешает внедрению — не даём ему зарегистрироваться. */
    static boolean isServiceWorkerScript(Uri u) {
        String path = u.getPath();
        return isYoutubeHost(u.getHost()) && path != null
                && (path.equals("/sw.js") || path.equals("/sw.js_data"));
    }

    static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    static WebResourceResponse notFound() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h,
                new ByteArrayInputStream(new byte[0]));
    }

    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Загружает HTML-страницу YouTube сами и вставляет наш скрипт в начало &lt;head&gt;,
     * чтобы он выполнился раньше скриптов YouTube. При любой проблеме возвращает null —
     * тогда WebView загрузит страницу как обычно (скрипт внедрится позже, запасным путём).
     */
    static WebResourceResponse fetchWithInjection(String url, Map<String, String> reqHeaders, String scriptTag) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (lk.equals("accept-encoding") || lk.equals("cookie") || lk.equals("x-requested-with")) continue;
                    c.setRequestProperty(k, e.getValue());
                }
            }
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            CookieManager cm = CookieManager.getInstance();
            String cookies = cm.getCookie(url);
            if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);

            int code = c.getResponseCode();
            Map<String, List<String>> headers = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String v : e.getValue()) cm.setCookie(url, v);
                }
            }
            cm.flush();
            if (code != 200) return null;

            String ct = c.getContentType();
            if (ct == null || !ct.toLowerCase(Locale.ROOT).contains("text/html")) return null;
            String charset = "UTF-8";
            Matcher cm2 = CHARSET.matcher(ct);
            if (cm2.find()) charset = cm2.group(1);

            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String html = bos.toString(charset);

            Matcher m = HEAD.matcher(html);
            int pos = m.find() ? m.end() : 0;
            html = html.substring(0, pos) + scriptTag + html.substring(pos);

            Map<String, String> out = new HashMap<String, String>();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.startsWith("content-security-policy") || lk.equals("content-encoding")
                        || lk.equals("content-length") || lk.equals("transfer-encoding")
                        || lk.equals("set-cookie") || lk.equals("content-type")
                        || lk.startsWith("x-android")) continue;
                StringBuilder sb = new StringBuilder();
                for (String v : e.getValue()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(v);
                }
                out.put(k, sb.toString());
            }
            return new WebResourceResponse("text/html", charset, 200, "OK", out,
                    new ByteArrayInputStream(html.getBytes(charset)));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
