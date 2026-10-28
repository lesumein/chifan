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

  function openH5(h5) {
    var w = window.open(h5, '_blank');
    if (w) { try { w.opener = null; } catch (e) {} }
    else location.href = h5;
  }

  function androidIntent(a, h5) {
    return 'intent://' + (a.path || '') + '#Intent;scheme=' + a.scheme + ';package=' + a.package +
      ';S.browser_fallback_url=' + encodeURIComponent(h5) + ';end';
  }

  function iosTry(o, h5) {
    var left = false;
    function onHide() { if (document.hidden) left = true; }
    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('pagehide', onHide);
    setTimeout(function () {
      document.removeEventListener('visibilitychange', onHide);
      window.removeEventListener('pagehide', onHide);
      if (!left && !document.hidden) location.href = h5;
    }, o.fallbackMs || 1600);
    location.href = o.url;
  }

  // 어떤 방식으로 열었는지 돌려준다: 'h5' | 'android-intent' | 'ios-scheme'
  function openDelivery() {
    var cfg = window.CHIFAN_CONFIG, h5 = cfg.h5Url, app = cfg.appOpen || {};
    if (!app.enabled || env.inApp) { openH5(h5); return 'h5'; }
    if (env.android && app.android && app.android.package) { location.href = androidIntent(app.android, h5); return 'android-intent'; }
    if (env.ios && app.ios && app.ios.url) { iosTry(app.ios, h5); return 'ios-scheme'; }
    openH5(h5);
    return 'h5';
  }

  window.Opener = { env: env, openDelivery: openDelivery, androidIntent: androidIntent };
})();
