package ru.gase.truba;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView, который в фоновом режиме «не замечает», что окно скрыто.
 * Благодаря этому Chromium не ставит страницу на паузу и не душит таймеры.
 */
public class YtWebView extends WebView {
    volatile boolean keepAlive = true;

    public YtWebView(Context context) {
        super(context);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (keepAlive && visibility != View.VISIBLE) return;
        super.onWindowVisibilityChanged(visibility);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        if (keepAlive && visibility != View.VISIBLE) return;
        super.onVisibilityChanged(changedView, visibility);
    }
}
