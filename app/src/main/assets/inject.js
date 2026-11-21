/*
 * Труба — скрипт, внедряемый в страницы YouTube.
 *
 * В приложении два окна (WebView) с разными ролями:
 *   browse — лента, поиск, каналы, шортс;
 *   player — открытое видео (разворачивается на весь экран или сворачивается в мини-плеер).
 *
 *  1. Вырезает рекламу из ответов YouTube и пропускает видеорекламу, если она проскочила.
 *  2. Разводит ссылки по окнам: видео → плеер, всё остальное → лента.
 *  3. В плеере: притворяется, что страница всегда видима (фоновый режим), сообщает приложению
 *     состояние, принимает команды (play/pause/next/...), режим «только видео» для мини-плеера и PiP.
 *  4. Добавляет в шапку YouTube кнопки приложения (настройки, свернуть, меню).
 *
 * Код намеренно на ES5 — должен работать и в старом WebView на Android 7.
 */
(function () {
  'use strict';
  if (window.__ytx) return;

  var bridge = window.YTXBridge;
  var nativeParse = JSON.parse;
  var cfg = { role: 'browse', adblock: true, bg: false, speed: 1, pip: false, captionsOff: true, shortsQuality: 'auto' };
  try { if (bridge) cfg = nativeParse(bridge.config()); } catch (e) {}
  var isPlayer = cfg.role === 'player';

  var api = (window.__ytx = { cfg: cfg });

  function q(sel, root) { try { return (root || document).querySelector(sel); } catch (e) { return null; } }
  function qa(sel, root) { try { return (root || document).querySelectorAll(sel); } catch (e) { return []; } }
  function each(list, fn) { for (var i = 0; i < list.length; i++) fn(list[i], i); }
  function call(name, arg) { try { if (bridge && bridge[name]) bridge[name](arg === undefined ? '' : arg); } catch (e) {} }

  /* ================================================================== */
  /* 1. Вырезание рекламы из данных                                       */
  /* ================================================================== */
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
    if (isr && Array.isArray(isr.contents) && isr.contents.length > 0 && isr.contents.every(hasAdKey)) return true;
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
      var origJson = window.Response && Response.prototype.json;
      if (origJson) {
        Response.prototype.json = function () {
          return origJson.apply(this, arguments).then(function (o) { return api.clean(o); });
        };
      }
    } catch (e) {}
    trap('ytInitialPlayerResponse');
    trap('ytInitialData');
    trap('playerResponse');
  }

  /* Субтитры по умолчанию выключены: запоминаемая настройка плеера YouTube */
  if (cfg.captionsOff) {
    try {
      var nowTs = Date.now();
      var off = JSON.stringify({ data: 'false', expiration: nowTs + 31536e6, creation: nowTs });
      localStorage.setItem('yt-player-sticky-caption', off);
      localStorage.setItem('yt-html5-player-modules::subtitlesModuleData::module-enabled', off);
    } catch (e) {}
  }

  /* ================================================================== */
  /* 2. CSS                                                             */
  /* ================================================================== */
  // Каждый селектор — отдельным правилом: если старый WebView не знает :has(),
  // он отбросит только это правило, а не весь список.
  var AD_SELECTORS = [
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-text-search-renderer',
    'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer', 'ytm-companion-slot',
    'ad-slot-renderer', 'ytm-ad-slot-renderer', 'ytm-statement-banner-renderer',
    'ytm-mealbar-promo-renderer', 'ytm-banner-promo-renderer', 'ytm-search-pyv-renderer',
    'ytm-rich-item-renderer:has(ad-slot-renderer)',
    'ytm-item-section-renderer:has(> lazy-list > ad-slot-renderer:only-child)',
    'ytm-rich-section-renderer:has(ytm-statement-banner-renderer)',
    '.ytp-ad-overlay-container', '.ytp-ad-image-overlay', '.ytp-ad-text-overlay',
    '#player-ads', '.ad-container', '.masthead-ad', 'ytm-paid-content-overlay-renderer',
    'ytd-enforcement-message-view-model', 'tp-yt-paper-dialog:has(ytd-enforcement-message-view-model)'
  ];
  var AD_CSS = AD_SELECTORS.map(function (s) { return s + '{display:none!important}'; }).join('\n');

  // «Только видео» — для мини-плеера и картинки-в-картинке
  var VIDEO_ONLY_CSS =
    'html.ytx-vo,html.ytx-vo body{background:#000!important;overflow:hidden!important}\n' +
    'html.ytx-vo body *{visibility:hidden!important}\n' +
    'html.ytx-vo .ytx-vo-anc{transform:none!important;contain:none!important;filter:none!important;' +
      'will-change:auto!important;overflow:visible!important;clip-path:none!important}\n' +
    'html.ytx-vo video.ytx-vo-v{visibility:visible!important;position:fixed!important;left:0!important;' +
      'top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;' +
      'max-width:none!important;max-height:none!important;margin:0!important;transform:none!important;' +
      'object-fit:contain!important;z-index:2147483647!important;background:#000!important}\n';

  // Плеер: прячем «Включить звук» — звук включаем сами
  var PLAYER_CSS = [
    '.ytp-unmute', '.ytp-unmute-inner', '.html5-video-player [class*="unmute"]',
    '[class*="unmute-button"]', '.ytm-muted-autoplay-endscreen'
  ].map(function (s) { return 'html.ytx-player ' + s + '{display:none!important}'; }).join('\n') + '\n';

  // Шортс: субтитры скрыты (кнопки включить их там всё равно нет)
  var SHORTS_CAPTIONS_CSS =
    'html.ytx-shorts .ytp-caption-window-container,html.ytx-shorts .caption-window,' +
    'html.ytx-shorts .ytp-caption-segment{display:none!important}\n';

  // Кнопки приложения в шапке YouTube
  var BTN_CSS =
    '.ytx-btn{display:inline-flex!important;align-items:center;justify-content:center;width:40px;height:40px;' +
      'min-width:40px;flex:none;border:0;margin:0 2px;padding:0;background:transparent;color:inherit;' +
      'border-radius:20px;cursor:pointer;-webkit-tap-highlight-color:transparent}\n' +
    '.ytx-btn:active{background:rgba(128,128,128,.3)}\n' +
    '.ytx-btn svg{width:24px;height:24px;fill:currentColor;pointer-events:none}\n' +
    '.ytx-float{position:fixed!important;z-index:2147483646;background:rgba(0,0,0,.55)!important;color:#fff!important}\n';

  var miniPad = 0;
  var style = document.createElement('style');
  style.id = 'ytx-style';
  var lastCss = null;
  function ensureStyle() {
    var css = (api.cfg.adblock ? AD_CSS + '\n' : '') + VIDEO_ONLY_CSS + BTN_CSS + PLAYER_CSS +
      (api.cfg.captionsOff ? SHORTS_CAPTIONS_CSS : '') +
      (miniPad > 0 ? 'html body{padding-bottom:' + miniPad + 'px!important}\n' : '');
    if (css !== lastCss) { style.textContent = css; lastCss = css; }
    var rootEl = document.documentElement;
    if (rootEl && !rootEl.contains(style)) (document.head || rootEl).appendChild(style);
    if (rootEl && isPlayer && !rootEl.classList.contains('ytx-player')) rootEl.classList.add('ytx-player');
  }
  ensureStyle();

  /* ================================================================== */
  /* 3. Фоновый режим (только плеер): страница всегда «видима»            */
  /* ================================================================== */
  if (isPlayer) (function patchVisibility() {
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
    each(['visibilitychange', 'webkitvisibilitychange'], function (t) {
      var stop = function (e) { if (api.cfg.bg) e.stopImmediatePropagation(); };
      window.addEventListener(t, stop, true);
      document.addEventListener(t, stop, true);
    });
  })();

  /* Плеер открывается программно (без нажатия на странице), и YouTube из-за этого
     запускает видео без звука с плашкой «Включить звук». Говорим ему, что нажатие было. */
  if (isPlayer) {
    try {
      var UAp = window.UserActivation && UserActivation.prototype;
      if (UAp) each(['hasBeenActive', 'isActive'], function (p) {
        try { Object.defineProperty(UAp, p, { configurable: true, get: function () { return true; } }); } catch (e) {}
      });
    } catch (e) {}
  }

  /* ================================================================== */
  /* 4. Маршрутизация ссылок между окнами                                */
  /* ================================================================== */
  function isYtHost(h) { return h === 'youtube.com' || /\.youtube\.com$/.test(h); }
  function isVideoPath(p) { return p === '/watch' || /^\/live\/[\w-]+/.test(p); }

  function routeUrl(href) {
    var u;
    try { u = new URL(href, location.href); } catch (e) { return null; }
    if (!isYtHost(u.hostname)) return null;
    return u;
  }

  window.addEventListener('click', function (e) {
    var t = e.target;
    var a = t && t.closest ? t.closest('a[href]') : null;
    if (!a) return;
    var u = routeUrl(a.getAttribute('href'));
    if (!u) return;
    if (isPlayer) {
      if (isVideoPath(u.pathname)) return;         // другое видео — остаётся в плеере
      e.preventDefault(); e.stopImmediatePropagation();
      call('openBrowse', u.href);
    } else if (isVideoPath(u.pathname)) {
      e.preventDefault(); e.stopImmediatePropagation();
      call('openVideo', u.href);
    }
  }, true);

  // Запасной путь: YouTube сменил адрес программно (history.pushState)
  var routing = false;
  function checkRoute() {
    if (routing) return;
    var p = location.pathname;
    if (isPlayer && !isVideoPath(p)) {
      routing = true;
      call('openBrowse', location.href);
      history.back();
      setTimeout(function () { routing = false; }, 800);
    } else if (!isPlayer && isVideoPath(p)) {
      routing = true;
      api.pauseAll();
      call('openVideo', location.href);
      if (history.length > 1) history.back(); else location.replace('/');
      setTimeout(function () { routing = false; }, 800);
    }
  }
  each(['pushState', 'replaceState'], function (m) {
    var orig = history[m];
    if (!orig) return;
    history[m] = function () {
      var r = orig.apply(this, arguments);
      setTimeout(checkRoute, 0);
      return r;
    };
  });
  window.addEventListener('popstate', function () { setTimeout(checkRoute, 0); }, true);

  /* ================================================================== */
  /* 5. Управление плеером                                               */
  /* ================================================================== */
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
    return q('video.ytx-vo-v') ||
      q('#player-container-id video, #movie_player video, .html5-video-player video') ||
      q('video');
  }
  function findButton(re) {
    var list = qa('#player-control-container button, .player-controls-middle button, ' +
      '.ytp-chrome-controls button, .html5-video-player button');
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      var label = (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '');
      if (re.test(label)) return b;
    }
    return null;
  }

  api.play = function () {
    var v = video(); if (!v) return;
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
  };
  api.pause = function () { var v = video(); if (v) v.pause(); };
  api.pauseAll = function () { each(qa('video'), function (v) { try { v.pause(); } catch (e) {} }); };
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
  api.setSpeed = function (r) {
    r = +r || 1;
    var mp = document.getElementById('movie_player') || q('.html5-video-player');
    try { if (mp && typeof mp.setPlaybackRate === 'function') mp.setPlaybackRate(r); } catch (e) {}
    var v = video(); if (v) v.playbackRate = r;
  };
  api.scrollTop = function () { try { window.scrollTo(0, 0); } catch (e) {} };

  /* Качество */
  var QUALITY_ORDER = ['highres', 'hd2160', 'hd1440', 'hd1080', 'hd720', 'large', 'medium', 'small', 'tiny'];
  function activePlayer() {
    var vids = qa('video');
    for (var i = 0; i < vids.length; i++) {
      if (!vids[i].paused) {
        var mp = vids[i].closest && vids[i].closest('.html5-video-player');
        if (mp) return mp;
      }
    }
    return playerApi();
  }
  api.qualityInfo = function () {
    var mp = activePlayer();
    var levels = [], cur = '';
    try { if (mp && mp.getAvailableQualityLevels) levels = mp.getAvailableQualityLevels() || []; } catch (e) {}
    try { if (mp && mp.getPlaybackQuality) cur = mp.getPlaybackQuality() || ''; } catch (e) {}
    return JSON.stringify({ levels: levels, current: cur });
  };
  function bestLevel(levels, want) {
    if (want === 'auto' || !levels || !levels.length) return want;
    if (levels.indexOf(want) >= 0) return want;
    var wi = QUALITY_ORDER.indexOf(want);
    for (var i = Math.max(0, wi); i < QUALITY_ORDER.length; i++) {
      if (levels.indexOf(QUALITY_ORDER[i]) >= 0) return QUALITY_ORDER[i];
    }
    return want;
  }
  function setQualityOn(mp, q) {
    if (!mp) return;
    try {
      var levels = mp.getAvailableQualityLevels ? mp.getAvailableQualityLevels() : [];
      var lv = bestLevel(levels, q);
      if (mp.setPlaybackQualityRange) mp.setPlaybackQualityRange(lv, lv);
      if (mp.setPlaybackQuality) mp.setPlaybackQuality(lv);
    } catch (e) {}
  }
  api.setQuality = function (q) {
    each(qa('.html5-video-player'), function (mp) { setQualityOn(mp, q); });
  };

  /* Субтитры: выключаем на каждом новом видео (пользователь может включить вручную) */
  var captionsDone = {};
  function captionsOff(v, id) {
    if (!api.cfg.captionsOff || !v || !id || captionsDone[id]) return;
    captionsDone[id] = 1;
    var mp = (v.closest && v.closest('.html5-video-player')) || playerApi();
    if (!mp) return;
    var off = function () {
      try { if (mp.unloadModule) { mp.unloadModule('captions'); mp.unloadModule('cc'); } } catch (e) {}
      try { if (mp.setOption) mp.setOption('captions', 'track', {}); } catch (e) {}
    };
    off();
    setTimeout(off, 1500);
    setTimeout(off, 4000);
  }

  api.setMiniPad = function (px) { miniPad = +px || 0; ensureStyle(); };
  api.getSpeed = function () { var v = video(); return v ? v.playbackRate : 1; };
  api.setCfg = function (c) {
    for (var k in c) api.cfg[k] = c[k];
    ensureStyle();
  };

  /* Режим «только видео» (мини-плеер, PiP) */
  api.setVideoOnly = function (on) {
    each(qa('.ytx-vo-v'), function (el) { el.classList.remove('ytx-vo-v'); });
    each(qa('.ytx-vo-anc'), function (el) { el.classList.remove('ytx-vo-anc'); });
    var rootEl = document.documentElement;
    api.videoOnly = !!on;
    if (!on) { rootEl.classList.remove('ytx-vo'); return; }
    var v = video();
    if (v) {
      v.classList.add('ytx-vo-v');
      for (var p = v.parentElement; p && p !== rootEl; p = p.parentElement) p.classList.add('ytx-vo-anc');
    }
    rootEl.classList.add('ytx-vo');
  };

  /* ================================================================== */
  /* 6. Кнопки приложения в шапке                                        */
  /* ================================================================== */
  var ICONS = {
    settings: 'M19.14 12.94a7.07 7.07 0 0 0 .06-.94 7.07 7.07 0 0 0-.06-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54A.48.48 0 0 0 13.92 2h-3.84a.48.48 0 0 0-.48.41l-.36 2.54c-.59.24-1.13.56-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.72 8.47a.48.48 0 0 0 .12.61l2.03 1.58a7.4 7.4 0 0 0 0 1.88l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54a7.03 7.03 0 0 0 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z',
    minimize: 'M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z',
    menu: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
    quality: 'M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm-8 12H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm2-6h4c.55 0 1 .45 1 1v4c0 .55-.45 1-1 1h-4V9zm1.5 4.5h2v-3h-2v3z',
    pip: 'M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z'
  };
  function makeBtn(action, label) {
    var b = document.createElement('button');
    b.className = 'ytx-btn ytx-btn-' + action;
    b.setAttribute('data-ytx', action);
    b.setAttribute('aria-label', label);
    b.setAttribute('type', 'button');
    b.innerHTML = '<svg viewBox="0 0 24 24"><path d="' + ICONS[action] + '"/></svg>';
    // YouTube может гасить click в шапке, поэтому ловим и касание; повторы отсекаем
    var last = 0;
    var fire = function (e) {
      e.preventDefault(); e.stopImmediatePropagation();
      var now = Date.now();
      if (now - last < 600) return;
      last = now;
      call('uiAction', action);
    };
    b.addEventListener('touchend', fire, true);
    b.addEventListener('click', fire, true);
    b.addEventListener('touchstart', function (e) { e.stopImmediatePropagation(); }, true);
    return b;
  }
  function topbar() {
    return q('ytm-mobile-topbar-renderer .mobile-topbar-header-content') ||
      q('header.mobile-topbar-header .mobile-topbar-header-content') ||
      q('.mobile-topbar-header-content') || q('ytm-mobile-topbar-renderer header') ||
      q('header.mobile-topbar-header');
  }
  function isShorts() { return /^\/shorts(\/|$)/.test(location.pathname); }
  function firstVisibleChild(bar) {
    // ставим ⌄ перед логотипом, пропуская пустые/служебные узлы
    for (var c = bar.firstElementChild; c; c = c.nextElementSibling) {
      if (c.offsetWidth > 0 || c.offsetHeight > 0) return c;
    }
    return bar.firstChild;
  }
  var startedAt = Date.now();
  function ensureButtons() {
    if (!document.body || api.videoOnly) return;
    var bar = topbar();
    var want = isPlayer ? (api.cfg.pip ? ['minimize', 'pip', 'menu'] : ['minimize', 'menu'])
      : (isShorts() ? ['quality', 'settings'] : ['settings']);
    var labels = { settings: 'Настройки приложения', minimize: 'Свернуть видео', menu: 'Меню плеера',
      pip: 'Картинка в картинке', quality: 'Качество видео' };
    each(qa('.ytx-btn'), function (b) {
      if (want.indexOf(b.getAttribute('data-ytx')) < 0 && b.parentNode) b.parentNode.removeChild(b);
    });
    each(want, function (action) {
      var existing = q('.ytx-btn-' + action);
      if (bar) {
        if (existing && bar.contains(existing)) return;
        if (existing) existing.parentNode.removeChild(existing);
        var b = makeBtn(action, labels[action]);
        if (action === 'minimize') bar.insertBefore(b, firstVisibleChild(bar));
        else bar.appendChild(b);
      } else if (!existing && Date.now() - startedAt > 4000) {
        // шапку не нашли — плавающая кнопка
        var f = makeBtn(action, labels[action]);
        f.classList.add('ytx-float');
        if (action === 'minimize') { f.style.top = '8px'; f.style.left = '8px'; }
        else if (action === 'menu') { f.style.top = '8px'; f.style.right = '8px'; }
        else if (action === 'pip') { f.style.top = '8px'; f.style.right = '56px'; }
        else if (action === 'quality') { f.style.top = '8px'; f.style.right = '56px'; }
        else { f.style.bottom = '64px'; f.style.left = '8px'; }
        document.body.appendChild(f);
      }
    });
  }

  /* ================================================================== */
  /* 7. Пропуск рекламы + отчёт о состоянии                              */
  /* ================================================================== */
  var adMutedBefore = null;
  var lastKey = '';
  var lastSentAt = 0;
  var lastLact = 0;
  var speedAppliedFor = '';
  var nudgedFor = '';
  var lastLayout = '';

  function playerApi() { return document.getElementById('movie_player') || q('.html5-video-player'); }

  // Звук включаем сами (кроме времени рекламы — её мы глушим специально)
  function ensureSound(v, ad) {
    if (!v || ad || adMutedBefore !== null || !v.muted) return;
    v.muted = false;
    var mp = playerApi();
    try { if (mp && mp.unMute) mp.unMute(); } catch (e) {}
  }

  // Лента сообщает приложению высоту нижнего меню и открыт ли раздел Shorts
  function reportLayout() {
    var pivot = q('ytm-pivot-bar-renderer');
    var h = 0;
    if (pivot) {
      var r = pivot.getBoundingClientRect();
      if (r.height > 0 && r.bottom >= window.innerHeight - 2) h = Math.round(r.height);
    }
    var shorts = isShorts();
    document.documentElement.classList.toggle('ytx-shorts', shorts);
    var key = h + '|' + shorts;
    if (key !== lastLayout) {
      lastLayout = key;
      call('onBrowseLayout', JSON.stringify({ pivot: h, shorts: shorts }));
    }
  }

  function text(sel) {
    var el = q(sel);
    return el ? (el.textContent || '').trim() : '';
  }
  function currentId() {
    try {
      return new URLSearchParams(location.search).get('v') ||
        ((location.pathname.match(/^\/(shorts|live|embed)\/([\w-]{6,})/) || [])[2]) || '';
    } catch (e) { return ''; }
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
    if (!isPlayer) { reportLayout(); return; }
    if (!bridge) return;
    if (v && isVideoPath(location.pathname)) ensureSound(v, ad);

    var path = location.pathname;
    var state = 'none';
    if (v && isVideoPath(path) && (v.currentSrc || v.src)) state = (v.paused || v.ended) ? 'paused' : 'playing';

    var md = null;
    try { md = navigator.mediaSession && navigator.mediaSession.metadata; } catch (e) {}
    var id = currentId();
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
      rate: v ? v.playbackRate : 1,
      w: v ? (v.videoWidth || 0) : 0,
      h: v ? (v.videoHeight || 0) : 0,
      vr: [0, 0, 0, 0],
      vw: window.innerWidth || 0
    };
    // Где видео на странице — приложение показывает в мини-плеере и PiP только этот участок
    if (v) {
      var rc = v.getBoundingClientRect();
      o.vr = [Math.round(rc.left), Math.round(rc.top), Math.round(rc.width), Math.round(rc.height)];
    }
    var key = [o.state, o.title, o.artist, o.art, o.ad, Math.round(o.dur), o.w, o.h, o.rate, o.vr.join(','), o.vw].join('|');
    var now = Date.now();
    if (force || key !== lastKey || (state === 'playing' && now - lastSentAt > 10000)) {
      lastKey = key;
      lastSentAt = now;
      call('onState', JSON.stringify(o));
    }

    // Не даём YouTube показать «Видео приостановлено. Продолжить просмотр?»
    if (now - lastLact > 30000) {
      lastLact = now;
      try { if ('_lact' in window) window._lact = now; } catch (e) {}
    }
  }

  function onMedia(e) {
    var t = e.type;
    var v = e.target;
    if (isPlayer) {
      if (t === 'playing' && !(q('.html5-video-player.ad-showing'))) {
        var id = currentId();
        if (id && id !== speedAppliedFor) {
          speedAppliedFor = id;
          var sp = +api.cfg.speed || 1;
          if (sp !== 1) api.setSpeed(sp);
        }
      }
      if (api.videoOnly && (t === 'loadedmetadata' || t === 'playing')) api.setVideoOnly(true);
      if (t === 'playing') {
        ensureSound(v, !!q('.html5-video-player.ad-showing'));
        captionsOff(v, 'p:' + currentId());
      }
      // Видео загрузилось, но YouTube его не запустил — один раз запускаем сами
      if ((t === 'loadedmetadata' || t === 'canplay') && isVideoPath(location.pathname)) {
        var nid = currentId();
        if (nid && nid !== nudgedFor) {
          nudgedFor = nid;
          setTimeout(function () { var vv = video(); if (vv && vv.paused && !vv.ended) api.play(); }, 700);
        }
      }
      report(t === 'seeked' || t === 'play' || t === 'pause' || t === 'ratechange');
    } else if (t === 'playing' && v && v.tagName === 'VIDEO' && isShorts()) {
      var sid = 's:' + currentId();
      captionsOff(v, sid);
      if (api.cfg.shortsQuality && api.cfg.shortsQuality !== 'auto' && v.getAttribute('data-ytx-q') !== sid) {
        v.setAttribute('data-ytx-q', sid);
        var mpq = v.closest && v.closest('.html5-video-player');
        setTimeout(function () { setQualityOn(mpq, api.cfg.shortsQuality); }, 300);
      }
      // В ленте заиграл шортс со звуком — ставим основное видео на паузу.
      // Проверяем с задержкой: превью в ленте играют без звука, и звук им ставят не сразу.
      setTimeout(function () {
        if (!v.paused && !v.muted && v.volume > 0 && /^\/shorts\//.test(location.pathname)) call('onBrowsePlay');
      }, 400);
    }
  }
  each(['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'canplay', 'durationchange', 'emptied', 'seeked', 'ratechange'],
    function (t) { document.addEventListener(t, onMedia, true); });
  window.addEventListener('yt-navigate-finish', function () { report(true); }, true);

  setInterval(function () { ensureStyle(); ensureButtons(); report(false); }, 500);
  api.report = function () { report(true); };
})();
