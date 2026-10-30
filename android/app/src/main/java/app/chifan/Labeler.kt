package app.chifan

/**
 * OCR로 읽은 한 줄 → 한국어 라벨.  (tests/sim/labeler.py 와 같은 규칙. 바꾸면 둘 다 바꾼다 — LabelerParityTest 가 확인)
 *
 * 1단계(단어): 숫자 규칙 → 사전의 가장 긴 말. 메뉴 이름은 재료·조리법·형태의 조합이라 조각 사전이 잘 맞는다.
 *   回锅肉盖饭 → 회과육 · 덮밥. 2자 이상 말이나 뜻 있는 규칙이 하나는 있어야 하고, 한자를 충분히 읽어야(짧은 이름 50%,
 *   문장 75%) 덮는다. 짧은 이름은 끝 글자(음식 본체)를 읽어야 한다.
 * 2단계(글자 뜻): 단어로 다 못 읽은 줄은 빈 글자를 글자 뜻(data/zi.json)으로 채운 결과를 우선 쓴다. 한 글자 사전 항목도 이때는
 *   어디서나 쓰고, 뜻 표에도 없는 드문 글자는 한 줄에 3자까지 한국 한자음으로 읽는다. 한자의 90% 이상을 읽어야 한다.
 *   글자 뜻이 절반 이상이면 회색 띠(직역).
 * 번체는 먼저 간체로 바꾼다 (data/t2s.json). 가격으로 시작하는 줄은 덮지 않는다.
 */
class Labeler(private val dict: Lexicon) {
    /** text: 덮어쓰기 라벨 (없으면 null), full: 읽은 글자 탭용 풀이, known: 사전이 충분히 읽은 줄, literal: 글자 뜻이 많이 섞인 직역 */
    class Result(val text: String?, val full: String, val kind: Int, val known: Boolean = true, val literal: Boolean = false)

    private class Rule(val re: Regex, val keep: Boolean = false, val f: ((MatchResult) -> String?)? = null)
    private fun r(p: String, f: (MatchResult) -> String?) = Rule(Regex(p), false, f)
    private fun keep(p: String) = Rule(Regex(p), true)
    /** 한자 숫자 → 아라비아 숫자 (三十六 → 36) */
    private val cnum = Regex("(?=[一二三四五六七八九两十百千]{2})[一二三四五六七八九两]?[十百千](?:[一二三四五六七八九两]?[十百])*[一二三四五六七八九]?")
    private fun cnumValue(t: String): String {
        val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0; var cur = 0
        for (c in t) { val v = d[c]; if (v != null) cur = v else { total += (if (cur == 0) 1 else cur) * when (c) { '十' -> 10; '百' -> 100; else -> 1000 }; cur = 0 } }
        return (total + cur).toString()
    }

    private val rules = listOf(
        Rule(cnum, false) { m -> cnumValue(m.value) },
        r("(\\d+(?:\\.\\d+)?)折") { m -> zhe(m.groupValues[1]) },
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
        r("[Tt]骨") { _ -> "티본" },
        r("(\\d+)\\s*-\\s*(\\d+)(?=专区|神券|神劵|券)") { m -> "${m.groupValues[1]}↑ -${m.groupValues[2]}" },
        r("立省\\s*[¥￥]?\\s*(\\d+(?:\\.\\d+)?)") { m -> "¥${m.groupValues[1]} 즉시 할인" },
        r("(\\d+:\\d+)\\s*后配送") { m -> "${m.groupValues[1]} 이후 배달" },
        r("(\\d+)人觉得") { m -> "${m.groupValues[1]}명이" },
        r("低至\\s*[¥￥]?(\\d+(?:\\.\\d+)?)") { m -> "최저 ¥${m.groupValues[1]}" },
        r("(\\d+)号(?!楼)") { m -> "${m.groupValues[1]}번지" },
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
        r("(\\d+(?:\\.\\d+)?)\\s*(?:公里|千米)") { m -> "${m.groupValues[1]}km" },
        r("(\\d+(?:\\.\\d+)?)\\s*米") { m -> "${m.groupValues[1]}m" },
        r("(\\d+)\\s*两") { m -> "${m.groupValues[1]}량(${m.groupValues[1].toInt() * 50}g)" },
        r("(\\d+(?:\\.\\d+)?)\\s*斤") { m -> "${m.groupValues[1]}근" },
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
        r("[(（][一-鿿]{2,8}店[)）]") { _ -> "" },               // (鲜鱼口店) 괄호 속 지점 이름: 뜻 없이 지나간다
        keep("\\d+:\\d+\\s*[-~－]\\s*\\d+:\\d+"),            // 07:00-21:00
        keep("[+＋-]\\s*[¥￥]\\s*\\d+(?:\\.\\d+)?"),      // +¥4 같은 추가 요금
        keep("[¥￥]\\s*\\d+(?:\\.\\d+)?"),               // 가격은 그대로
        keep("\\d+:\\d+"),
        keep("\\d+(?:\\.\\d+)?(?:ml|ML|L|g|kg|G)\\b"),
        keep("\\d+(?:\\.\\d+)?\\+?")                     // 그 밖의 숫자도 그대로
    )

