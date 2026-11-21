/*
 * Труба — скрипт, внедряемый в страницы YouTube.
 *  1. Вырезает рекламу из ответов YouTube (adPlacements / playerAds / adSlots и рекламные карточки).
 *  2. Пропускает видеорекламу, если она всё-таки проскочила.
 *  3. Притворяется, что страница всегда видима (для фонового воспроизведения).
 *  4. Сообщает приложению состояние плеера и принимает команды (play/pause/next/...).
 *  5. Раскладка «только видео» для режима картинка-в-картинке.
 */
(function () {
  'use strict';
  if (window.__ytx) return;

  var bridge = window.YTXBridge;
  var nativeParse = JSON.parse;
  var cfg = { adblock: true, bg: true };
  try { if (bridge) cfg = nativeParse(bridge.config()); } catch (e) {}

  var api = (window.__ytx = { cfg: cfg });

  /* ------------------------------------------------------------------ */
  /* 1. Вырезание рекламы из данных                                      */
  /* ------------------------------------------------------------------ */
  var PRUNE_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams',
    'adBreakParams', 'enforcementMessageViewModel'];
  var AD_RENDERERS = {
    adSlotRenderer: 1, promotedSparklesWebRenderer: 1, promotedSparklesTextSearchRenderer: 1,
    promotedVideoRenderer: 1, compactPromotedVideoRenderer: 1, statementBannerRenderer: 1,
    brandVideoShelfRenderer: 1, brandVideoSingletonRenderer: 1, bannerPromoRenderer: 1,
    mealbarPromoRenderer: 1, searchPyvRenderer: 1, inFeedAdLayoutRenderer: 1
  };
  var MARK = /"(adPlacements|adSlots|playerAds|adSlotRenderer|promoted[A-Za-z]*Renderer|statementBannerRenderer|enforcementMessageViewModel|bannerPromoRenderer|mealbarPromoRenderer|searchPyvRenderer)"/;

  function hasAdKey(o) {
    if (!o || typeof o !== 'object') return false;
    for (var k in o) if (AD_RENDERERS[k]) return true;
    return false;
  }
  function isAdItem(x) {
    if (!x || typeof x !== 'object' || Array.isArray(x)) return false;
    if (hasAdKey(x)) return true;
    if (x.richItemRenderer && hasAdKey(x.richItemRenderer.content)) return true;
    var isr = x.itemSectionRenderer;
    if (isr && Array.isArray(isr.contents) && isr.contents.length > 0 &&
        isr.contents.every(hasAdKey)) return true;
    return false;
  }
  function deepClean(o, depth) {
    if (!o || typeof o !== 'object' || depth > 60) return;
    if (Array.isArray(o)) {
      for (var i = o.length - 1; i >= 0; i--) {
        if (isAdItem(o[i])) o.splice(i, 1);
        else deepClean(o[i], depth + 1);
      }
      return;
    }
    for (var j = 0; j < PRUNE_KEYS.length; j++) {
      if (Object.prototype.hasOwnProperty.call(o, PRUNE_KEYS[j])) delete o[PRUNE_KEYS[j]];
    }
    for (var k in o) {
      var v = o[k];
      if (v && typeof v === 'object') deepClean(v, depth + 1);
    }
  }
  api.clean = function (o) { try { deepClean(o, 0); } catch (e) {} return o; };

  function trap(name) {
    var val = window[name];
    if (val && typeof val === 'object') api.clean(val);
    try {
      Object.defineProperty(window, name, {
        configurable: true, enumerable: true,
        get: function () { return val; },
        set: function (v) { if (v && typeof v === 'object') api.clean(v); val = v; }
      });
    } catch (e) {}
  }

  if (cfg.adblock) {
    JSON.parse = function (text) {
      var r = nativeParse.apply(this, arguments);
      try { if (typeof text === 'string' && r && typeof r === 'object' && MARK.test(text)) deepClean(r, 0); } catch (e) {}
      return r;
    };
    try {
      var origJson = Response.prototype.json;
      Response.prototype.json = function () {
        return origJson.apply(this, arguments).then(function (o) { return api.clean(o); });
      };
    } catch (e) {}
    trap('ytInitialPlayerResponse');
    trap('ytInitialData');
    trap('playerResponse');
  }

  /* ------------------------------------------------------------------ */
  /* 2. CSS: скрытие рекламных блоков + раскладка для PiP                */
  /* ------------------------------------------------------------------ */
  var AD_CSS = [
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-text-search-renderer',
    'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer', 'ytm-companion-slot',
    'ad-slot-renderer', 'ytm-ad-slot-renderer', 'ytm-statement-banner-renderer',
    'ytm-mealbar-promo-renderer', 'ytm-banner-promo-renderer', 'ytm-search-pyv-renderer',
    'ytm-rich-item-renderer:has(ad-slot-renderer)', 'ytm-item-section-renderer:has(> lazy-list > ad-slot-renderer:only-child)',
    'ytm-rich-section-renderer:has(ytm-statement-banner-renderer)',
    '.ytp-ad-overlay-container', '.ytp-ad-image-overlay', '.ytp-ad-text-overlay',
    '.ytm-promoted-sparkles-web-renderer', '#player-ads', '.ad-container', '.masthead-ad',
    'ytd-enforcement-message-view-model', 'tp-yt-paper-dialog:has(ytd-enforcement-message-view-model)',
    'ytm-paid-content-overlay-renderer'
  ].join(',') + '{display:none!important}';

  var PIP_CSS =
    'html.ytx-pip,html.ytx-pip body{background:#000!important;overflow:hidden!important}' +
    'html.ytx-pip body *{visibility:hidden!important}' +
    'html.ytx-pip .ytx-pip-anc{transform:none!important;contain:none!important;filter:none!important;' +
      'will-change:auto!important;overflow:visible!important;clip-path:none!important}' +
    'html.ytx-pip video.ytx-pip-v{visibility:visible!important;position:fixed!important;left:0!important;' +
      'top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;' +
      'max-width:none!important;max-height:none!important;margin:0!important;transform:none!important;' +
      'object-fit:contain!important;z-index:2147483647!important;background:#000!important}';

  var style = document.createElement('style');
  style.id = 'ytx-style';
  var lastCss = null;
  function ensureStyle() {
    var css = (api.cfg.adblock ? AD_CSS : '') + PIP_CSS;
    if (css !== lastCss) { style.textContent = css; lastCss = css; }
    if (!style.isConnected) {
      var host = document.head || document.documentElement;
      if (host) host.appendChild(style);
    }
  }
  ensureStyle();

  /* ------------------------------------------------------------------ */
  /* 3. Фоновый режим: страница всегда «видима»                           */
  /* ------------------------------------------------------------------ */
  (function patchVisibility() {
    var proto = Document.prototype;
    function wrap(prop, fake) {
      var d = Object.getOwnPropertyDescriptor(proto, prop);
      if (!d || !d.get) return;
      var g = d.get;
      try {
        Object.defineProperty(proto, prop, {
          configurable: true, enumerable: d.enumerable,
          get: function () { return api.cfg.bg ? fake : g.call(this); }
        });
      } catch (e) {}
    }
    wrap('hidden', false);
    wrap('webkitHidden', false);
    wrap('visibilityState', 'visible');
    wrap('webkitVisibilityState', 'visible');
    ['visibilitychange', 'webkitvisibilitychange'].forEach(function (t) {
      var stop = function (e) { if (api.cfg.bg) e.stopImmediatePropagation(); };
      window.addEventListener(t, stop, true);
      document.addEventListener(t, stop, true);
    });
  })();

  /* ------------------------------------------------------------------ */
  /* 4. Управление плеером                                              */
  /* ------------------------------------------------------------------ */
  var handlers = {};
  try {
    var ms = navigator.mediaSession;
    if (ms && ms.setActionHandler) {
      var origSet = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (action, h) {
        handlers[action] = h;
        try { return origSet(action, h); } catch (e) {}
      };
    }
  } catch (e) {}

  function video() {
    return document.querySelector('video.ytx-pip-v') ||
      document.querySelector('#player-container-id video, #movie_player video, .html5-video-player video') ||
      document.querySelector('video');
  }
  function q(sel) { try { return document.querySelector(sel); } catch (e) { return null; } }
  function findButton(re) {
    var list = document.querySelectorAll(
      '#player-control-container button, .player-controls-middle button, .ytp-chrome-controls button, ' +
      'ytm-reel-player-overlay-renderer button, .html5-video-player button');
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      var label = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '');
      if (re.test(label)) return b;
    }
    return null;
  }

  api.play = function () {
    var v = video();
    if (!v) return;
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
  };
  api.pause = function () { var v = video(); if (v) v.pause(); };
  api.toggle = function () { var v = video(); if (!v) return; if (v.paused) api.play(); else v.pause(); };
  api.seekBy = function (s) {
    var v = video(); if (!v) return;
    var t = v.currentTime + s;
    if (isFinite(v.duration)) t = Math.min(t, v.duration - 0.5);
    v.currentTime = Math.max(0, t);
  };
  api.seekTo = function (s) { var v = video(); if (v) v.currentTime = Math.max(0, s); };
  api.next = function () {
    if (handlers.nexttrack) { try { handlers.nexttrack({ action: 'nexttrack' }); return; } catch (e) {} }
    var b = q('.ytp-next-button') || findButton(/next|следующ/i);
    if (b) { b.click(); return; }
    var a = q('ytm-autonav-bar a[href*="/watch"], ytm-compact-autoplay-renderer a[href*="/watch"], ' +
              'ytm-video-with-context-renderer a[href*="/watch"], ytm-compact-video-renderer a[href*="/watch"]');
    if (a) a.click();
  };
  api.prev = function () {
    var v = video();
    if (v && v.currentTime > 3) { v.currentTime = 0; return; }
    if (handlers.previoustrack) { try { handlers.previoustrack({ action: 'previoustrack' }); return; } catch (e) {} }
    history.back();
  };
  api.setCfg = function (c) {
    for (var k in c) api.cfg[k] = c[k];
    ensureStyle();
  };

  /* ------------------------------------------------------------------ */
  /* 5. Картинка-в-картинке                                             */
  /* ------------------------------------------------------------------ */
  api.setPip = function (on) {
    Array.prototype.forEach.call(document.querySelectorAll('.ytx-pip-v'), function (el) { el.classList.remove('ytx-pip-v'); });
    Array.prototype.forEach.call(document.querySelectorAll('.ytx-pip-anc'), function (el) { el.classList.remove('ytx-pip-anc'); });
    var root = document.documentElement;
    if (!on) { root.classList.remove('ytx-pip'); return; }
    var v = video();
    if (v) {
      v.classList.add('ytx-pip-v');
      for (var p = v.parentElement; p && p !== document.documentElement; p = p.parentElement) p.classList.add('ytx-pip-anc');
    }
    root.classList.add('ytx-pip');
  };

  /* ------------------------------------------------------------------ */
  /* 6. Пропуск рекламы + отчёт о состоянии                              */
  /* ------------------------------------------------------------------ */
  var adMutedBefore = null;
  var lastKey = '';
  var lastSentAt = 0;
  var lastLact = 0;

  function text(sel) {
    var el = q(sel);
    return el ? (el.textContent || '').trim() : '';
  }

  function skipAds(v) {
    var player = q('.html5-video-player');
    var ad = !!(player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
    if (!api.cfg.adblock || !v) return ad;
    if (ad) {
      if (adMutedBefore === null) adMutedBefore = v.muted;
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.3) {
        try { v.currentTime = v.duration - 0.1; } catch (e) {}
      }
      var skip = q('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
                   '.ytp-ad-skip-button-slot button, .ytp-ad-skip-button-container button');
      if (skip) skip.click();
    } else if (adMutedBefore !== null) {
      v.muted = adMutedBefore;
      adMutedBefore = null;
    }
    return ad;
  }

  function report(force) {
    var v = video();
    var ad = skipAds(v);
    if (!bridge) return;

    var path = location.pathname;
    var onVideoPage = /^\/(watch|shorts\/|live\/|embed\/)/.test(path);
    var state = 'none';
    if (v && onVideoPage && (v.currentSrc || v.src)) state = (v.paused || v.ended) ? 'paused' : 'playing';

    var md = null;
    try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) {}
    var id = '';
    try {
      id = new URLSearchParams(location.search).get('v') ||
        ((path.match(/^\/(shorts|live|embed)\/([\w-]{6,})/) || [])[2]) || '';
    } catch (e) {}
    var title = (md && md.title) || (document.title || '').replace(/\s*[-–]\s*YouTube\s*$/i, '');
    var artist = (md && md.artist) ||
      text('.slim-owner-channel-name') || text('ytm-slim-owner-renderer .yt-core-attributed-string') ||
      text('ytm-channel-name') || '';
    var art = '';
    if (md && md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src || '';
    if (!art && id) art = 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg';

    var o = {
      state: state, title: title, artist: artist, art: art, id: id, ad: ad,
      dur: (v && isFinite(v.duration)) ? v.duration : 0,
      pos: v ? v.currentTime : 0,
      w: v ? (v.videoWidth || 0) : 0,
      h: v ? (v.videoHeight || 0) : 0
    };
    var key = [o.state, o.title, o.artist, o.art, o.ad, Math.round(o.dur), o.w, o.h].join('|');
    var now = Date.now();
    if (force || key !== lastKey || (state === 'playing' && now - lastSentAt > 10000)) {
      lastKey = key;
      lastSentAt = now;
      try { bridge.onState(JSON.stringify(o)); } catch (e) {}
    }

    // Не даём YouTube показать «Видео приостановлено. Продолжить просмотр?»
    if (now - lastLact > 30000) {
      lastLact = now;
      try { if ('_lact' in window) window._lact = now; } catch (e) {}
    }
  }

  ['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'durationchange', 'emptied', 'seeked', 'ratechange']
    .forEach(function (t) {
      document.addEventListener(t, function () { report(t === 'seeked' || t === 'play' || t === 'pause'); }, true);
    });
  window.addEventListener('yt-navigate-finish', function () { report(true); }, true);
  window.addEventListener('popstate', function () { setTimeout(function () { report(true); }, 300); }, true);

  setInterval(function () { ensureStyle(); report(false); }, 500);
  api.report = function () { report(true); };
})();
