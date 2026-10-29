package app.chifan

/**
 * OCR로 읽은 한 줄 → 한국어 라벨.  (tests/sim/labeler.py 와 같은 규칙. 바꾸면 둘 다 바꾼다)
 *
 * 중국 메뉴 이름은 재료·조리법·형태의 조합이라 문장 번역보다 조각 사전이 잘 맞는다.
 *   回锅肉盖饭 → 회과육 · 덮밥,  重庆小面单人餐 → 충칭 샤오면 · 1인 세트
 * 순서: 숫자 규칙(4.9折 → -51%, 2选1 → 2택1) → 사전 가장 긴 말부터. 【】|/ 같은 기호는 조각 경계.
 * 덮어쓰기 라벨(text)에는 2자 이상 사전 조각이나 규칙이 하나는 있어야 하고, 한자음은 넣지 않는다
 * (잘못 읽힌 글자·사진 속 글씨의 한자음이 전부 소음이 됐다). 한자음은 '읽은 글자' 탭용 full 에만 넣는다.
 * 가격(¥12)이 섞인 줄은 덮지 않는다 — 가격이 더 중요하다.
 */
class Labeler(private val dict: Dict) {
    /** text: 덮어쓰기 라벨 (없으면 null), full: 한자음까지 포함한 전체 풀이 */
    class Result(val text: String?, val full: String, val kind: Int)

    private class Rule(val re: Regex, val f: (MatchResult) -> String?)
    private val rules = listOf(
        Rule(Regex("(\\d+(?:\\.\\d+)?)折")) { m -> val x = m.groupValues[1].toDouble(); if (x in 0.1..9.9) "-${Math.round((10 - x) * 10)}%" else null },
        Rule(Regex("已减(\\d+(?:\\.\\d+)?)元")) { m -> "${m.groupValues[1]}위안 할인됨" },
        Rule(Regex("半年售\\s*(\\d+)\\+?")) { m -> "반년 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("月售\\s*(\\d+)\\+?")) { m -> "월 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("年售\\s*(\\d+)\\+?")) { m -> "연 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("(\\d+)%\\s*好评")) { m -> "좋은 평 ${m.groupValues[1]}%" },
        Rule(Regex("(\\d+)选(\\d+)")) { m -> "${m.groupValues[1]}택${m.groupValues[2]}" },
        Rule(Regex("(\\d+)人餐")) { m -> "${m.groupValues[1]}인 세트" },
        Rule(Regex("季售\\s*(\\d+)\\+?")) { m -> "시즌 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("(\\d+)条评价")) { m -> "리뷰 ${m.groupValues[1]}개" },
        Rule(Regex("(\\d+)分钟送达")) { m -> "${m.groupValues[1]}분 내 배달" },
        Rule(Regex("[¥￥]\\s*\\d+(?:\\.\\d+)?")) { null },
        Rule(Regex("\\d+(?:\\.\\d+)?")) { null }
    )
    private val priceRe = Regex("[¥￥]\\s*\\d")

    fun label(raw: String): Result? {
        val s = raw.trim()
        if (!s.any { isHan(it) }) return null
        val parts = ArrayList<String>(); val fullParts = ArrayList<String>()
        var hanja = StringBuilder(); var strong = 0; var anyFood = false
        fun flushHanja() { if (hanja.isNotEmpty()) { fullParts.add(hanja.toString()); hanja = StringBuilder() } }
        var i = 0
        while (i < s.length) {
            var matched = false
            for (r in rules) {
                val m = r.re.find(s, i) ?: continue
                if (m.range.first != i) continue
                flushHanja()
                r.f(m)?.let { parts.add(it); fullParts.add(it); strong++ }
                i = m.range.last + 1; matched = true; break
            }
            if (matched) continue
            val c = s[i]
            if (!isHan(c)) { flushHanja(); i++; continue }
            val hit = longest(s, i)
            if (hit != null) {
                flushHanja()
                val ko = stripNote(hit.ko).replace("🌶", "").trim()
                parts.add(ko); fullParts.add(ko)
                if (hit.zh.length >= 2 || hit.foodId != null) strong++
                if (hit.foodId != null) anyFood = true
                i += hit.zh.length; continue
            }
            dict.reading(c)?.let { hanja.append(it) }
            i++
        }
        flushHanja()
        if (fullParts.isEmpty()) return null
        val full = fullParts.joinToString(" · ")
        val kind = if (anyFood) OverlayView.Label.FOOD else OverlayView.Label.DICT
        val text = if (parts.isEmpty() || strong == 0 || priceRe.containsMatchIn(s)) null else parts.joinToString(" · ")
        return Result(text, full, kind)
    }

    /** 라벨용 뜻: 괄호 설명은 뗀다 ("고명 (면 위 토핑)" → "고명") */
    private fun stripNote(ko: String) = ko.replace(Regex("\\s*[(（][^)）]*[)）]"), "").trim().ifEmpty { ko }

    /**
     * s[i..]에서 사전에 있는 가장 긴 말. 한 글자짜리 말(满·减·元…)은 앞뒤에 한자가 없을 때만 —
     * 안 그러면 渝满天 같은 가게 이름이 "투 · ~이상이면 · 천"으로 쪼개진다.
     */
    private fun longest(s: String, i: Int): Hit? {
        var n = minOf(dict.maxLen, s.length - i)
        while (n >= 1) {
            val w = s.substring(i, i + n)
            if (w.all { isHan(it) }) {
                val h = dict.exact(w)
                if (h != null) {
                    if (n > 1) return h
                    val prevHan = i > 0 && isHan(s[i - 1]); val nextHan = i + 1 < s.length && isHan(s[i + 1])
                    if (!prevHan && !nextHan) return h
                }
            }
            n--
        }
        return null
    }

    private fun isHan(c: Char) = c in '㐀'..'鿿'
}
