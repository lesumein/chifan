(function () {
  'use strict';

  var $ = function (s, el) { return (el || document).querySelector(s); };
  var LS = {
    get: function (k, d) { try { var v = localStorage.getItem('chifan.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('chifan.' + k, JSON.stringify(v)); } catch (e) {} }
  };
  var SPICE = ['안 매움', '🌶', '🌶🌶', '🌶🌶🌶'];
  var SPICE_KO = ['안 매움', '약간 매움', '매움', '아주 매움'];

  var S = {
    foods: [], cats: [], catById: {}, foodById: {}, req: { groups: [], items: [] },
    gloss: { groups: [], items: [] }, glossGroup: 'option', dict: null, dictMax: 1,
    cat: LS.get('cat', 'noodle'), query: '',
    recent: LS.get('recent', []), memo: LS.get('memo', []),
    photos: {}, lastCopied: null, sheetFood: null
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function h(tag, attrs, html) {
    var el = document.createElement(tag);
    for (var k in attrs || {}) {
      if (k === 'class') el.className = attrs[k];
      else if (k === 'style') el.style.cssText = attrs[k];
      else el.setAttribute(k, attrs[k]);
    }
    if (html != null) el.innerHTML = html;
    return el;
  }

  // ---------- 복사 / 토스트 ----------
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () { return true; }, function () { return legacyCopy(text); });
    }
    return Promise.resolve(legacyCopy(text));
  }
  function legacyCopy(text) {
    var ta = h('textarea', { readonly: '', style: 'position:fixed;top:0;left:0;opacity:0' });
    ta.value = text;
    document.body.appendChild(ta);
    ta.select(); ta.setSelectionRange(0, text.length);
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) {}
    ta.remove();
    return ok;
  }

  var toastTimer;
  function toast(html, ms) {
    var t = $('#toast');
    t.innerHTML = html;
    t.classList.add('is-on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove('is-on'); }, ms || 2200);
  }

  function copyFood(f) {
    var q = f.q || f.zh;
    return copyText(q).then(function (ok) {
      S.lastCopied = f;
      LS.set('lastCopied', f.id);
      S.recent = [f.id].concat(S.recent.filter(function (id) { return id !== f.id; })).slice(0, 24);
      LS.set('recent', S.recent);
      if (ok) toast('복사됨: <b lang="zh-CN">' + esc(q) + '</b>');
      else toast('복사가 막혔어요. 길게 눌러 직접 복사해 주세요: <b lang="zh-CN">' + esc(q) + '</b>', 4000);
      updateDock(ok);
      if (S.cat === 'recent' && !S.query) renderCats();
    });
  }

  function updateDock(ok) {
    var f = S.lastCopied;
    $('#dockText').innerHTML = f
      ? (ok === false ? '복사 실패 · ' : '복사됨 · ') + '<b lang="zh-CN">' + esc(f.q || f.zh) + '</b><br><small>앱 검색창에 붙여넣기 하세요</small>'
      : '카드를 누르면 중국어 검색어가 복사돼요';
  }

  // ---------- 앱 열기 ----------
  function openApp(mode) {
    var how = Opener.openDelivery(mode, S.lastCopied && (S.lastCopied.q || S.lastCopied.zh));
    var msg = S.lastCopied
      ? '검색창을 길게 눌러 <b>붙여넣기</b> 하세요'
      : '먼저 메뉴 카드를 눌러 검색어를 복사해 두면 편해요';
    if (mode === 'app' && how === 'h5' && Opener.env.inApp) msg = '앱 안 브라우저라 웹으로 열었어요. ' + msg;
    else if (how !== 'h5') msg = S.lastCopied
      ? '<b lang="zh-CN">' + esc(S.lastCopied.q || S.lastCopied.zh) + '</b> 검색으로 앱을 열어요. 결과가 안 뜨면 검색창에 붙여넣기'
      : '앱이 안 열리면 웹으로 넘어가요. ' + msg;
    toast(msg, 3200);
  }

  function openInstall() {
    var term = (window.CHIFAN_CONFIG.install || {}).searchTerm || '美团';
    copyText(term).then(function (ok) {
      Opener.openInstall();
      toast(ok ? '복사됨: <b lang="zh-CN">' + esc(term) + '</b> · 스토어에서 바로 안 보이면 검색창에 붙여넣기'
               : '스토어에서 <b lang="zh-CN">' + esc(term) + '</b>를 검색하세요', 4000);
    });
  }

  // ---------- 검색 ----------
  function score(f, q, qpy) {
    var best = -1;
    function tryField(text, w, py) {
      if (!text) return;
      var t = py ? Hangul.normPy(text) : Hangul.norm(text);
      var i = Hangul.find(t, py ? qpy : q);
      if (i < 0) return;
      var s = w - i * 2 + (t.length === (py ? qpy : q).length ? 25 : 0) - t.length * 0.2;
      if (s > best) best = s;
    }
    tryField(f.ko, 100);
    (f.aka || []).forEach(function (a) { tryField(a, 85); });
    tryField(f.zh, 95);
    if (qpy) tryField(f.py, 60, true);
    return best;
  }

  function searchFoods(raw) {
    var q = Hangul.norm(raw);
    if (!q) return [];
    var qpy = /^[a-z]+$/.test(q) ? q : '';
    return S.foods
      .map(function (f) { return { f: f, s: score(f, q, qpy) }; })
      .filter(function (x) { return x.s >= 0; })
      .sort(function (a, b) { return b.s - a.s; })
      .map(function (x) { return x.f; });
  }

  // ---------- 그리기 ----------
  function renderCats() {
    var box = $('#cats');
    box.innerHTML = '';
    var list = [];
    if (S.recent.length) list.push({ id: 'recent', ko: '최근', emoji: '🕘', color: '#7a6a5a' });
    list = list.concat(S.cats);
    if (S.cat === 'recent' && !S.recent.length) S.cat = S.cats[0].id;
    list.forEach(function (c) {
      var on = !S.query && S.cat === c.id;
      var b = h('button', { class: 'cat' + (on ? ' is-on' : ''), role: 'tab', 'aria-selected': String(on), 'data-cat': c.id, style: '--c:' + c.color },
        '<span aria-hidden="true">' + c.emoji + '</span> ' + esc(c.ko));
      box.appendChild(b);
    });
    renderGrid();
  }

  function currentFoods() {
    if (S.query) return searchFoods(S.query);
    if (S.cat === 'recent') return S.recent.map(function (id) { return S.foodById[id]; }).filter(Boolean);
    return S.foods.filter(function (f) { return f.cat === S.cat; });
  }

  function artHTML(f, big) {
    var c = S.catById[f.cat] || {};
    return '<span class="emoji" aria-hidden="true">' + (f.emoji || c.emoji) + '</span>' +
      (big ? '' : '<span class="spice-chip' + (f.spicy ? ' hot' : '') + '" aria-label="맵기: ' + SPICE_KO[f.spicy || 0] + '">' + SPICE[f.spicy || 0] + '</span>');
  }

  function setArtImage(el, f) {
    function apply(url) { if (url) { el.style.backgroundImage = 'url("' + url + '")'; el.classList.add('has-photo'); } }
    if (S.photos[f.id]) Photos.url(f.id).then(apply).catch(function () {});
    else if (f.img) {
      var src = 'img/' + f.id + '.jpg', im = new Image();
      im.onload = function () { apply(src); };
      im.src = src;
    }
  }

  function card(f) {
    var c = S.catById[f.cat] || {};
    var el = h('div', { class: 'card', role: 'button', tabindex: '0', 'data-id': f.id, style: '--c:' + c.color,
      'aria-label': f.ko + ', ' + f.zh + ' 검색어 복사' });
    el.innerHTML =
      '<div class="art">' + artHTML(f) +
        '<button class="more" data-more="' + esc(f.id) + '" aria-label="' + esc(f.ko) + ' 자세히 보기·사진 넣기">' +
          '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M9 4 7.2 6H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-3.2L15 4H9zm3 4.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9zm0 2a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z"/></svg>' +
        '</button>' +
      '</div>' +
      '<div class="body">' +
        '<div class="zh" lang="zh-CN">' + esc(f.zh) + '</div>' +
        '<div class="py">' + esc(f.py) + '</div>' +
        '<div class="ko">' + esc(f.ko) + (f.check ? ' <span class="check" title="중국어 표기 검증 필요">확인 중</span>' : '') + '</div>' +
        '<div class="desc">' + esc(f.desc) + '</div>' +
      '</div>';
    setArtImage(el.querySelector('.art'), f);
    return el;
  }

  function renderGrid() {
    var list = currentFoods(), grid = $('#grid');
    grid.innerHTML = '';
    var frag = document.createDocumentFragment();
    list.forEach(function (f) { frag.appendChild(card(f)); });
    grid.appendChild(frag);
    $('#empty').hidden = list.length > 0;
    var info = '';
    if (S.query) info = '‘' + esc(S.query) + '’ 검색 결과 ' + list.length + '개';
    else if (S.cat === 'recent') info = '최근 복사한 메뉴';
    else { var c = S.catById[S.cat]; info = c ? esc(c.ko) + ' <span lang="zh-CN">' + esc(c.zh) + '</span> · ' + list.length + '개' : ''; }
    $('#resultInfo').innerHTML = info;
  }

  // ---------- 상세 시트 ----------
  function openSheet(html, cls) {
    $('#sheetBody').innerHTML = html;
    $('#sheet').className = 'sheet ' + (cls || '');
    $('#sheet').hidden = false; $('#sheetBack').hidden = false;
    requestAnimationFrame(function () { $('#sheet').classList.add('is-on'); $('#sheetBack').classList.add('is-on'); });
    $('#sheetClose').focus();
  }
  function closeSheet() {
    $('#sheet').classList.remove('is-on'); $('#sheetBack').classList.remove('is-on');
    setTimeout(function () { $('#sheet').hidden = true; $('#sheetBack').hidden = true; }, 200);
    S.sheetFood = null;
  }

  function showFood(f) {
    S.sheetFood = f;
    var c = S.catById[f.cat] || {};
    var has = !!S.photos[f.id];
    openSheet(
      '<div class="detail-art art" style="--c:' + c.color + '">' + artHTML(f, true) + '</div>' +
      '<div class="detail-zh" lang="zh-CN">' + esc(f.zh) + '</div>' +
      '<div class="detail-py">' + esc(f.py) + '</div>' +
      '<div class="detail-ko">' + esc(f.ko) + ' · <span>' + SPICE_KO[f.spicy || 0] + (f.spicy ? ' ' + SPICE[f.spicy] : '') + '</span></div>' +
      '<p class="detail-desc">' + esc(f.desc) + '</p>' +
      (f.check ? '<p class="note">이 중국어 표기는 아직 확인 중이에요. 검색 결과가 이상하면 비슷한 이름으로도 찾아보세요.</p>' : '') +
      '<div class="row">' +
        '<button class="btn primary" data-act="copy">검색어 복사</button>' +
        (Opener.canTryApp() ? '<button class="btn" data-act="openApp">앱으로 열기</button>' : '') +
        '<button class="btn" data-act="openWeb">웹으로 열기</button>' +
      '</div>' +
      '<div class="row">' +
        '<button class="btn" data-act="camera">📷 사진 찍기</button>' +
        '<button class="btn" data-act="pick">🖼 앨범에서</button>' +
        (has ? '<button class="btn danger" data-act="rmphoto">사진 지우기</button>' : '') +
      '</div>' +
      '<p class="hint">사진은 이 기기에만 저장돼요. 가게 사람에게 보여줄 땐 이 화면을 보여 주세요.</p>',
      'detail');
    setArtImage($('#sheetBody .detail-art'), f);
  }

  function pickPhoto(capture) {
    var inp = $('#photoInput');
    if (capture) inp.setAttribute('capture', 'environment'); else inp.removeAttribute('capture');
    inp.value = '';
    inp.click();
  }

  function showAbout() {
    var env = Opener.env;
    var openState = Opener.canTryApp()
      ? '이 폰은 <b>' + (env.ios ? '아이폰' : '안드로이드') + '</b>으로 알아봤어요. [앱으로 열기]는 앱을 먼저 시도하고, 안 열리면 웹으로 넘어가요. 앱 주소는 아직 확인 중이에요.'
      : (env.inApp ? '앱 안 브라우저에서는 웹으로만 열 수 있어요.' : '이 기기에서는 웹으로 열어요.');
    openSheet(
      '<h2>치판은 이런 앱이에요</h2>' +
      '<ol class="steps">' +
        '<li>메뉴 카드를 누르면 중국어 검색어가 <b>복사</b>돼요.</li>' +
        '<li><b>[앱으로 열기]</b> 또는 <b>[웹]</b>을 눌러 메이퇀을 열어요.</li>' +
        '<li>검색창을 길게 눌러 <b>붙여넣기</b> 하면 끝.</li>' +
      '</ol>' +
      '<p>' + openState + '</p>' +
      '<h3>안드로이드 앱 (실험)</h3>' +
      '<p>메이퇀 화면 위에 한국어 뜻을 덧그리고, 떠다니는 버튼으로 메뉴·요청사항을 바로 복사하는 안드로이드 앱이 있어요. ' +
      '설치 파일을 직접 받아 설치해요(스토어 아님). 자세한 건 README.</p>' +
      '<div class="row"><a class="btn" href="apk/chifan-0.1-debug.apk" download>앱 설치 파일 받기 (APK)</a></div>' +
      '<h3>배달앱이 없다면</h3>' +
      '<p>먼저 메이퇀 앱을 설치하세요. 아이폰은 중국 지역 Apple ID가 있어야 보일 수 있어요. ' +
      '앱이 없으면 [앱으로 열기]를 눌러도 웹으로 넘어가요.</p>' +
      '<div class="row"><button class="btn" data-act="installApp">메이퇀 앱 설치하기</button></div>' +
      '<h3>홈 화면에 추가</h3>' +
      '<p>안드로이드 크롬: 오른쪽 위 ⋮ → <b>홈 화면에 추가</b><br>아이폰 사파리: 공유 버튼 → <b>홈 화면에 추가</b><br>한 번 열어 두면 인터넷 없이도 메뉴를 볼 수 있어요.</p>' +
      (S.installEvt ? '<div class="row"><button class="btn primary" data-act="install">지금 설치하기</button></div>' : '') +
      '<h3>알아 두세요</h3>' +
      '<p>치판은 메이퇀 등 배달앱과 <b>관계없는 개인 도구</b>예요. 검색어만 만들어 줄 뿐, 배달앱 화면이나 데이터에는 접근하지 않아요. ' +
      '‘확인 중’ 표시가 있는 메뉴는 중국어 표기를 아직 검증하지 못했어요.</p>' +
      '<p>저장되는 것(최근 메뉴, 요청사항 선택, 사진)은 모두 이 기기 안에만 있어요.</p>' +
      '<p class="hint">메뉴 ' + S.foods.length + '개 · 요청사항 ' + S.req.items.length + '개</p>',
      'about');
  }

  // ---------- 요청사항 ----------
  function renderMemo() {
    var box = $('#memoGroups');
    box.innerHTML = '';
    S.req.groups.forEach(function (g) {
      var items = S.req.items.filter(function (i) { return i.group === g.id; });
      var sec = h('div', { class: 'memo-group' }, '<h3>' + esc(g.ko) + '</h3>');
      var wrap = h('div', { class: 'chips' });
      items.forEach(function (i) {
        var on = S.memo.indexOf(i.id) >= 0;
        wrap.appendChild(h('button', { class: 'chip' + (on ? ' is-on' : ''), 'data-req': i.id, 'aria-pressed': String(on) },
          esc(i.ko) + '<small lang="zh-CN">' + esc(i.zh) + '</small>'));
      });
      sec.appendChild(wrap);
      box.appendChild(sec);
    });
    updateMemo();
  }

  function memoItems() {
    var byId = {};
    S.req.items.forEach(function (i) { byId[i.id] = i; });
    var list = S.memo.map(function (id) { return byId[id]; }).filter(Boolean);
    // 끝인사는 항상 마지막
    return list.filter(function (i) { return i.id !== 'thanks'; }).concat(list.filter(function (i) { return i.id === 'thanks'; }));
  }
  function memoText() { return memoItems().map(function (i) { return i.zh; }).join('，'); }

  function updateMemo() {
    var items = memoItems();
    $('#memoZh').textContent = items.length ? memoText() : '아래에서 요청사항을 골라 주세요';
    $('#memoZh').classList.toggle('is-empty', !items.length);
    $('#memoKo').textContent = items.map(function (i) { return i.ko; }).join(' · ');
    $('#memoCopy').disabled = $('#memoClear').disabled = !items.length;
  }

  function toggleReq(id) {
    var item = S.req.items.filter(function (i) { return i.id === id; })[0];
    if (!item) return;
    var i = S.memo.indexOf(id);
    if (i >= 0) S.memo.splice(i, 1);
    else {
      if (item.excl) {
        var same = S.req.items.filter(function (x) { return x.excl === item.excl; }).map(function (x) { return x.id; });
        S.memo = S.memo.filter(function (m) { return same.indexOf(m) < 0; });
      }
      S.memo.push(id);
    }
    LS.set('memo', S.memo);
    renderMemo();
  }


  // ---------- 중국어 읽기 ----------
  // 단어장·메뉴·요청사항을 합쳐 사전을 만든다. 같은 표기는 메뉴 > 요청사항 > 단어장 순으로 덮어쓴다.
  function buildDict() {
    var d = {}, max = 1;
    function add(zh, v) { d[zh] = v; if (zh.length > max) max = zh.length; }
    S.gloss.items.forEach(function (g) { add(g.zh, { ko: g.ko }); });
    S.req.items.forEach(function (r) { add(r.zh, { ko: r.ko }); });
    S.foods.forEach(function (f) {
      add(f.zh, { ko: f.ko + (f.spicy ? ' ' + SPICE[f.spicy] : ''), food: f.id });
      if (f.q && f.q !== f.zh) add(f.q, { ko: f.ko, food: f.id });
    });
    S.dict = d; S.dictMax = max;
  }

  // 가장 긴 단어부터 맞춰 나간다. 결과: [{t: 글자, v: 뜻 또는 null}]
  function annotate(text) {
    var out = [], i = 0, chars = Array.from(text);
    while (i < chars.length) {
      var hit = null;
      for (var n = Math.min(S.dictMax, chars.length - i); n >= 1; n--) {
        var w = chars.slice(i, i + n).join('');
        if (S.dict[w]) { hit = { t: w, v: S.dict[w] }; i += n; break; }
      }
      if (hit) out.push(hit);
      else {
        var last = out[out.length - 1];
        if (last && !last.v) last.t += chars[i]; else out.push({ t: chars[i], v: null });
        i++;
      }
    }
    return out;
  }

  function hasHangul(s) { return /[가-힣ㄱ-ㆎ]/.test(s); }

  function renderRead() {
    var v = $('#readIn').value.trim(), out = $('#readOut');
    if (!v || hasHangul(v)) { out.hidden = true; out.innerHTML = ''; renderGloss(); return; }
    var toks = annotate(v), known = 0;
    out.innerHTML = toks.map(function (t) {
      if (!t.v) return '<span class="plain" lang="zh-CN">' + esc(t.t) + '</span>';
      known++;
      return '<span class="tk' + (t.v.food ? ' is-food' : '') + '"' + (t.v.food ? ' data-food="' + esc(t.v.food) + '" role="button" tabindex="0"' : '') + '>' +
        '<span class="tk-zh" lang="zh-CN">' + esc(t.t) + '</span><span class="tk-ko">' + esc(t.v.ko) + '</span></span>';
    }).join('');
    if (!known) out.innerHTML += '<p class="hint">아는 단어를 못 찾았어요. 화면 번역을 써 보세요.</p>';
    out.hidden = false;
    renderGloss();
  }

  function renderGloss() {
    var q = $('#readIn').value.trim(), list = S.gloss.items, box = $('#glossList');
    var filtering = q && hasHangul(q);
    var cats = $('#glossCats');
    cats.innerHTML = '';
    S.gloss.groups.forEach(function (g) {
      var on = !filtering && S.glossGroup === g.id;
      cats.appendChild(h('button', { class: 'cat' + (on ? ' is-on' : ''), role: 'tab', 'aria-selected': String(on), 'data-gg': g.id, style: '--c:#C2462E' }, esc(g.ko)));
    });
    if (filtering) {
      var nq = Hangul.norm(q);
      list = list.filter(function (g) { return Hangul.find(Hangul.norm(g.ko), nq) >= 0; });
    } else list = list.filter(function (g) { return g.group === S.glossGroup; });
    box.innerHTML = list.length ? list.map(function (g) {
      return '<button class="gl" data-zh="' + esc(g.zh) + '"><span class="gl-zh" lang="zh-CN">' + esc(g.zh) + '</span><span class="gl-ko">' + esc(g.ko) + '</span></button>';
    }).join('') : '<p class="empty">단어장에 없어요.</p>';
  }

  // ---------- 화면 전환 ----------
  function setView(v) {
    document.querySelectorAll('.tab').forEach(function (t) {
      var on = t.getAttribute('data-view') === v;
      t.classList.toggle('is-on', on); t.setAttribute('aria-selected', String(on));
    });
    $('#view-menu').hidden = v !== 'menu';
    $('#view-memo').hidden = v !== 'memo';
    $('#view-read').hidden = v !== 'read';
    $('#dock').hidden = v !== 'menu';
    document.body.classList.toggle('no-dock', v !== 'menu');
    window.scrollTo(0, 0);
  }

  // ---------- 이벤트 ----------
  function bind() {
    document.querySelectorAll('.tab').forEach(function (t) {
      t.addEventListener('click', function () { setView(t.getAttribute('data-view')); });
    });

    $('#cats').addEventListener('click', function (e) {
      var b = e.target.closest('[data-cat]');
      if (!b) return;
      S.cat = b.getAttribute('data-cat');
      if (S.cat !== 'recent') LS.set('cat', S.cat);
      if (S.query) { S.query = ''; $('#q').value = ''; $('#qClear').hidden = true; }
      renderCats();
      b = $('#cats [data-cat="' + S.cat + '"]');
      if (b && b.scrollIntoView) b.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
    });

    var qTimer;
    $('#q').addEventListener('input', function (e) {
      clearTimeout(qTimer);
      var v = e.target.value;
      $('#qClear').hidden = !v;
      qTimer = setTimeout(function () { S.query = v.trim(); renderCats(); }, 80);
    });
    $('#qClear').addEventListener('click', function () {
      $('#q').value = ''; S.query = ''; $('#qClear').hidden = true; renderCats(); $('#q').focus();
    });

    $('#grid').addEventListener('click', function (e) {
      var more = e.target.closest('[data-more]');
      if (more) { e.stopPropagation(); showFood(S.foodById[more.getAttribute('data-more')]); return; }
      var c = e.target.closest('.card');
      if (c) copyFood(S.foodById[c.getAttribute('data-id')]);
    });
    $('#grid').addEventListener('keydown', function (e) {
      if ((e.key === 'Enter' || e.key === ' ') && e.target.classList.contains('card')) {
        e.preventDefault(); copyFood(S.foodById[e.target.getAttribute('data-id')]);
      }
    });

    $('#openAppBtn').addEventListener('click', function () { openApp('app'); });
    $('#openWebBtn').addEventListener('click', function () { openApp('web'); });
    $('#openAppBtn').hidden = !Opener.canTryApp();
    $('#installBtn').addEventListener('click', openInstall);
    $('#setupDone').addEventListener('click', function () { LS.set('setupDone', true); $('#setupCard').hidden = true; });
    $('#aboutBtn').addEventListener('click', showAbout);
    $('#sheetClose').addEventListener('click', closeSheet);
    $('#sheetBack').addEventListener('click', closeSheet);
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !$('#sheet').hidden) closeSheet(); });

    $('#sheetBody').addEventListener('click', function (e) {
      var b = e.target.closest('[data-act]');
      if (!b) return;
      var act = b.getAttribute('data-act'), f = S.sheetFood;
      if (act === 'copy' && f) copyFood(f);
      else if (act === 'openApp' || act === 'openWeb') { closeSheet(); openApp(act === 'openApp' ? 'app' : 'web'); }
      else if (act === 'camera') pickPhoto(true);
      else if (act === 'pick') pickPhoto(false);
      else if (act === 'rmphoto' && f) {
        Photos.remove(f.id).then(function () { delete S.photos[f.id]; showFood(f); renderGrid(); toast('사진을 지웠어요'); });
      } else if (act === 'installApp') {
        openInstall();
      } else if (act === 'install' && S.installEvt) {
        S.installEvt.prompt(); S.installEvt = null; closeSheet();
      }
    });

    $('#photoInput').addEventListener('change', function (e) {
      var file = e.target.files && e.target.files[0], f = S.sheetFood;
      if (!file || !f) return;
      Photos.save(f.id, file).then(function () {
        S.photos[f.id] = true;
        showFood(f); renderGrid();
        toast('사진을 저장했어요 (이 기기에만)');
      }).catch(function () { toast('사진을 저장하지 못했어요'); });
    });

    $('#readIn').addEventListener('input', renderRead);
    $('#readPaste').addEventListener('click', function () {
      var fail = function () { $('#readIn').focus(); toast('입력칸을 길게 눌러 붙여넣기 해 주세요'); };
      if (!navigator.clipboard || !navigator.clipboard.readText) return fail();
      navigator.clipboard.readText().then(function (t) {
        if (!t) return fail();
        $('#readIn').value = t; renderRead();
      }, fail);
    });
    $('#readClear').addEventListener('click', function () { $('#readIn').value = ''; renderRead(); $('#readIn').focus(); });
    $('#readOut').addEventListener('click', function (e) {
      var t = e.target.closest('[data-food]');
      if (t) showFood(S.foodById[t.getAttribute('data-food')]);
    });
    $('#glossCats').addEventListener('click', function (e) {
      var b = e.target.closest('[data-gg]');
      if (!b) return;
      S.glossGroup = b.getAttribute('data-gg');
      if (hasHangul($('#readIn').value)) $('#readIn').value = '';
      renderGloss();
    });
    $('#glossList').addEventListener('click', function (e) {
      var b = e.target.closest('[data-zh]');
      if (!b) return;
      var zh = b.getAttribute('data-zh');
      copyText(zh).then(function (ok) { if (ok) toast('복사됨: <b lang="zh-CN">' + esc(zh) + '</b>'); });
    });

    $('#memoGroups').addEventListener('click', function (e) {
      var b = e.target.closest('[data-req]');
      if (b) toggleReq(b.getAttribute('data-req'));
    });
    $('#memoCopy').addEventListener('click', function () {
      var t = memoText();
      copyText(t).then(function (ok) {
        toast(ok ? '메모 복사됨: <b lang="zh-CN">' + esc(t) + '</b>' : '복사가 막혔어요. 위 문구를 길게 눌러 복사해 주세요', 3000);
      });
    });
    $('#memoClear').addEventListener('click', function () { S.memo = []; LS.set('memo', S.memo); renderMemo(); });

    $('#copyUrlBtn').addEventListener('click', function () {
      copyText(location.href).then(function (ok) { toast(ok ? '주소를 복사했어요. 외부 브라우저에 붙여넣어 주세요' : '주소 복사가 막혔어요'); });
    });

    window.addEventListener('beforeinstallprompt', function (e) { e.preventDefault(); S.installEvt = e; });
  }

  function inAppBanner() {
    var env = Opener.env;
    if (!env.inApp) return;
    $('#inappBanner').hidden = false;
    if (env.kakao) $('#inappHow').textContent = '오른쪽 아래(또는 위) ⋮ 메뉴에서 ‘다른 브라우저로 열기’를 눌러 주세요.';
    else if (env.wechat) $('#inappHow').textContent = '오른쪽 위 ⋯ 메뉴에서 ‘브라우저로 열기(在浏览器打开)’를 눌러 주세요.';
  }

  function loadJSON(p) {
    return fetch(p).then(function (r) { if (!r.ok) throw new Error(p + ' ' + r.status); return r.json(); });
  }

  function init() {
    bind();
    inAppBanner();
    $('#setupCard').hidden = !!LS.get('setupDone', false);
    Promise.all([loadJSON('data/categories.json'), loadJSON('data/foods.json'), loadJSON('data/requests.json'), loadJSON('data/glossary.json')])
      .then(function (r) {
        S.cats = r[0]; S.foods = r[1]; S.req = r[2]; S.gloss = r[3];
        S.cats.forEach(function (c) { S.catById[c.id] = c; });
        S.foods.forEach(function (f) { S.foodById[f.id] = f; });
        if (!S.catById[S.cat] && S.cat !== 'recent') S.cat = S.cats[0].id;
        S.recent = S.recent.filter(function (id) { return S.foodById[id]; });
        S.lastCopied = S.foodById[LS.get('lastCopied', '')] || null;
        updateDock();
        renderCats();
        renderMemo();
        buildDict();
        renderGloss();
        return Photos.keys().then(function (keys) {
          keys.forEach(function (k) { S.photos[k] = true; });
          if (keys.length) renderGrid();
        }).catch(function () {});
      })
      .catch(function (err) {
        $('#grid').innerHTML = '<p class="empty">메뉴 데이터를 불러오지 못했어요. 인터넷 연결을 확인하고 다시 열어 주세요.</p>';
        console.error(err);
      });

    if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
      // 새 버전이 설치돼 페이지를 넘겨받으면 한 번 새로고침해서 바로 새 화면을 보여 준다
      var hadController = !!navigator.serviceWorker.controller, reloaded = false;
      navigator.serviceWorker.addEventListener('controllerchange', function () {
        if (!hadController || reloaded) return;
        reloaded = true;
        location.reload();
      });
      navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).catch(function () {});
    }
  }

  window.ChifanApp = { searchFoods: searchFoods, memoText: memoText, annotate: function (t) { return annotate(t); }, state: S };
  init();
})();
