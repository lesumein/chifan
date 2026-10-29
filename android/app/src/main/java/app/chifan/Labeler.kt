package app.chifan

/**
 * OCR로 읽은 한 줄 → 한국어 라벨.  (tests/sim/labeler.py 와 같은 규칙. 바꾸면 둘 다 바꾼다)
 *
 * 중국 메뉴 이름은 재료·조리법·형태의 조합이라 문장 번역보다 조각 사전이 잘 맞는다.
 *   回锅肉盖饭 → 회과육 · 덮밥,  重庆小面单人餐 → 충칭 샤오면 · 1인 세트
 * 순서: 숫자 규칙(4.9折 → -51%, 满30减5 → 30↑ -5, 约35分钟 → 약 35분) → 사전 가장 긴 말부터. 가격·숫자는 그대로 둔다.
 * 덮어쓰기 라벨(text)에는 2자 이상 사전 조각이나 뜻 있는 규칙이 하나는 있어야 하고, 한자음은 넣지 않는다
 * (잘못 읽힌 글자·사진 속 글씨의 한자음이 전부 소음이 됐다). 한자음은 '읽은 글자' 탭용 full 에만 넣는다.
 * 단어장의 short(짧은 표기)가 있으면 라벨에는 그것을 쓴다 (微辣 → 약간, 选规格 → 옵션).
 */
class Labeler(private val dict: Dict) {
    /** text: 덮어쓰기 라벨 (없으면 null), full: 한자음까지 포함한 전체 풀이, known: 사전이 충분히 읽은 줄(기계 번역이 끼어들지 않음) */
    class Result(val text: String?, val full: String, val kind: Int, val known: Boolean = true)

