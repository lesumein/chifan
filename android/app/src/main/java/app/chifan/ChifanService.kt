package app.chifan

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 메이퇀 앱 화면의 글자를 읽어 한국어 뜻을 덧그리고, 떠다니는 치판 버튼을 띄운다.
 *
 * 하는 것: 화면 글자 읽기(접근성 API), 그 위에 라벨 그리기, 클립보드에 검색어 복사.
 * 안 하는 것: 메이퇀 화면 누르기·스크롤·입력 등 어떤 조작도 하지 않는다. 읽은 글자는 기기 밖으로 나가지 않는다.
 */
class ChifanService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var dict: Dict
    private var overlay: OverlayView? = null
    private var bubble: Bubble? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var meituanVisible = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dict = Dict.get(this)
        showOverlay()
        bubble = Bubble(this, wm, dict).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName) {
                meituanVisible = pkg == MEITUAN
                if (!meituanVisible) { overlay?.labels = emptyList() }
                bubble?.setVisible(meituanVisible)
            }
        }
        scheduleScan()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        bubble?.hide()
        bubble = null
        super.onDestroy()
    }

    // 이벤트가 연달아 오므로 120ms에 한 번만 읽는다
    private fun scheduleScan() {
        if (pending) return
        pending = true
        handler.postDelayed({ pending = false; scan() }, 120)
    }

    private fun scan() {
        val ov = overlay ?: return
        if (!Prefs.labelsOn(this)) { ov.labels = emptyList(); return }
        val root = rootInActiveWindow
        if (root == null || root.packageName != MEITUAN) { return }
        meituanVisible = true
        bubble?.setVisible(true)
        val out = ArrayList<OverlayView.Label>()
        val r = Rect()
        walk(root, 0) { node ->
            val text = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return@walk
            if (!text.any { it in '㐀'..'鿿' }) return@walk
            node.getBoundsInScreen(r)
            if (r.isEmpty || r.bottom < 0) return@walk
            val label = labelFor(text) ?: return@walk
            out.add(OverlayView.Label(Rect(r), label.first, label.second))
            if (out.size >= MAX_LABELS) return@walk
        }
        ov.labels = out
    }

    /** 글자에 대한 한국어 라벨. 통째로 사전에 있으면 그 뜻, 아니면 찾은 단어들의 뜻을 이어 붙인다. */
    private fun labelFor(text: String): Pair<String, Boolean>? {
        val t = text.replace(Regex("\\s+"), "")
        dict.exact(t)?.let { return it.ko to (it.foodId != null) }
        val hits = dict.annotate(t)
        if (hits.isEmpty()) return null
        // 뜻이 글자 수의 절반도 못 덮으면(긴 설명문 등) 앞 세 개만
        val shown = hits.distinctBy { it.zh }.take(3)
        val s = shown.joinToString(" · ") { it.ko }
        return (if (hits.size > 3) "$s …" else s) to shown.any { it.foodId != null }
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, f: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 40) return
        if (node.isVisibleToUser) f(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, depth + 1, f)
        }
    }

    private fun showOverlay() {
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        overlay = v
    }

    fun refreshNow() { overlay?.labels = emptyList(); scheduleScan() }

    companion object {
        const val MEITUAN = "com.sankuai.meituan"
        private const val MAX_LABELS = 80
        @Volatile var instance: ChifanService? = null
    }
}
