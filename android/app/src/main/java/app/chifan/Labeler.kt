package app.chifan

/**
 * OCR로 읽은 한 줄 → 한국어 라벨.
 *
 * 중국 메뉴 이름은 재료·조리법·형태의 조합이라 문장 번역보다 조각 사전이 잘 맞는다.
 *   回锅肉盖饭 → 회과육 · 덮밥,  重庆小面单人餐 → 충칭 · 샤오미엔 · 1인 세트
 * 순서: 숫자 규칙(4.9折 → 51% 할인) → 사전 가장 긴 말부터 → 남는 한자는 한자음(渝满天 → 투만천).
 * 【】|/ 같은 기호는 조각 경계로만 쓴다.
 */
class Labeler(private val dict: Dict) {
    class Part(val text: String, val kind: Int)   // kind: OverlayView.Label.DICT / FOOD / HANJA / RULE(=DICT)
    class Result(val text: String, val kind: Int, val dictParts: Int, val hanjaChars: Int)

    private class Rule(val re: Regex, val f: (MatchResult) -> String?)
    private val rules = listOf(
        Rule(Regex("""(\d+(?:\.\d+)?)折""")) { m -> val x = m.groupValues[1].toDouble(); if (x in 0.1..9.9) "${Math.round((10 - x) * 10)}% 할인" else null },
        Rule(Regex("""已减(\d+(?:\.\d+)?)元""")) { m -> "${m.groupValues[1]}위안 할인됨" },
        Rule(Regex("""半年售\s*(\d+)\+?""")) { m -> "반년 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("""月售\s*(\d+)\+?""")) { m -> "월 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("""年售\s*(\d+)\+?""")) { m -> "연 ${m.groupValues[1]}+ 판매" },
        Rule(Regex("""(\d+)%好评""")) { m -> "좋은 평 ${m.groupValues[1]}%" },
        Rule(Regex("""(\d+)选(\d+)""")) { m -> "${m.groupValues[1]}개 중 ${m.groupValues[2]}개 선택" },
        Rule(Regex("""(\d+)人餐""")) { m -> "${m.groupValues[1]}인 세트" },
        Rule(Regex("""(\d+(?:\.\d+)?)元""")) { m -> "${m.groupValues[1]}위안" },
        Rule(Regex("""[¥￥]\s*\d+(?:\.\d+)?""")) { null },   // 가격 숫자는 그대로 읽히니 라벨 없음
        Rule(Regex("""\d+(?:\.\d+)?""")) { null }
    )

    fun label(raw: String): Result? {
        val s = raw.trim()
        if (!s.any { isHan(it) }) return null
        val parts = ArrayList<Part>()
        var hanjaBuf = StringBuilder(); var hanjaChars = 0; var dictParts = 0; var anyFood = false
        fun flushHanja() { if (hanjaBuf.isNotEmpty()) { parts.add(Part(hanjaBuf.toString(), OverlayView.Label.HANJA)); hanjaBuf = StringBuilder() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // 1) 숫자 규칙 (사전보다 먼저: 已减11元 → 11위안 할인됨)
            var matched = false
            for (r in rules) {
                val m = r.re.find(s, i) ?: continue
                if (m.range.first != i) continue
                flushHanja()
                r.f(m)?.let { parts.add(Part(it, OverlayView.Label.DICT)); dictParts++ }
                i = m.range.last + 1; matched = true; break
            }
            if (matched) continue
            if (!isHan(c)) { flushHanja(); i++; continue }   // 기호·영문은 경계
            // 2) 사전: 가장 긴 말부터 (규칙에 걸리는 말은 사전보다 규칙 우선이므로 숫자 뒤 글자는 위에서 처리됨)
            val hit = longest(s, i)
            if (hit != null) {
                flushHanja()
                parts.add(Part(stripNote(hit.ko), if (hit.foodId != null) OverlayView.Label.FOOD else OverlayView.Label.DICT))
                dictParts++; if (hit.foodId != null) anyFood = true
                i += hit.zh.length; continue
            }
            // 3) 한자음
            val r = dict.reading(c)
            if (r != null) { hanjaBuf.append(r); hanjaChars++ }
            i++
        }
        flushHanja()
        if (parts.isEmpty()) return null
        // 사전 조각이 하나도 없고 한자음만 길게 이어지면(설명문) 소리만 보여 줘 봐야 소음이다
        if (dictParts == 0 && hanjaChars > 6) return null
        val text = parts.joinToString(" · ") { it.text }
        val kind = when { anyFood -> OverlayView.Label.FOOD; dictParts > 0 -> OverlayView.Label.DICT; else -> OverlayView.Label.HANJA }
        return Result(if (text.length > 34) text.substring(0, 33) + "…" else text, kind, dictParts, hanjaChars)
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
