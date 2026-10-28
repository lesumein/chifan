// 사용자가 찍거나 고른 메뉴 사진을 기기(IndexedDB)에만 저장한다. 서버로 보내지 않는다.
(function () {
  var DB = 'chifan', STORE = 'photos', MAX = 720, dbp = null;

  function open() {
    if (dbp) return dbp;
    dbp = new Promise(function (resolve, reject) {
      if (!window.indexedDB) return reject(new Error('IndexedDB 없음'));
      var req = indexedDB.open(DB, 1);
      req.onupgradeneeded = function () { req.result.createObjectStore(STORE); };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    });
    return dbp;
  }

  function tx(mode, fn) {
    return open().then(function (db) {
      return new Promise(function (resolve, reject) {
        var t = db.transaction(STORE, mode), st = t.objectStore(STORE), out;
        var r = fn(st);
        if (r) r.onsuccess = function () { out = r.result; };
        t.oncomplete = function () { resolve(out); };
        t.onerror = t.onabort = function () { reject(t.error); };
      });
    });
  }

  // 큰 사진은 긴 변 MAX px JPEG로 줄여서 저장 (기기 용량 절약)
  function shrink(file) {
    return new Promise(function (resolve) {
      var url = URL.createObjectURL(file), img = new Image();
      img.onload = function () {
        var s = Math.min(1, MAX / Math.max(img.naturalWidth, img.naturalHeight));
        var c = document.createElement('canvas');
        c.width = Math.round(img.naturalWidth * s);
        c.height = Math.round(img.naturalHeight * s);
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
        URL.revokeObjectURL(url);
        c.toBlob(function (b) { resolve(b || file); }, 'image/jpeg', 0.82);
      };
      img.onerror = function () { URL.revokeObjectURL(url); resolve(file); };
      img.src = url;
    });
  }

  var urls = {};
  window.Photos = {
    keys: function () { return tx('readonly', function (st) { return st.getAllKeys(); }).then(function (k) { return k || []; }); },
    url: function (id) {
      if (urls[id]) return Promise.resolve(urls[id]);
      return tx('readonly', function (st) { return st.get(id); }).then(function (b) {
        return b ? (urls[id] = URL.createObjectURL(b)) : null;
      });
    },
    save: function (id, file) {
      if (navigator.storage && navigator.storage.persist) navigator.storage.persist();
      return shrink(file).then(function (b) {
        return tx('readwrite', function (st) { return st.put(b, id); });
      }).then(function () {
        if (urls[id]) { URL.revokeObjectURL(urls[id]); delete urls[id]; }
      });
    },
    remove: function (id) {
      return tx('readwrite', function (st) { return st.delete(id); }).then(function () {
        if (urls[id]) { URL.revokeObjectURL(urls[id]); delete urls[id]; }
      });
    }
  };
})();
