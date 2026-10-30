package app.chifan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** 메이퇀 화면 위에 한국어 라벨을 그리는 전체 화면 뷰. 터치는 받지 않는다(아래 앱으로 그대로 통과). */
class OverlayView(ctx: Context) : View(ctx) {
    /** kind: 0 사전, 1 메뉴 이름, 2 기계 번역, 3 한자음만 */
    class Label(val bounds: Rect, val text: String, val kind: Int, val rows: Int = 1) {
        companion object { const val DICT = 0; const val FOOD = 1; const val MT = 2; const val HANJA = 3 }
    }

    @Volatile var labels: List<Label> = emptyList()
        set(v) { field = v; postInvalidate() }
    /** 화면의 글자 줄 영역. 라벨이 다른 글자를 가리지 않게 피해서 놓는다. */
    @Volatile var obstacles: List<Rect> = emptyList()

    fun set(labels: List<Label>, obstacles: List<Rect>) { this.obstacles = obstacles; this.labels = labels }

    private val d = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * d; isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6C2462E.toInt() }
    private val bgFood = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0A2502E.toInt() }
    private val bgMt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE63F5F7F.toInt() }   // 기계 번역은 파란 회색
    private val bgHanja = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9707070.toInt() } // 한자음만 있는 라벨은 회색
    private val pad = 4f * d
    private val radius = 5f * d
    private val loc = IntArray(2)

    private val drawn = ArrayList<Rect>()
    /** 마지막으로 실제 그린 라벨 자리(화면 좌표) */
    fun drawnRects(): List<Rect> = synchronized(drawn) { ArrayList(drawn) }

    /** true면 원래 글자 위에 덮어 그린다 (기본). false면 옆에 작은 라벨로 붙인다. */
    @Volatile var coverMode = true

    // 어두운 바탕 + 흰 글자: 폰에 따라 오버레이 창이 반투명으로 보여도(터치를 통과시키려 80%로 깔리는 기종) 밑의 중국어가 비쳐 섞이지 않는다.
    // 0.19까지의 밝은 바탕은 반투명이 되면 원문과 겹쳐 둘 다 못 읽었다.
    private val coverBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16120F.toInt() }
    // 안드로이드는 터치가 통과하는 오버레이를 최대 80% 불투명으로만 보여 주므로(그 이상이면 아래 앱 터치를 막는다) 밑의 글자가 조금 비친다.
    // 글자에 어두운 그림자를 둘러 비친 원문보다 또렷하게 한다.
    private val coverText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true; setShadowLayer(3f * resources.displayMetrics.density, 0f, 0f, 0xFF000000.toInt()) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 원래 글자 자리를 한국어로 덮는다 (tests/sim/render.py 와 같은 규칙).
     * 글자 높이에 맞춰 크기를 정하고, 안 들어가면 줄이고, 그래도 넘치면 같은 줄의 다음 글자 직전까지만 넓히고, 그래도 안 되면 …로 자른다.
     * 이미 덮은 상자와 30% 넘게 겹치면 건너뛴다.
     */
    private fun drawCover(c: Canvas, ls: List<Label>) {
        val minSize = 8f * d; val maxSize = 17f * d
        val obs = obstacles.map { RectF((it.left - loc[0]).toFloat(), (it.top - loc[1]).toFloat(), (it.right - loc[0]).toFloat(), (it.bottom - loc[1]).toFloat()) }
        val done = ArrayList<RectF>()
        for (l in ls.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            val b = RectF((l.bounds.left - loc[0]).toFloat(), (l.bounds.top - loc[1]).toFloat(), (l.bounds.right - loc[0]).toFloat(), (l.bounds.bottom - loc[1]).toFloat())
            if (b.height() < 6 * d || b.width() < 10 * d) continue
            var limit = width.toFloat(); var llimit = 0f
            for (o in obs) {
                if (o == b || o.bottom < b.top || o.top > b.bottom) continue
                if (o.left >= b.right - 2 || o.left > b.left + 0.6f * b.width()) limit = minOf(limit, o.left - 4 * d)   // 상자가 맞닿거나 살짝 겹쳐도 오른쪽 글자면 장애물
                else if (o.right <= b.left + 2) llimit = maxOf(llimit, o.right + 4 * d)
            }
            val r = RectF(b.left - 3 * d, b.top - 2 * d, minOf(b.right + 3 * d, width.toFloat()), b.bottom + 2 * d)
            if (done.any { o -> val ix = maxOf(0f, minOf(r.right, o.right) - maxOf(r.left, o.left)); val iy = maxOf(0f, minOf(r.bottom, o.bottom) - maxOf(r.top, o.top)); ix * iy > 0.3f * r.width() * r.height() }) continue
            for (o in done) if (!(o.bottom < r.top || o.top > r.bottom) && o.left < r.left && r.left < o.right - 2 * d) r.left = o.right + 2 * d   // 먼저 그린 라벨이 내 왼쪽 끝을 덮고 있으면 그 뒤에서 시작
            if (r.width() < 10 * d) continue
            val avail = r.width() - 11 * d
            val rowH = b.height() / l.rows               // 두 줄로 접힌 이름은 줄 수만큼 나눠 덮는다
            val minSz = minOf(minSize, rowH * 0.95f)   // 원문이 아주 작으면 최소 크기도 줄 높이 안으로 (넘치면 아래 라벨에 가려 잘린다)
            var size = (rowH * 0.72f).coerceIn(minSz, maxSize)
            coverText.textSize = size
            var rows = Paragraphs.splitLabel(l.text, l.rows)
            if (rows.size < l.rows) r.bottom = r.top + 4 * d + rowH * rows.size      // 조각이 줄 수보다 적으면 윗줄만 덮는다
            var w = rows.maxOf { coverText.measureText(it) }
            // 안 들어가면 먼저 오른쪽 빈 자리(다음 글자 직전)까지 넓히고, 그래도 안 되면 글자를 줄인다
            if (w > avail) {
                // 넓히기: 먼저 오른쪽(다음 글자 직전까지), 모자라면 왼쪽(앞 글자·앞 라벨 직후까지) — 가운데 정렬된 칸에 빈 곳이 많다
                for (o in done) if (!(o.bottom < r.top || o.top > r.bottom) && o.left >= r.left) limit = minOf(limit, o.left - 2 * d)   // 먼저 그린 라벨(같은 줄, 오른쪽)도 장애물
                val extra = minOf(w - avail, limit - r.right)
                if (extra > 0) r.right += extra
                for (o in done) if (!(o.bottom < r.top || o.top > r.bottom) && o.right <= r.left + 2) llimit = maxOf(llimit, o.right + 2 * d)
                val need = w - (r.width() - 11 * d)
                if (need > 0) r.left -= maxOf(0f, minOf(need, r.left - llimit, minOf(3 * rowH, 24 * d)))   // 왼쪽은 줄 높이 3배까지만 (OCR은 사진을 모른다: 썸네일 위로 뻗지 않게)
            }
            val avail2 = r.width() - 11 * d
            if (w > avail2) {   // 그래도 안 되면 자르기 전에 간격부터 줄인다 ("쓰촨 · 간식" → "쓰촨·간식")
                rows = rows.map { it.replace(" · ", "·") }
                w = rows.maxOf { coverText.measureText(it) }
            }
            while (w > avail2 && size > minSz) { size -= 1 * d; coverText.textSize = size; w = rows.maxOf { coverText.measureText(it) } }
            rows = rows.map { var t = it
                // 안 들어가면 먼저 띄어쓰기를 빼 보고("월 0 판매"→"월0판매"), 그래도 안 되면 …로 자른다
                if (coverText.measureText(t) > avail2 && ' ' in t && " · " !in t && coverText.measureText(t.replace(" ", "")) <= avail2) t = t.replace(" ", "")
                while (coverText.measureText(t) > avail2 && t.length > 2) t = t.substring(0, t.length - 2) + "…"
                t }
            done.add(r)
            c.drawRoundRect(r, radius, radius, coverBg)
            barPaint.color = when (l.kind) { Label.FOOD -> 0xFFA2502E.toInt(); Label.MT -> 0xFF3F5F7F.toInt(); Label.HANJA -> 0xFF9A9A9A.toInt(); else -> 0xFFC2462E.toInt() }
            c.drawRoundRect(RectF(r.left, r.top, r.left + 3 * d, r.bottom), 2 * d, 2 * d, barPaint)
            val fm = coverText.fontMetrics
            for ((i, t) in rows.withIndex()) {
                val cy = r.top + 2 * d + rowH * (i + 0.5f)
                c.drawText(t, r.left + 3 * d + 4 * d, cy - (fm.ascent + fm.descent) / 2, coverText)
            }
            synchronized(drawn) { drawn.add(Rect((r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(), (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt())) }
        }
    }

    /** 화면이 멈춘 뒤 프레임을 하나 받기 위해 그림을 '아주 조금' 바꾼다 (한쪽 구석 1px, 거의 투명). 내용이 바뀌어야 캡처 프레임이 생긴다. */
    private var nudgeOn = false
    private val nudgePaint = Paint().apply { color = 0x01000000 }
    fun nudge() { nudgeOn = !nudgeOn; postInvalidate() }

    override fun onDraw(c: Canvas) {
        if (nudgeOn) c.drawRect(0f, (height - 1).toFloat(), 1f, height.toFloat(), nudgePaint)
        val ls = labels
        synchronized(drawn) { drawn.clear() }
        if (ls.isEmpty()) return
        getLocationOnScreen(loc)
        if (coverMode) { drawCover(c, ls); return }
        val fm = textPaint.fontMetrics
        val h = fm.descent - fm.ascent + pad * 2
        val taken = ArrayList<RectF>()
        val obs = obstacles.map { RectF((it.left - loc[0]).toFloat(), (it.top - loc[1]).toFloat(), (it.right - loc[0]).toFloat(), (it.bottom - loc[1]).toFloat()) }
        val gap = 6 * d
        val maxW = width * 0.7f
        for (l in ls) {
            var text = l.text
            var w = textPaint.measureText(text) + pad * 2
            // 너무 긴 라벨은 화면 폭 70%에서 자른다
            while (w > maxW && text.length > 4) { text = text.substring(0, text.length - 2) + "…"; w = textPaint.measureText(text) + pad * 2 }
            val b = RectF((l.bounds.left - loc[0]).toFloat(), (l.bounds.top - loc[1]).toFloat(), (l.bounds.right - loc[0]).toFloat(), (l.bounds.bottom - loc[1]).toFloat())
            // 글자 오른쪽 → 아래 → 위 순서로, 다른 글자나 라벨을 가리지 않는 자리에 놓는다
            val cands = listOf(
                RectF(b.right + gap, b.centerY() - h / 2, b.right + gap + w, b.centerY() + h / 2),
                RectF(b.left, b.bottom + 2 * d, b.left + w, b.bottom + 2 * d + h),
                RectF(b.left, b.top - 2 * d - h, b.left + w, b.top - 2 * d)
            )
            var r: RectF? = null
            for ((ci, cand) in cands.withIndex()) {
                if (cand.right > width) cand.offset(width - cand.right, 0f)
                if (cand.left < 0 || cand.top < 0 || cand.bottom > height) continue
                if (ci == 0 && RectF.intersects(cand, b)) continue   // 오른쪽 자리가 밀려서 제 글자를 덮으면 안 됨
                if (taken.any { RectF.intersects(it, cand) }) continue
                if (obs.any { it != b && RectF.intersects(it, cand) && !(it.contains(b)) }) continue
                r = cand; break
            }
            if (r == null) continue
            taken.add(r)
            synchronized(drawn) { drawn.add(Rect((r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(), (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt())) }
            c.drawRoundRect(r, radius, radius, when (l.kind) { Label.FOOD -> bgFood; Label.MT -> bgMt; Label.HANJA -> bgHanja; else -> bgPaint })
            c.drawText(text, r.left + pad, r.top + pad - fm.ascent, textPaint)
        }
    }
}
