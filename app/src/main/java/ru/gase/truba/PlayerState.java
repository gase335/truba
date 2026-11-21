package ru.gase.truba;

import org.json.JSONObject;

/** Снимок состояния плеера на странице (приходит из inject.js). */
final class PlayerState {
    String state = "none";
    String title = "";
    String artist = "";
    String art = "";
    String id = "";
    boolean ad;
    double duration;
    double position;
    double rate = 1;
    int width;
    int height;
    /** Прямоугольник видео на странице (CSS px) и ширина окна страницы. */
    int[] videoRect = {0, 0, 0, 0};
    int viewportWidth;
    long receivedAt = android.os.SystemClock.elapsedRealtime();

    boolean playing() { return "playing".equals(state); }

    boolean active() { return !"none".equals(state); }

    static PlayerState parse(String json) {
        PlayerState s = new PlayerState();
        try {
            JSONObject o = new JSONObject(json);
            s.state = o.optString("state", "none");
            s.title = o.optString("title", "");
            s.artist = o.optString("artist", "");
            s.art = o.optString("art", "");
            s.id = o.optString("id", "");
            s.ad = o.optBoolean("ad", false);
            s.duration = o.optDouble("dur", 0);
            s.position = o.optDouble("pos", 0);
            s.rate = o.optDouble("rate", 1);
            if (Double.isNaN(s.rate) || s.rate <= 0) s.rate = 1;
            s.width = o.optInt("w", 0);
            s.height = o.optInt("h", 0);
            org.json.JSONArray vr = o.optJSONArray("vr");
            if (vr != null && vr.length() == 4) {
                for (int i = 0; i < 4; i++) s.videoRect[i] = vr.optInt(i, 0);
            }
            s.viewportWidth = o.optInt("vw", 0);
            if (Double.isNaN(s.duration) || Double.isInfinite(s.duration)) s.duration = 0;
            if (Double.isNaN(s.position) || Double.isInfinite(s.position)) s.position = 0;
        } catch (Exception ignored) {
        }
        return s;
    }
}