    private class Rule(val re: Regex, val keep: Boolean = false, val f: ((MatchResult) -> String?)? = null)
    private fun r(p: String, f: (MatchResult) -> String?) = Rule(Regex(p), false, f)
    private fun keep(p: String) = Rule(Regex(p), true)
    private val rules = listOf(
        r("(\\d+(?:\\.\\d+)?)折") { m -> val x = m.groupValues[1].toDouble(); if (x in 0.1..9.9) "-${Math.round((10 - x) * 10)}%" else null },
        r("已减(\\d+(?:\\.\\d+)?)元") { m -> "¥${m.groupValues[1]} 할인됨" },
        r("已优惠[¥￥]?(\\d+(?:\\.\\d+)?)") { m -> "¥${m.groupValues[1]} 할인됨" },
        r("还差[¥￥]?(\\d+(?:\\.\\d+)?)起送") { m -> "¥${m.groupValues[1]} 더 담으면 주문 가능" },
        r("[¥￥]?(\\d+(?:\\.\\d+)?)起送") { m -> "최소 ¥${m.groupValues[1]}" },
        r("起送[¥￥](\\d+(?:\\.\\d+)?)") { m -> "최소 ¥${m.groupValues[1]}" },
        r("配送费[¥￥](\\d+(?:\\.\\d+)?)") { m -> "배달비 ¥${m.groupValues[1]}" },
        r("人均[¥￥]?(\\d+(?:\\.\\d+)?)") { m -> "1인 ¥${m.groupValues[1]}" },
        r("半年售\\s*(\\d+)\\+?") { m -> "반년 ${m.groupValues[1]}+ 판매" },
        r("月售\\s*(\\d+)(\\+?)") { m -> "월 ${m.groupValues[1]}${m.groupValues[2]} 판매" },
        r("年售\\s*(\\d+)\\+?") { m -> "연 ${m.groupValues[1]}+ 판매" },
        r("季售\\s*(\\d+)\\+?") { m -> "시즌 ${m.groupValues[1]}+ 판매" },
        r("已售\\s*(\\d+)\\+?") { m -> "${m.groupValues[1]}+ 판매" },
        r("(\\d+)条评价") { m -> "리뷰 ${m.groupValues[1]}개" },
        r("(\\d+)%\\s*好评率?") { m -> "호평 ${m.groupValues[1]}%" },
        r("好评\\s*(\\d+)%") { m -> "호평 ${m.groupValues[1]}%" },
        r("仅剩\\s*(\\d+:\\d+:\\d+)") { m -> "${m.groupValues[1]} 남음" },
        r("仅剩\\s*(\\d+)\\s*份") { m -> "${m.groupValues[1]}개 남음" },
        keep("\\d+:\\d+:\\d+"),
        r("(\\d+)瓶") { m -> "${m.groupValues[1]}병" },
        r("(\\d+)份") { m -> "${m.groupValues[1]}인분" },
        keep("\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2}"),              // 날짜
        r("好评率\\s*(\\d+)%") { m -> "호평 ${m.groupValues[1]}%" },
        r("(\\d+)选(\\d+)") { m -> "${m.groupValues[1]}택${m.groupValues[2]}" },
        r("(\\d+)\\s*[-~]\\s*(\\d+)\\s*人餐") { m -> "${m.groupValues[1]}~${m.groupValues[2]}인 세트" },
        r("(\\d+)\\s*人餐") { m -> "${m.groupValues[1]}인 세트" },
        r("约\\s*(\\d+)\\s*克") { m -> "약 ${m.groupValues[1]}g" },
        r("(\\d+)\\s*克") { m -> "${m.groupValues[1]}g" },
        r("满(\\d+)减(\\d+)") { m -> "${m.groupValues[1]}↑ -${m.groupValues[2]}" },
        r("立减(\\d+)元?") { m -> "즉시 -${m.groupValues[1]}" },
        r("(\\d+)分钟送达") { m -> "${m.groupValues[1]}분 내 배달" },
        r("约\\s*(\\d+)分钟") { m -> "약 ${m.groupValues[1]}분" },
        r("(\\d+)分钟") { m -> "${m.groupValues[1]}분" },
        r("(\\d+(?:\\.\\d+)?)分(?!钟)") { m -> "${m.groupValues[1]}점" },
        r("预计\\s*(\\d+:\\d+)\\s*送达") { m -> "${m.groupValues[1]} 도착 예정" },
        r("距您\\s*(\\d+(?:\\.\\d+)?)\\s*km") { m -> "${m.groupValues[1]}km 거리" },
        r("(\\d+(?:\\.\\d+)?)\\s*km") { m -> "${m.groupValues[1]}km" },
        r("第(\\d+)名") { m -> "${m.groupValues[1]}위" },
        r("(\\d+)人份") { m -> "${m.groupValues[1]}인분" },
        r("(\\d+)号楼") { m -> "${m.groupValues[1]}동" },
        r("(\\d+)个") { m -> "${m.groupValues[1]}개" },
        r("(\\d+)\\s*次") { m -> "${m.groupValues[1]}회" },
        r("[xX×]\\s*(\\d+)") { m -> "x${m.groupValues[1]}" },
        r("再减(\\d+(?:\\.\\d+)?)元") { m -> "¥${m.groupValues[1]} 추가 할인" },
        r("省(?:了)?(\\d+(?:\\.\\d+)?)元") { m -> "¥${m.groupValues[1]} 절약" },
        r("(\\d+(?:\\.\\d+)?)元") { m -> "¥${m.groupValues[1]}" },
        r("(\\d+)天低价") { m -> "${m.groupValues[1]}일 최저가" },
        r("购买之后(\\d+)天有效") { m -> "구매 후 ${m.groupValues[1]}일 유효" },
        r("(\\d+)天") { m -> "${m.groupValues[1]}일" },
        r("(\\d+)人推荐") { m -> "${m.groupValues[1]}명 추천" },
        r("(\\d+)家门店通用") { m -> "${m.groupValues[1]}개 매장 공통" },
        r("半年销量\\s*(\\d+)(\\+?)") { m -> "반년 ${m.groupValues[1]}${m.groupValues[2]} 판매" },
        r("[(（]?[一-鿿]{2,8}店[)）]?$") { _ -> "" },               // 지점 이름(鲜鱼口店): 뜻 없이 지나가되 '읽은 것'으로 친다
        keep("\\d+:\\d+\\s*[-~－]\\s*\\d+:\\d+"),            // 07:00-21:00
        keep("[+＋-]\\s*[¥￥]\\s*\\d+(?:\\.\\d+)?"),      // +¥4 같은 추가 요금
        keep("[¥￥]\\s*\\d+(?:\\.\\d+)?"),               // 가격은 그대로
        keep("\\d+:\\d+"),
        keep("\\d+(?:\\.\\d+)?(?:ml|ML|L|g|kg|G)\\b"),
        keep("\\d+(?:\\.\\d+)?\\+?")                     // 그 밖의 숫자도 그대로
    )