    /** N折 → -X%. 10 이상이면 OCR이 앞의 ↓ 화살표를 숫자로 읽은 것(44.1折): 앞자리를 버린다 */
    private fun zhe(v: String): String? {
        var x = v.toDouble()
        if (x > 9.9 && '.' in v && v.substringBefore('.').length >= 2) x = v.takeLast(3).toDouble()
        return if (x in 0.1..9.9) "-${Math.round((10 - x) * 10)}%" else null
    }

    private val priceStart = Regex("^[+＋-]?\\s*[¥￥]\\s*\\d")

    private class Pass(val text: String, val kind: Int, val covered: Int, val total: Int)

    /** 폰 OCR(ML Kit)이 자주 틀리게 읽는 글자 묶음 (2026-10-30 폰 화면: 月售를 月昔으로) */
    private val ocrFix = listOf("月昔" to "月售", "已昔" to "已售", "年昔" to "年售", "选本格" to "选规格", "选规恪" to "选规格", "选现格" to "选规格")
    private fun fixOcr(t: String): String { var r = t; for ((a, b) in ocrFix) r = r.replace(a, b); return r }

    fun label(raw0: String): Result? {
        val raw = fixOcr(dict.simp(raw0))
        val s = raw.trim()
        if (!s.any { isHan(it) } || priceStart.containsMatchIn(s)) return null
        val total = s.count { isHan(it) }
        val w = pass(s, false)
        if (w == null || w.covered < total) {
            val z = pass(s, true)          // 단어로 다 못 읽었으면 빈 글자를 뜻으로 채운 쪽을 우선 (清蒸猪舌汤: "찜 · 탕" → 돼지 혀까지)
            if (z != null) return Result(z.text, z.text, z.kind, true, z.kind == OverlayView.Label.HANJA)
        }
        if (w != null) return Result(w.text, w.text, w.kind, true)
        return Result(null, s, OverlayView.Label.DICT, known = false)
    }

