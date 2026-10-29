package app.chifan

/**
 * 두 줄로 접힌 이름("春龙抬首鸿 / 运当头", "新品超大熏 / 鸡腿饭")을 한 문단으로 합친다 — 줄마다 따로 읽으면 어느 쪽도 사전에 안 맞는다.
 * 규칙(tests/sim/paragraph.py 와 같음): 같은 열(가로 겹침이 좁은 쪽 폭의 60% 이상)에서 바로 아래(세로 간격이 줄 높이의 0.8배 이내)에 있고
 * 글자 높이가 비슷(30% 이내)하면 같은 문단. 라벨은 다시 n 줄로 나눠 덮는다 (splitLabel).
 * 안드로이드에 의존하지 않아 단위 테스트로 돌린다 (ParagraphsTest).
 */
object Paragraphs {
    class Box(var l: Int, var t: Int, var r: Int, var b: Int) {
        val h get() = b - t
        val w get() = r - l
    }
    class Para(var text: String, val box: Box, var n: Int = 1)

    fun group(lines: List<Pair<String, Box>>): List<Para> {
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
        val parts = text.split(" · ")
        if (parts.size < n) {
            val step = maxOf(1, (text.length + n - 1) / n)
            return text.chunked(step).take(n)
        }
        val target = text.length.toFloat() / n
        val rows = ArrayList<String>(); var cur = ""
        for ((i, prt) in parts.withIndex()) {
            val cand = if (cur.isEmpty()) prt else "$cur · $prt"
            val remaining = n - rows.size
            if (cur.isNotEmpty() && cand.length > target && remaining > 1 && parts.size - i >= remaining - 1) { rows.add(cur); cur = prt }
            else cur = cand
        }
        rows.add(cur)
        while (rows.size > n) { rows[rows.size - 2] = rows[rows.size - 2] + " · " + rows.last(); rows.removeAt(rows.size - 1) }
        return rows
    }
}
