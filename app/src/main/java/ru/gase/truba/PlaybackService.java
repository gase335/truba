package ru.gase.truba;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground-сервис «Воспроизведение»: держит процесс живым в фоне,
 * показывает медиа-уведомление и принимает команды с гарнитуры/экрана блокировки.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;

    static PlaybackService instance;

    private MediaSession session;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus;
    private boolean resumeOnFocusGain;
    private long focusRequestedAt;

    private String metaKey = "";
    private String artUrl = "";
    private Bitmap art;

    // ---------------------------------------------------------------- static API

    static void startIfNeeded(Context c) {
        if (instance != null) return;
        try {
            c.startForegroundService(new Intent(c, PlaybackService.class));
        } catch (Exception ignored) {
            // Android 12+: запуск из фона запрещён — просто пропускаем.
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PlaybackService.class));
    }

    static void onState(PlayerState s) {
        if (instance != null) instance.update(s);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Воспроизведение",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);

        session = new MediaSession(this, "Truba");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Ctl.play(); }
            @Override public void onPause() { Ctl.pause(); }
            @Override public void onStop() { Ctl.pause(); }
            @Override public void onSkipToNext() { Ctl.next(); }
            @Override public void onSkipToPrevious() { Ctl.prev(); }
            @Override public void onFastForward() { Ctl.seekBy(10); }
            @Override public void onRewind() { Ctl.seekBy(-10); }
            @Override public void onSeekTo(long pos) { Ctl.seekTo(pos / 1000.0); }
        });
        session.setSessionActivity(openAppIntent());
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Truba:playback");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Truba:wifi");
            wifiLock.setReferenceCounted(false);
        }
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(noisy, f);

        Notification n = buildNotification(Ctl.state);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        update(Ctl.state);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Ctl.pause();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        instance = null;
        try { unregisterReceiver(noisy); } catch (Exception ignored) {}
        abandonFocus();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- state

    void update(PlayerState s) {
        if (!s.active()) {
            stopSelf();
            return;
        }
        boolean playing = s.playing();

        // Метаданные (обложка грузится отдельно)
        String key = s.title + '\n' + s.artist + '\n' + Math.round(s.duration) + '\n' + s.art;
        if (!key.equals(metaKey)) {
            metaKey = key;
            if (!s.art.equals(artUrl)) {
                artUrl = s.art;
                art = null;
                loadArt(s.art);
            }
            pushMetadata(s);
        }

        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        (long) (s.position * 1000), playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());

        if (playing) {
            if (!wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            requestFocus();
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        }

        try {
            nm.notify(NOTIF_ID, buildNotification(s));
        } catch (Exception ignored) {
        }
    }

    private void pushMetadata(PlayerState s) {
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, s.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, s.artist);
        if (s.duration > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (s.duration * 1000));
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        session.setMetadata(b.build());
    }

    private void loadArt(final String url) {
        if (url == null || url.isEmpty() || !url.startsWith("https://")) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    InputStream in = c.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    in.close();
                    if (bmp == null) return;
                    Ctl.MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (instance != PlaybackService.this || !url.equals(artUrl)) return;
                            art = bmp;
                            PlayerState s = Ctl.state;
                            pushMetadata(s);
                            try { nm.notify(NOTIF_ID, buildNotification(s)); } catch (Exception ignored) {}
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }, "art-loader").start();
    }

    // ---------------------------------------------------------------- notification

    private PendingIntent openAppIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String title, String act, int rc) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), title,
                ControlReceiver.intent(this, act, rc)).build();
    }

    private Notification buildNotification(PlayerState s) {
        boolean playing = s.playing();
        String title = s.title.isEmpty() ? getString(R.string.app_name) : s.title;
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(s.artist)
                .setContentIntent(openAppIntent())
                .setDeleteIntent(ControlReceiver.intent(this, ControlReceiver.CLOSE, 106))
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(R.drawable.ic_prev, "Назад", ControlReceiver.PREV, 101))
                .addAction(playing
                        ? action(R.drawable.ic_pause, "Пауза", ControlReceiver.PAUSE, 102)
                        : action(R.drawable.ic_play, "Играть", ControlReceiver.PLAY, 103))
                .addAction(action(R.drawable.ic_next, "Далее", ControlReceiver.NEXT, 104))
                .addAction(action(R.drawable.ic_close, "Закрыть", ControlReceiver.CLOSE, 105))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    // ---------------------------------------------------------------- audio focus

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_LOSS:
                            hasFocus = false;
                            resumeOnFocusGain = false;
                            // защита от гонки, если кто-то в нашем же процессе запросил фокус
                            if (SystemClock.elapsedRealtime() - focusRequestedAt > 1500) Ctl.pause();
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            hasFocus = false;
                            if (Ctl.state.playing()) {
                                resumeOnFocusGain = true;
                                Ctl.pause();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_GAIN:
                            hasFocus = true;
                            if (resumeOnFocusGain) {
                                resumeOnFocusGain = false;
                                Ctl.play();
                            }
                            break;
                        default:
                            break;
                    }
                }
            };

    private void requestFocus() {
        if (hasFocus || audio == null) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener, Ctl.MAIN)
                    .setWillPauseWhenDucked(false)
                    .build();
        }
        focusRequestedAt = SystemClock.elapsedRealtime();
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audio != null && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // наушники отключены
            Ctl.pause();
        }
    };
}