    private fun pass(s: String, useZi: Boolean): Pass? {
        val parts = ArrayList<String>(); val kinds = ArrayList<Int>()
        var strong = 0; var covered = 0; var covEnd = 0
        val total = s.count { isHan(it) }
        val zrun = StringBuilder()
        var multi = false   // 두 글자 이상 말이나 숫자 규칙이 있었나
        var loose = 0       // 일반 한 글자·글자 뜻·발음으로 읽은 한자 수 (잡음 판정)
        val DICT = OverlayView.Label.DICT; val FOOD = OverlayView.Label.FOOD; val HANJA = OverlayView.Label.HANJA
        fun flush() {
            if (zrun.isEmpty()) return
            // 뜻 있는 글자는 뜻, 소리만 빌린 글자('~')·뜻 표에 없는 드문 글자는 한자음으로 이어 읽는다
            val out = ArrayList<String>(); val snd = StringBuilder()
            for (c in zrun) {
                val m = dict.zi(c) ?: "~"
                if (m == "~") {   // 소리만 빌린 글자: 중국어 발음 (慕斯 → 무쓰), 뜻 표에 없는 드문 글자: 한국 한자음
                    snd.append(if (dict.zi(c) != null) (dict.sound(c) ?: dict.hanja(c) ?: "") else (dict.hanja(c) ?: dict.sound(c) ?: ""))
                    continue
                }
                if (snd.isNotEmpty()) { out.add(snd.toString()); snd.clear() }
                if (m != "-") out.add(m)
            }
            if (snd.isNotEmpty()) out.add(snd.toString())
            if (out.isNotEmpty()) { parts.add(out.joinToString(" ")); kinds.add(HANJA) }
            zrun.clear()
        }
        // 줄 전체에서 가장 자연스러운 분절을 고른다 (동적 계획법, tests/sim/labeler.py _segment 와 같음).
        // 0.26까지는 앞에서부터 가장 긴 말을 먼저 잡아 咖喱鸡肉 → 咖喱鸡+肉("카레 닭 · 고기")처럼 틀렸다.
        for (e in segment(s, useZi)) {
            when (e.kind) {
                Seg.RULE -> {
                    val m = e.m!!; val rule = e.rule!!
                    if (m.value.any { isHan(it) } || !rule.keep) multi = true
                    flush()
                    if (rule.keep) { parts.add(m.value.replace("￥", "¥").replace(" ", "")); kinds.add(DICT) }
                    else {
                        val t = rule.f!!(m)
                        covered += m.value.count { isHan(it) }
                        if (m.value.any { isHan(it) }) covEnd = m.range.last + 1
                        if (!t.isNullOrEmpty()) { parts.add(t); kinds.add(DICT); strong++ }
                    }
                }
                Seg.SKIP -> if (isHan(s[e.start])) flush()
                Seg.TRANSLIT -> {
                    flush()
                    parts.add(buildString { for (k in e.start until e.end) append(dict.sound(s[k]) ?: dict.hanja(s[k]) ?: "") }); kinds.add(HANJA)
                    covered += e.end - e.start; covEnd = e.end; loose += e.end - e.start
                }
                Seg.WORD -> {
                    val hit = e.hit!!
                    if (hit.zh.length >= 2) multi = true else if (hit.zh[0] !in BOUND) loose++
                    flush()
                    val ko = stripNote(hit.short).replace("🌶", "").trim()
                    covered += hit.zh.length; covEnd = e.end
                    if (ko.isNotEmpty()) {
                        parts.add(ko); kinds.add(if (hit.foodId != null) FOOD else DICT)
                        if (hit.zh.length >= 2 || hit.foodId != null || s.length == 1) strong++   // 한 글자 칩(热·大·小)은 줄 전체가 그 글자일 때만
                    }
                }
                Seg.ZI, Seg.RARE -> { zrun.append(s[e.start]); covered++; covEnd = e.end; loose++ }
            }
        }
        flush()
        if (useZi) {
            // 글자 뜻 단계: 거의 모든 한자를 읽었을 때만 (모르는 글자 하나쯤은 허용)
            if (parts.isEmpty() || covered < maxOf(total - 1.0, 0.9 * total)) return null
            // 잡음 막기: 두 글자 이상 말·숫자 규칙이 하나도 없고 일반 한 글자·직역이 70%를 넘으면 덮지 않는다 (한자 3자 이하 제외).
            // 폰에서 사진 속 글씨·화면 위에 잘린 줄이 "막 · 찾은 · 지난…"이 됐다.
            if (!multi && loose > 0.7 * total && total > 3) return null
            val kind = if (kinds.count { it == HANJA } * 2 >= kinds.size) HANJA else if (FOOD in kinds) FOOD else DICT
            return Pass(join(parts), kind, covered, total)
        }
        if (parts.isEmpty() || strong == 0) return null
        val need = if (total > 12) 0.75 else if (total <= 8) 0.5 else 0.6   // 짧은 메뉴 이름은 모르는 수식어 하나쯤 있어도 덮는다
        if (covered < need * total) return null
        val lastHan = s.indexOfLast { isHan(it) }
        if (total <= 8 && covEnd <= lastHan) return null   // 짧은 이름은 끝(음식 본체)을 읽어야 덮는다: 湖南糖粑 → "후난" 같은 오해 방지
        return Pass(join(parts), if (FOOD in kinds) FOOD else DICT, covered, total)
    }

