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
    /** kind: 0 사전, 1 메뉴 이름, 2 기계 번역 */
    class Label(val bounds: Rect, val text: String, val kind: Int) {
        companion object { const val DICT = 0; const val FOOD = 1; const val MT = 2 }
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
    private val pad = 4f * d
    private val radius = 5f * d
    private val loc = IntArray(2)

    private val drawn = ArrayList<Rect>()
    /** 마지막으로 실제 그린 라벨 자리(화면 좌표) */
    fun drawnRects(): List<Rect> = synchronized(drawn) { ArrayList(drawn) }

    override fun onDraw(c: Canvas) {
        val ls = labels
        synchronized(drawn) { drawn.clear() }
        if (ls.isEmpty()) return
        getLocationOnScreen(loc)
        val fm = textPaint.fontMetrics
        val h = fm.descent - fm.ascent + pad * 2
        val taken = ArrayList<RectF>()
        val obs = obstacles.map { RectF((it.left - loc[0]).toFloat(), (it.top - loc[1]).toFloat(), (it.right - loc[0]).toFloat(), (it.bottom - loc[1]).toFloat()) }
        val gap = 6 * d
        for (l in ls) {
            val text = l.text
            val w = textPaint.measureText(text) + pad * 2
            val b = RectF((l.bounds.left - loc[0]).toFloat(), (l.bounds.top - loc[1]).toFloat(), (l.bounds.right - loc[0]).toFloat(), (l.bounds.bottom - loc[1]).toFloat())
            // 글자 오른쪽 → 아래 → 위 순서로, 다른 글자나 라벨을 가리지 않는 자리에 놓는다
            val cands = listOf(
                RectF(b.right + gap, b.centerY() - h / 2, b.right + gap + w, b.centerY() + h / 2),
                RectF(b.left, b.bottom + 2 * d, b.left + w, b.bottom + 2 * d + h),
                RectF(b.left, b.top - 2 * d - h, b.left + w, b.top - 2 * d)
            )
            var r: RectF? = null
            for (cand in cands) {
                if (cand.right > width) cand.offset(width - cand.right, 0f)
                if (cand.left < 0 || cand.top < 0 || cand.bottom > height) continue
                if (taken.any { RectF.intersects(it, cand) }) continue
                if (obs.any { it != b && RectF.intersects(it, cand) && !(it.contains(b)) }) continue
                r = cand; break
            }
            if (r == null) continue
            taken.add(r)
            synchronized(drawn) { drawn.add(Rect((r.left + loc[0]).toInt(), (r.top + loc[1]).toInt(), (r.right + loc[0]).toInt(), (r.bottom + loc[1]).toInt())) }
            c.drawRoundRect(r, radius, radius, when (l.kind) { Label.FOOD -> bgFood; Label.MT -> bgMt; else -> bgPaint })
            c.drawText(text, r.left + pad, r.top + pad - fm.ascent, textPaint)
        }
    }
}
