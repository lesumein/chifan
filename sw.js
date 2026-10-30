// 오프라인용 서비스워커. 파일을 바꾸면 VERSION을 올릴 것.
var VERSION = 'chifan-v39';
var CORE = [
  './',
  'index.html',
  'manifest.webmanifest',
  'css/style.css',
  'js/config.js',
  'js/hangul.js',
  'js/photos.js',
  'js/open.js',
  'js/app.js',
  'data/categories.json',
  'data/foods.json',
  'data/requests.json',
  'data/glossary.json',
  'data/hanja.json',
  'icons/icon.svg',
  'icons/pattern.svg',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/icon-maskable-512.png'
];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(VERSION).then(function (c) {
    // HTTP 캐시에 남은 옛 파일을 받지 않도록 cache: 'reload'
    return c.addAll(CORE.map(function (u) { return new Request(u, { cache: 'reload' }); }));
  }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== VERSION; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

// 같은 출처 GET만: 캐시 먼저 보여 주고 뒤에서 새로 받아 둔다 (github.io가 느릴 때 대비).
// 다른 출처(배달앱 등)는 건드리지 않는다.
self.addEventListener('fetch', function (e) {
  var req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return;
  e.respondWith(caches.open(VERSION).then(function (cache) {
    return cache.match(req, { ignoreSearch: true }).then(function (hit) {
      var net = fetch(req).then(function (res) {
        if (res && res.ok) cache.put(req, res.clone());
        return res;
      }).catch(function () { return hit; });
      if (hit) { e.waitUntil(net); return hit; }
      return net.then(function (res) {
        return res || (req.mode === 'navigate' ? cache.match('index.html') : Response.error());
      });
    });
  }));
});