    private enum class Seg { RULE, SKIP, TRANSLIT, WORD, ZI, RARE }
    private class Edge(val kind: Seg, val start: Int, val end: Int, val hit: Hit? = null, val rule: Rule? = null, val m: MatchResult? = null)

    private fun ruleAt(s: String, i: Int): Pair<Rule, MatchResult>? {
        for (rule in rules) {
            val m = rule.re.find(s, i) ?: continue
            if (m.range.first != i) continue
            if (rule.re === cnum) {   // 한자 숫자: 그보다 길거나 같은 사전 말(二十四节气·三十六味)이 있으면 사전 우선
                val w2 = longest(s, i, 99, false, 2)
                if (w2 != null && w2.zh.length >= m.range.last + 1 - i) continue
            }
            return rule to m
        }
        return null
    }

    /** 가장 비용이 낮은 분절. 비용: 규칙 0.5, 여러 글자 말 1, 한 글자 말 1(조리 동사 등·홀로)/1.6(그 밖), 음역어 2, 글자 뜻 2.5, 드문 글자 발음 3, 못 읽은 한자 5 */
    private fun segment(s: String, useZi: Boolean): List<Edge> {
        val n = s.length
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }; val back = arrayOfNulls<Edge>(n + 1)
        cost[0] = 0.0
        for (i in 0 until n) {
            if (cost[i] == Double.POSITIVE_INFINITY) continue
            val c0 = cost[i]
            fun relax(e: Edge, cst: Double) { if (c0 + cst < cost[e.end] - 1e-9) { cost[e.end] = c0 + cst; back[e.end] = e } }
            val r = ruleAt(s, i)
            if (r != null) { relax(Edge(Seg.RULE, i, r.second.range.last + 1, rule = r.first, m = r.second), 0.5); continue }
            val ch = s[i]
            if (!isHan(ch)) { relax(Edge(Seg.SKIP, i, i + 1), 0.0); continue }
            if (useZi) {
                var j = i
                while (j < n && s[j] in TRANSLIT && longest(s, j, 99, false, 2) == null) j++
                if (j - i >= 3) relax(Edge(Seg.TRANSLIT, i, j), 2.0)
            }
            for (L in minOf(dict.maxLen, n - i) downTo 1) {
                val w = s.substring(i, i + L)
                if (!w.all { isHan(it) }) continue
                val h = dict.exact(w) ?: continue
                // 같은 비용이면 앞쪽 말을 길게 잡는 쪽: 짧은 말은 앞에 올수록 아주 조금 비싸게
                val tie = 0.001 * (n - i) / L
                if (L >= 2) relax(Edge(Seg.WORD, i, i + L, hit = h), 1.0 + tie)
                else {
                    val prevHan = i > 0 && isHan(s[i - 1]); val nextHan = i + 1 < n && isHan(s[i + 1])
                    if ((!prevHan && !nextHan) || w[0] in BOUND) relax(Edge(Seg.WORD, i, i + 1, hit = h), 1.0 + tie)
                    else if (useZi) relax(Edge(Seg.WORD, i, i + 1, hit = h), 1.6 + tie)
                }
            }
            if (useZi && dict.zi(ch) != null) relax(Edge(Seg.ZI, i, i + 1), 2.5)
            else if (useZi && (dict.hanja(ch) != null || dict.sound(ch) != null)) relax(Edge(Seg.RARE, i, i + 1), 3.0)
            relax(Edge(Seg.SKIP, i, i + 1), 5.0)
        }
        val out = ArrayList<Edge>(); var j = n
        while (j > 0) { val e = back[j]!!; out.add(e); j = e.start }
        out.reverse()
        return out
    }

    /** 같은 말 되풀이는 하나로 ("쿠키 · 쿠키") */
    private fun join(parts: List<String>): String {
        val out = ArrayList<String>()
        for (p in parts) if (p.isNotEmpty() && (out.isEmpty() || out.last() != p)) out.add(p)
        return out.joinToString(" · ")
    }

    /** 라벨용 뜻: 괄호 설명은 뗀다 ("고명 (면 위 토핑)" → "고명"). 괄호뿐인 뜻("(메이퇀)")은 덮지 않는다 → "" */
    private fun stripNote(ko: String) = ko.replace(Regex("\\s*[(（][^)）]*[)）]"), "").trim()

    /** 음역(외래어 소리 옮기기)에 자주 쓰는 글자. 사전 말 없이 3자 이상 이어지면 뜻 대신 중국어 발음 한글로 (洪都拉斯 → 훙더우라쓰) */
    private val TRANSLIT = "丘丝丰丹丽乔亚亨什代伦伯佛佩俄保克内冈凯切列利加努劳勒博卡卢厄古吉哈哥嘉圭坎坦埃基堡塔塞墨多夫夸奇奈奎奥妮姆娜宁宾密尔尚尼巴布帕库康弗彭彼德恩恰戈戴拉摩斯施昆普曼朗本杰查柯根格桑森欧比毕汉法波洛洪涅潘澳玛班琳琴瑟甘皮盖科穆索纳纽罗肯胡舍艾芬芭苏范莉莎莫莱菲萨蒙蕾诺豪贝费贾赫路达迈连迪邦里阿隆雷霍韦顿鲁麦黎齐".toSet()

    /** 단어 안에서도 한 글자로 읽는 글자: 조리 동사·모양·기본 음식 (메뉴 이름은 'A炒B', 'A丁' 식의 조합) */
    private val BOUND = "派塔挞皮豆粽冰露盅果糖酥芹笋菇配小大酱盒夹团圆角馍菜藕花膏饮糊杯扒干醋茶汁奶冻鸡鸭鱼虾肉蛋粉鲜香嫩脆酥辣炒蒸炖煎烧拌煲焖炸烤卤爆煮炝熘涮焗烩酿熏丁丝片块条球卷段末泥饭面粥汤羹饼包糕".toSet()

    /**
     * 가장 긴 말. 단 그 말 바로 뒤가 읽히지 않으면, 더 짧게 잘라 다음 말이 더 멀리 이어지는 쪽을 고른다
     * (家常菜谱: 家常菜+谱 → 家常+菜谱, 大肉馅: 大肉+馅 → 大+肉馅)
     */
    private fun best(s: String, i: Int, anySingle: Boolean): Hit? {
        val hit = longest(s, i, 99, anySingle) ?: return null
        val end = i + hit.zh.length
        if (end < s.length && isHan(s[end]) && longest(s, end) == null) {
            for (n in hit.zh.length - 1 downTo 1) {
                val h2 = longest(s, i, n) ?: continue
                val e2 = i + h2.zh.length
                val nxt = longest(s, e2)
                if (nxt != null && e2 + nxt.zh.length > end) return h2
            }
        }
        return hit
    }

    /** s[i..]에서 사전에 있는 가장 긴 말. 한 글자 말은 앞뒤에 한자가 없거나 BOUND 글자일 때만 (渝满天 같은 가게 이름 보호) */
    private fun longest(s: String, i: Int, maxN: Int = 99, anySingle: Boolean = false, minN: Int = 1): Hit? {
        var n = minOf(dict.maxLen, s.length - i, maxN)
        while (n >= minN) {
            val w = s.substring(i, i + n)
            if (w.all { isHan(it) }) {
                val h = dict.exact(w)
                if (h != null) {
                    if (n > 1) return h
                    val prevHan = i > 0 && isHan(s[i - 1]); val nextHan = i + 1 < s.length && isHan(s[i + 1])
                    if ((!prevHan && !nextHan) || w[0] in BOUND || anySingle) return h
                }
            }
            n--
        }
        return null
    }

    private fun isHan(c: Char) = c in '㐀'..'鿿'
}
