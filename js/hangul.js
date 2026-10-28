// 한국어 검색: 초성(ㅁㄹㅌ), 부분 일치, 입력 중인 마지막 글자(마ㄹ → 마라 / 말 → 마라)까지 맞춘다.
(function () {
  var CHO = ['ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
  // 받침 인덱스(1~27) → 다음 글자 초성으로 넘어갈 수 있는 자음 (겹받침은 뒷자음)
  var JONG_TO_CHO = [null,'ㄱ','ㄲ','ㅅ','ㄴ','ㅈ','ㅎ','ㄷ','ㄹ','ㄱ','ㅁ','ㅂ','ㅅ','ㅌ','ㅍ','ㅎ','ㅁ','ㅂ','ㅅ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'];
  var CHO_SET = {};
  CHO.forEach(function (c) { CHO_SET[c] = true; });

  function syl(ch) {
    var code = ch.charCodeAt(0) - 0xAC00;
    if (code < 0 || code > 11171) return null;
    return { cho: Math.floor(code / 588), jung: Math.floor((code % 588) / 28), jong: code % 28 };
  }

  // 글자 하나 비교. last = 검색어의 마지막 글자(아직 입력 중일 수 있음)
  function charMatch(t, tNext, q, last) {
    if (t === q) return true;
    if (CHO_SET[q]) {
      var ts = syl(t);
      return ts ? CHO[ts.cho] === q : t === q;
    }
    if (!last) return false;
    var a = syl(t), b = syl(q);
    if (!a || !b || a.cho !== b.cho || a.jung !== b.jung) return false;
    if (b.jong === 0) return true;                       // "마" → 막, 만, 마
    if (a.jong === 0 && tNext) {                          // "말" → 마라
      var n = syl(tNext);
      return !!n && CHO[n.cho] === JONG_TO_CHO[b.jong];
    }
    return false;
  }

  // target 안에서 query가 처음 나오는 위치(-1 = 없음)
  function find(target, query) {
    if (!query) return 0;
    var T = Array.from(target), Q = Array.from(query);
    for (var i = 0; i + Q.length <= T.length; i++) {
      var ok = true;
      for (var j = 0; j < Q.length; j++) {
        if (!charMatch(T[i + j], T[i + j + 1], Q[j], j === Q.length - 1)) { ok = false; break; }
      }
      if (ok) return i;
    }
    return -1;
  }

  // 공백·기호 제거. 한글은 NFD로 풀면 자모로 쪼개지므로 여기선 건드리지 않는다.
  function norm(s) {
    return String(s || '').toLowerCase().replace(/[\s\u00b7()\uff08\uff09'\u2019\-]/g, '');
  }

  // 병음: 성조 기호 제거 (ü → v)
  function normPy(s) {
    return norm(String(s || '').replace(/\u00fc/g, 'v').normalize('NFD').replace(/[\u0300-\u036f]/g, ''));
  }

  window.Hangul = { find: find, norm: norm, normPy: normPy };
})();
