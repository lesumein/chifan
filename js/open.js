// 배달앱 열기. 주소는 js/config.js 한 곳에서만 읽는다.
// 반드시 사용자 탭에서만 호출할 것 (페이지 로드 시 자동 이동 금지).
(function () {
  var ua = navigator.userAgent || '';
  var env = {
    android: /Android/i.test(ua),
    ios: /iPhone|iPad|iPod/i.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1),
    wechat: /MicroMessenger/i.test(ua),
    kakao: /KAKAOTALK/i.test(ua),
    inApp: /MicroMessenger|KAKAOTALK|NAVER\(inapp|Line\/|FBAN|FBAV|Instagram|DingTalk|\bQQ\/|Weibo/i.test(ua)
  };

  // 테스트에서 이동을 가로챌 수 있게 한 곳으로 모은다
  function go(url) {
    if (window.__chifanGo) return window.__chifanGo(url);
    location.href = url;
  }

  function openH5(h5) {
    var w = window.open(h5, '_blank');
    if (w) { try { w.opener = null; } catch (e) {} }
    else go(h5);
  }

  function withQ(tpl, q) { return tpl.replace('{q}', encodeURIComponent(q)); }

  function androidIntent(a, h5, q) {
    var p = q && a.searchPath ? withQ(a.searchPath, q) : (a.path || '');
    return 'intent://' + p + '#Intent;scheme=' + a.scheme + ';package=' + a.package +
      ';S.browser_fallback_url=' + encodeURIComponent(h5) + ';end';
  }

  // url로 이동해 보고, fallbackMs 안에 페이지가 숨겨지지 않으면(앱이 안 열리면) fallback으로
  function tryThen(url, fallback, ms) {
    var left = false;
    function onHide() { if (document.hidden) left = true; }
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', onHide);
    setTimeout(function () {
      document.removeEventListener('visibilitychange', onHide);
      window.removeEventListener('pagehide', onHide);
      if (!left && !document.hidden) go(fallback);
    }, ms || 1600);
    go(url);
  }

  // mode: 'app' = 앱 시도 후 안 되면 웹, 'web' = 웹만.
  // 어떤 방식으로 열었는지 돌려준다: 'h5' | 'android-intent' | 'ios-scheme'
  // q: 앱에 넘길 검색어(없으면 앱 첫 화면)
  function openDelivery(mode, q) {
    var cfg = window.CHIFAN_CONFIG, h5 = cfg.h5Url, app = cfg.appOpen || {};
    if (mode !== 'app' || !app.enabled || env.inApp) { openH5(h5); return 'h5'; }
    if (env.android && app.android && app.android.package) { go(androidIntent(app.android, h5, q)); return 'android-intent'; }
    if (env.ios && app.ios && app.ios.url) { tryThen(q && app.ios.searchUrl ? withQ(app.ios.searchUrl, q) : app.ios.url, h5, app.ios.fallbackMs); return 'ios-scheme'; }
    openH5(h5);
    return 'h5';
  }

  // 배달앱 설치: 안드로이드는 스토어 앱(market://) → 안 되면 Play 웹, iOS는 App Store
  function openInstall() {
    var ins = window.CHIFAN_CONFIG.install || {}, a = ins.android || {}, i = ins.ios || {};
    if (env.ios && i.url) { go(i.url); return 'ios-store'; }
    if (env.android && a.market && !env.inApp) { tryThen(a.market, a.web, a.fallbackMs); return 'android-market'; }
    var w = a.web || i.url;
    if (w) openH5(w);
    return 'web-store';
  }

  // 앱 열기를 시도할 수 있는 폰인가 (안드로이드·아이폰, 앱 안 브라우저 제외)
  function canTryApp() {
    var app = window.CHIFAN_CONFIG.appOpen || {};
    return !!app.enabled && !env.inApp && (env.android || env.ios);
  }

  window.Opener = { env: env, canTryApp: canTryApp, openDelivery: openDelivery, openInstall: openInstall, androidIntent: androidIntent };
})();
