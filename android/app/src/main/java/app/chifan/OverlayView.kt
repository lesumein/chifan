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
    class Label(val bounds: Rect, val text: String, val isFood: Boolean)

    @Volatile var labels: List<Label> = emptyList()
        set(v) { field = v; postInvalidate() }

    private val d = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * d; isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6C2462E.toInt() }
    private val bgFood = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0A2502E.toInt() }
    private val pad = 4f * d
    private val radius = 5f * d
    private val loc = IntArray(2)

    override fun onDraw(c: Canvas) {
        val ls = labels
        if (ls.isEmpty()) return
        getLocationOnScreen(loc)
        val fm = textPaint.fontMetrics
        val h = fm.descent - fm.ascent + pad * 2
        val taken = ArrayList<RectF>()
        for (l in ls) {
            val text = l.text
            val w = textPaint.measureText(text) + pad * 2
            // 글자의 왼쪽 아래에 붙이되, 화면 밖이면 위로/안쪽으로
            var x = (l.bounds.left - loc[0]).toFloat()
            var y = (l.bounds.bottom - loc[1]).toFloat() + 2 * d
            if (x + w > width) x = width - w
            if (x < 0) x = 0f
            if (y + h > height) y = (l.bounds.top - loc[1]) - h - 2 * d
            if (y < 0) continue
            val r = RectF(x, y, x + w, y + h)
            if (taken.any { RectF.intersects(it, r) }) {
                // 겹치면 글자 위쪽으로 한 번 더 시도
                val y2 = (l.bounds.top - loc[1]) - h - 2 * d
                val r2 = RectF(x, y2, x + w, y2 + h)
                if (y2 < 0 || taken.any { RectF.intersects(it, r2) }) continue
                r.set(r2)
            }
            taken.add(r)
            c.drawRoundRect(r, radius, radius, if (l.isFood) bgFood else bgPaint)
            c.drawText(text, r.left + pad, r.top + pad - fm.ascent, textPaint)
        }
    }
}
