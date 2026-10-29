package app.chifan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 다른 앱 위에 떠 있는 치판 버튼과, 누르면 열리는 작은 치판 창.
 * 창 안에서 메뉴를 고르면 중국어 검색어가 복사된다. 다른 앱 화면을 누르는 일은 없다.
 */
class Bubble(private val ctx: Context, private val wm: WindowManager, private val dict: Dict) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var visible = false
    private var tab = "menu"
    private var cat = Prefs.lastCat(ctx)
    private var glossGroup = "option"
    private var query = ""

    private val accent = 0xFFC2462E.toInt()
    private val ink = 0xFF2A211C.toInt()
    private val muted = 0xFF7A6A5E.toInt()
    private val bg = 0xFFFFF8F0.toInt()
    private val chipBg = 0xFFF4EAE0.toInt()
    private val white = Color.WHITE

    fun show() {
        if (bubbleView != null) return
        val v = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(accent, 999)
            clipToOutline = true
            contentDescription = "치판 열기"
            alpha = 0.92f
        }
        val size = dp(52)
        val lp = WindowManager.LayoutParams(size, size, OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = dp(6)
        lp.y = Prefs.bubbleY(ctx).let { if (it < 0) dp(180) else it }

        // 세로로 끌 수 있고, 거의 안 움직였으면 탭으로 본다
        var downY = 0f; var startY = 0; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dy) > dp(6)) moved = true
                    if (moved) { lp.y = (startY + dy).coerceAtLeast(0); wm.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Prefs.setBubbleY(ctx, lp.y) else { view.performClick(); togglePanel() }
                    true
                }
                else -> false
            }
        }
        v.visibility = View.GONE
        wm.addView(v, lp)
        bubbleView = v
    }

    fun hide() {
        closePanel()
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
    }

    fun setVisible(on: Boolean) {
        if (visible == on) return
        visible = on
        bubbleView?.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) closePanel()
    }

    private fun togglePanel() { if (panelView == null) openPanel() else closePanel() }

    private fun closePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        CaptureService.instance?.setPaused(false)
    }

    // ---------- 창 ----------
    private lateinit var content: LinearLayout
    private lateinit var search: EditText
    private lateinit var tabBtns: Map<String, TextView>

    private fun openPanel() {
        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closePanel(); return true }
                return super.dispatchKeyEvent(event)
            }
        }
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(bg, 20)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 머리: 제목 · 라벨 스위치 · 닫기
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("치판 吃饭", 18f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            text = "한국어 라벨 "
            setTextColor(muted); textSize = 13f
            isChecked = Prefs.labelsOn(ctx)
            setOnCheckedChangeListener { _, on -> Prefs.setLabelsOn(ctx, on); CaptureService.instance?.refreshNow() }
        }
        head.addView(sw)
        head.addView(TextView(ctx).apply {
            text = "중지"; textSize = 13f; setTextColor(muted); setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { ctx.startService(android.content.Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }
        })
        head.addView(TextView(ctx).apply {
            text = "✕"; textSize = 20f; setTextColor(muted); setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { closePanel() }
        })
        card.addView(head)

        // 탭
        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tabBtns = listOf("menu" to "메뉴", "memo" to "요청사항", "gloss" to "단어장", "ocr" to "읽은 글자").associate { (id, label) ->
            val t = text(label, 15f, muted, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { tab = id; query = ""; search.setText(""); render() }
            }
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            id to t
        }
        card.addView(tabs)

        // 검색
        search = EditText(ctx).apply {
            hint = "한국어로 찾기 (예: 마라탕, ㅉㅈㅁ)"
            textSize = 16f; setTextColor(ink); setHintTextColor(muted)
            background = round(white, 22, stroke = 0xFFEADFD3.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; renderContent() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        card.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll.addView(content)
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val h = ctx.resources.displayMetrics.heightPixels
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.72).toInt(), OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.addView(root, lp)
        panelView = root
        CaptureService.instance?.setPaused(true)
        render()
    }

    private fun render() {
        tabBtns.forEach { (id, t) -> t.setTextColor(if (id == tab) accent else muted) }
        search.hint = when (tab) {
            "menu" -> "한국어로 찾기 (예: 마라탕, ㅉㅈㅁ)"
            "gloss" -> "단어 찾기 (예: 맵기, 당도, 加料)"
            "ocr" -> "방금 화면에서 읽은 글자와 붙인 뜻"
            else -> "요청사항 찾기"
        }
        renderContent()
    }

    private fun renderContent() {
        content.removeAllViews()
        when (tab) { "menu" -> renderMenu(); "memo" -> renderMemo(); "ocr" -> renderOcr(); else -> renderGloss() }
    }

    // ---------- 메뉴 고르기 ----------
    private fun renderMenu() {
        val list = if (query.isNotEmpty()) dict.searchFoods(query) else dict.foods.filter { it.cat == cat }
        if (query.isEmpty()) {
            content.addView(chipRow(dict.categories.map { c -> Triple(c.id, "${c.emoji} ${c.ko}", c.color) }, cat) { id ->
                cat = id; Prefs.setLastCat(ctx, id); renderContent()
            })
        }
        if (list.isEmpty()) { content.addView(text("찾는 메뉴가 없어요. 다른 이름이나 초성으로 찾아보세요.", 14f, muted).apply { setPadding(0, dp(20), 0, 0); gravity = Gravity.CENTER }); return }
        for (f in list) {
            val c = dict.categories.firstOrNull { it.id == f.cat }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = round(white, 14); setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { copy(f.searchTerm, "복사됨: ${f.searchTerm} · 메이퇀 검색창에 붙여넣기") }
            }
            row.addView(TextView(ctx).apply {
                text = f.emoji; textSize = 26f; gravity = Gravity.CENTER
                background = round(c?.color ?: accent, 12)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(f.zh, 20f, ink, bold = true))
            val sub = f.ko + (if (f.spicy > 0) "  " + "🌶".repeat(f.spicy) else "") + (if (f.check) "  (확인 중)" else "")
            col.addView(text(sub, 14f, muted))
            col.addView(text(f.py, 12f, accent))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    // ---------- 요청사항 ----------
    private fun renderMemo() {
        val sel = Prefs.memo(ctx)
        val chosen = dict.requests.filter { it.id in sel }
            .let { l -> l.filter { it.id != "thanks" } + l.filter { it.id == "thanks" } }
        val memo = chosen.joinToString("，") { it.zh }

        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = round(white, 14); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        box.addView(text("주문 메모 (备注 칸에 붙여넣기)", 12f, muted))
        box.addView(text(if (memo.isEmpty()) "아래에서 요청사항을 골라 주세요" else memo, if (memo.isEmpty()) 15f else 19f, if (memo.isEmpty()) muted else ink, bold = memo.isNotEmpty()))
        if (chosen.isNotEmpty()) box.addView(text(chosen.joinToString(" · ") { it.ko }, 12f, muted))
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(button("메모 복사", primary = true) { if (memo.isNotEmpty()) copy(memo, "메모 복사됨 · 주문 화면 备注 칸에 붙여넣기") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(button("모두 지우기") { Prefs.setMemo(ctx, emptySet()); renderContent() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        box.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        content.addView(box)

        val q = Hangul.norm(query)
        for (g in dict.reqGroups) {
            val items = dict.requests.filter { it.group == g.id && (q.isEmpty() || Hangul.find(Hangul.norm(it.ko), q) >= 0) }
            if (items.isEmpty()) continue
            content.addView(text(g.ko, 14f, muted, bold = true).apply { setPadding(dp(2), dp(14), 0, dp(6)) })
            content.addView(flow(items.map { r ->
                val on = r.id in sel
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = round(if (on) 0xFFF6DED8.toInt() else white, 12, stroke = if (on) accent else 0xFFEADFD3.toInt())
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener {
                        val s = Prefs.memo(ctx)
                        if (r.id in s) s.remove(r.id) else {
                            if (r.excl != null) dict.requests.filter { it.excl == r.excl }.forEach { s.remove(it.id) }
                            s.add(r.id)
                        }
                        Prefs.setMemo(ctx, s); renderContent()
                    }
                }
                chip.addView(text((if (on) "✓ " else "") + r.ko, 14f, ink, bold = true))
                chip.addView(text(r.zh, 12f, muted))
                chip
            }))
        }
    }

    // ---------- 단어장 ----------
    private fun renderGloss() {
        val filtering = query.isNotEmpty()
        if (!filtering) {
            content.addView(chipRow(dict.glossGroups.map { Triple(it.id, it.ko, accent) }, glossGroup) { id -> glossGroup = id; renderContent() })
        }
        val list = if (filtering) dict.searchGloss(query) else dict.gloss.filter { it.group == glossGroup }
        if (list.isEmpty()) { content.addView(text("단어장에 없어요.", 14f, muted).apply { setPadding(0, dp(20), 0, 0); gravity = Gravity.CENTER }); return }
        for (g in list) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = round(white, 12); setPadding(dp(14), dp(9), dp(14), dp(9))
                setOnClickListener { copy(g.zh, "복사됨: ${g.zh}") }
            }
            row.addView(text(g.zh, 19f, ink, bold = true), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(g.ko, 14f, muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }
    }

    // ---------- 읽은 글자 (OCR 결과 확인용) ----------
    private fun renderOcr() {
        val lines = CaptureService.instance?.lastOcr ?: emptyList()
        content.addView(text("창을 열기 직전 화면에서 읽은 중국어예요. 뜻이 ‘—’인 줄은 사전에 없는 말이에요. [복사]해서 보내 주면 사전에 넣을 수 있어요.", 13f, muted))
        content.addView(button("전부 복사", primary = true) {
            copy(lines.joinToString("\n") { it.first + " → " + (it.second ?: "—") }, "읽은 글자 ${lines.size}줄 복사됨")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        if (lines.isEmpty()) { content.addView(text("아직 읽은 글자가 없어요. 창을 닫고 화면을 조금 움직인 뒤 다시 열어 보세요.", 14f, muted).apply { setPadding(0, dp(16), 0, 0) }); return }
        for ((zh, ko) in lines) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = round(white, 10); setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { copy(zh, "복사됨: $zh") }
            }
            row.addView(text(zh, 16f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(text(ko ?: "—", 13f, if (ko == null) muted else accent), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
    }

    // ---------- 공통 조각 ----------
    private fun copy(s: String, msg: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("chifan", s))
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        text = label; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) white else ink)
        background = round(if (primary) accent else white, 12, stroke = if (primary) accent else 0xFFEADFD3.toInt())
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * d
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** 가로 스크롤 칩 줄 (카테고리 등) */
    private fun chipRow(items: List<Triple<String, String, Int>>, selected: String, onPick: (String) -> Unit): View {
        val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, label, color) in items) {
            val on = id == selected
            row.addView(text(label, 14f, if (on) white else ink, bold = true).apply {
                background = round(if (on) color else white, 18, stroke = if (on) color else 0xFFEADFD3.toInt())
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setOnClickListener { onPick(id) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        hs.addView(row)
        return hs
    }

    /** 줄바꿈되는 칩 묶음: 폭을 재서 한 줄씩 LinearLayout에 담는다 */
    private fun flow(chips: List<View>): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val maxW = ctx.resources.displayMetrics.widthPixels - dp(28)
        var line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        var used = 0
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (c in chips) {
            c.measure(spec, spec)
            val w = c.measuredWidth + dp(8)
            if (used + w > maxW && line.childCount > 0) {
                col.addView(line); line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }; used = 0
            }
            line.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8); bottomMargin = dp(8) })
            used += w
        }
        if (line.childCount > 0) col.addView(line)
        return col
    }

    companion object {
        private const val OVERLAY = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }
}