    private val priceStart = Regex("^[+＋-]?\\s*[¥￥]\\s*\\d")

    /**
     * 사전·규칙이 읽은 한자가 줄의 한자 60%(12자 넘는 문장은 75%) 이상이어야 덮는다 — 모르는 문장(리뷰·안내문)에
     * 조각 몇 개만 붙여 덮으면 원문도 뜻도 못 읽는다. 가격으로 시작하는 줄(¥1.79 6折 ¥3)은 덮지 않는다 (full 은 만든다).
     */
    fun label(raw: String): Result? {
        val s = raw.trim()
        if (!s.any { isHan(it) }) return null
        val parts = ArrayList<String>(); val fullParts = ArrayList<String>()
        var hanja = StringBuilder(); var strong = 0; var anyFood = false
        var covered = 0; val total = s.count { isHan(it) }
        fun flushHanja() { if (hanja.isNotEmpty()) { fullParts.add(hanja.toString()); hanja = StringBuilder() } }
        var i = 0
        while (i < s.length) {
            var matched = false
            for (rule in rules) {
                val m = rule.re.find(s, i) ?: continue
                if (m.range.first != i) continue
                flushHanja()
                if (rule.keep) { val t = m.value.replace("￥", "¥").replace(" ", ""); parts.add(t); fullParts.add(t) }
                else {
                    covered += m.value.count { isHan(it) }
                    rule.f!!(m)?.let { if (it.isNotEmpty()) { parts.add(it); fullParts.add(it); strong++ } }
                }
                i = m.range.last + 1; matched = true; break
            }
            if (matched) continue
            val c = s[i]
            if (!isHan(c)) { flushHanja(); i++; continue }
            val hit = longest(s, i)
            if (hit != null) {
                flushHanja()
                val ko = stripNote(hit.short).replace("🌶", "").trim()
                covered += hit.zh.length
                fullParts.add(stripNote(hit.ko).ifEmpty { hit.ko })
                if (ko.isNotEmpty()) {
                    parts.add(ko)
                    if (hit.zh.length >= 2 || hit.foodId != null || s.length == 1) strong++   // 한 글자 칩(热·大·小)은 줄 전체가 그 글자일 때만
                    if (hit.foodId != null) anyFood = true
                }
                i += hit.zh.length; continue
            }
            dict.reading(c)?.let { hanja.append(it) }
            i++
        }
        flushHanja()
        if (fullParts.isEmpty()) return null
        val full = fullParts.joinToString(" · ")
        val kind = if (anyFood) OverlayView.Label.FOOD else OverlayView.Label.DICT
        val need = if (total > 12) 0.75f else 0.6f
        val price = priceStart.containsMatchIn(s)
        val coverOk = covered >= need * total && !price
        val text = if (parts.isEmpty() || strong == 0 || !coverOk) null else parts.joinToString(" · ")
        // 가격 줄·지점 이름처럼 '읽었지만 덮을 게 없는' 줄은 known: 기계 번역이 엉뚱한 말을 만들지 않게
        val known = price || covered >= need * total
        return Result(text, full, kind, known)
    }

    /** 라벨용 뜻: 괄호 설명은 뗀다 ("고명 (면 위 토핑)" → "고명"). 괄호뿐인 뜻("(메이퇀)")은 덮지 않는다 → "" */
    private fun stripNote(ko: String) = ko.replace(Regex("\\s*[(（][^)）]*[)）]"), "").trim()

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
