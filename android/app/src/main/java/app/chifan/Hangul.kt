package app.chifan

import java.text.Normalizer

/** 한국어 검색: 초성(ㅁㄹㅌ), 부분 일치, 입력 중인 마지막 글자(말 → 마라)까지 맞춘다. js/hangul.js와 같은 규칙. */
object Hangul {
    private val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    // 받침 인덱스(1~27) → 다음 글자 초성으로 넘어갈 수 있는 자음 (겹받침은 뒷자음)
    private val JONG_TO_CHO = arrayOf(null, 'ㄱ', 'ㄲ', 'ㅅ', 'ㄴ', 'ㅈ', 'ㅎ', 'ㄷ', 'ㄹ', 'ㄱ', 'ㅁ', 'ㅂ', 'ㅅ', 'ㅌ', 'ㅍ', 'ㅎ',
        'ㅁ', 'ㅂ', 'ㅅ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ')

    private class Syl(val cho: Int, val jung: Int, val jong: Int)

    private fun syl(ch: Char): Syl? {
        val code = ch.code - 0xAC00
        if (code < 0 || code > 11171) return null
        return Syl(code / 588, (code % 588) / 28, code % 28)
    }

    private fun charMatch(t: Char, tNext: Char?, q: Char, last: Boolean): Boolean {
        if (t == q) return true
        if (CHO.indexOf(q) >= 0) {
            val ts = syl(t) ?: return false
            return CHO[ts.cho] == q
        }
        if (!last) return false
        val a = syl(t) ?: return false
        val b = syl(q) ?: return false
        if (a.cho != b.cho || a.jung != b.jung) return false
        if (b.jong == 0) return true
        if (a.jong == 0 && tNext != null) {
            val n = syl(tNext) ?: return false
            return CHO[n.cho] == JONG_TO_CHO[b.jong]
        }
        return false
    }

    /** target 안에서 query가 처음 나오는 위치(-1 = 없음) */
    fun find(target: String, query: String): Int {
        if (query.isEmpty()) return 0
        for (i in 0..(target.length - query.length)) {
            var ok = true
            for (j in query.indices) {
                val next = if (i + j + 1 < target.length) target[i + j + 1] else null
                if (!charMatch(target[i + j], next, query[j], j == query.length - 1)) { ok = false; break }
            }
            if (ok) return i
        }
        return -1
    }

    private val STRIP = Regex("[\\s·()（）'’\\-]")
    fun norm(s: String): String = s.lowercase().replace(STRIP, "")
    fun normPy(s: String): String =
        norm(Normalizer.normalize(s.replace('ü', 'v'), Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), ""))

    fun hasHangul(s: String) = s.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
}
