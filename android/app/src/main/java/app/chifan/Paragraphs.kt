package app.chifan

/**
 * 두 줄로 접힌 이름("春龙抬首鸿 / 运当头", "新品超大熏 / 鸡腿饭")을 한 문단으로 합친다 — 줄마다 따로 읽으면 어느 쪽도 사전에 안 맞는다.
 * 규칙(tests/sim/paragraph.py 와 같음): 같은 열(가로 겹침이 좁은 쪽 폭의 60% 이상)에서 바로 아래(세로 간격이 줄 높이의 0.8배 이내)에 있고
 * 글자 높이가 비슷(30% 이내)하고, **사전의 말이 줄 경계에 걸쳐 있을 때만**(春龙抬首鸿|运当头 의 鸿运) 같은 문단.
 * 제목 + 지점 이름(锦芳小吃|艾窝窝 / 鲜鱼口店)처럼 모양만 같은 두 줄을 합치면 라벨이 엉뚱한 줄까지 덮는다 (0.19의 실수).
 * OCR 상자 높이는 같은 글꼴도 20%쯤 들쭉날쭉이라 높이로는 못 가른다. 라벨은 다시 n 줄로 나눠 덮는다 (splitLabel).
 * 안드로이드에 의존하지 않아 단위 테스트로 돌린다 (ParagraphsTest).
 */
object Paragraphs {
    class Box(var l: Int, var t: Int, var r: Int, var b: Int) {
        val h get() = b - t
        val w get() = r - l
    }
    class Para(var text: String, val box: Box, var n: Int = 1)

    fun group(lines: List<Pair<String, Box>>, isWord: (String) -> Boolean = { false }): List<Para> {
        val paras = ArrayList<Para>()
        for ((raw, bx) in lines.sortedWith(compareBy({ it.second.t }, { it.second.l }))) {
            val text = raw.trim()
            var attached = false
            for (p in paras) {
                val pb = p.box
                val ov = minOf(bx.r, pb.r) - maxOf(bx.l, pb.l)
                if (ov <= 0 || ov < 0.6f * minOf(bx.w, pb.w)) continue
                val gap = bx.t - pb.b
                if (gap < -0.2f * bx.h || gap > 0.8f * bx.h) continue
                val ph = pb.h.toFloat() / p.n          // 문단의 줄 하나 높이
                if (Math.abs(bx.h - ph) > 0.3f * maxOf(bx.h.toFloat(), ph)) continue
                if (!straddles(isWord, p.text, text)) continue
                p.text = join(p.text, text)
                pb.l = minOf(pb.l, bx.l); pb.t = minOf(pb.t, bx.t); pb.r = maxOf(pb.r, bx.r); pb.b = maxOf(pb.b, bx.b)
                p.n++
                attached = true
                break
            }
            if (!attached) paras.add(Para(text, Box(bx.l, bx.t, bx.r, bx.b)))
        }
        return paras
    }

    /** a+b 의 경계에 걸친 사전 말(2자 이상)이 있나 */
    fun straddles(isWord: (String) -> Boolean, a0: String, b0: String, maxLen: Int = 8): Boolean {
        val a = a0.filter { isHan(it) }.takeLast(maxLen - 1)
        val b = b0.filter { isHan(it) }.take(maxLen - 1)
        val j = a + b; val bi = a.length
        for (st in maxOf(0, bi - maxLen + 1) until bi)
            for (en in bi + 1..minOf(j.length, st + maxLen))
                if (en - st >= 2 && isWord(j.substring(st, en))) return true
        return false
    }

    /** 한자끼리는 붙이고, 영문·숫자가 만나면 띄운다 */
    private fun join(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val x = a.last(); val y = b.first()
        return if ((!isHan(x) || !isHan(y)) && x.isLetterOrDigit() && y.isLetterOrDigit()) "$a $b" else a + b
    }

    private fun isHan(c: Char) = c in '㐀'..'鿿'

    /** 라벨 글을 " · " 조각 단위로 n 줄에 고르게 나눈다 */
    fun splitLabel(text: String, n: Int): List<String> {
        if (n <= 1) return listOf(text)
        var parts = text.split(" · "); var sep = " · "
        if (parts.size < n) {
            parts = text.split(" "); sep = " "       // 조각이 모자라면 띄어쓰기 단위로 ("2월 2일 용두절" → "2월 2일" / "용두절")
            if (parts.size < n) return listOf(text)  // 그것도 모자라면 한 줄로 (글자를 쪼개 '간/식'으로 만들지 않는다)
        }
        val target = text.length.toFloat() / n
        val rows = ArrayList<String>(); var cur = ""
        for ((i, prt) in parts.withIndex()) {
            val cand = if (cur.isEmpty()) prt else cur + sep + prt
            val remaining = n - rows.size
            // 넘치는 쪽이 모자란 쪽보다 크면 여기서 줄을 바꾼다 (줄 길이를 고르게)
            if (cur.isNotEmpty() && cand.length > target && (cand.length - target) > (target - cur.length) && remaining > 1 && parts.size - i >= remaining - 1) { rows.add(cur); cur = prt }
            else cur = cand
        }
        rows.add(cur)
        while (rows.size > n) { rows[rows.size - 2] = rows[rows.size - 2] + sep + rows.last(); rows.removeAt(rows.size - 1) }
        return rows
    }
}
