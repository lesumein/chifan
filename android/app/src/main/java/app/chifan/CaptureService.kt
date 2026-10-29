package app.chifan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 화면을 캡처해 폰 안 OCR로 중국어를 읽고, 사전에 있는 말 옆에 한국어 라벨을 그린다.
 *
 * 하는 것: 화면 캡처(사용자가 허용한 동안만, 상태바에 표시됨), 폰 안 글자 인식, 라벨 그리기, 검색어 복사.
 * 안 하는 것: 어떤 앱도 누르거나 조작하지 않는다. 읽은 화면·글자는 폰 밖으로 보내지 않는다 (인터넷은 번역 모델 내려받기에만).
 * 어느 앱이든 한자가 보이면 동작한다 (메이퇀에 묶여 있지 않음).
 */
class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var dict: Dict
    private var overlay: OverlayView? = null
    private var bubble: Bubble? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("chifan-capture").apply { start() }
    private val bg = Handler(thread.looper)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private var width = 0; private var height = 0; private var dpi = 0
    private var lastSig = ""             // 마지막 OCR 결과 서명 (같으면 라벨을 다시 그리지 않음)
    @Volatile private var paused = false
    // 되먹임 방지: 캡처는 라벨을 숨긴 깨끗한 화면으로만 한다

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dict = Dict.get(this)
        // 번역 모델이 있는지만 확인한다. 받는 건 설정 화면 버튼으로만 (조용히 걸어 둔 와이파이 대기 요청이 버튼을 막던 문제)
        Mt.check { }
    }

    private var lastText: Text? = null
    private var rebuildPending = false
    /** 번역 결과가 도착하면 마지막 OCR 결과로 라벨만 다시 만든다 (새 캡처 없이) */
    private fun scheduleRebuild() {
        if (rebuildPending) return
        rebuildPending = true
        main.postDelayed({
            rebuildPending = false
            val t = lastText ?: return@postDelayed
            draw(toLabels(t, lastMasks), t.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox } }.filter { b -> lastMasks.none { overlap(it, b) > 0.4f } }, true)
        }, 150)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        startForeground()
        if (overlay == null) { showOverlay(); bubble = Bubble(this, wm, dict).also { it.show(); it.setVisible(true) } }
        startProjection(code, data)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopProjection()
        bubble?.hide(); bubble = null
        overlay?.let { runCatching { wm.removeView(it) } }; overlay = null
        recognizer.close()
        thread.quitSafely()
        super.onDestroy()
    }

    // ---------- 알림 (화면을 읽는 동안 항상 보임) ----------
    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    // ---------- 화면 캡처 ----------
    private fun startProjection(code: Int, data: Intent) {
        stopProjection()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = mp

        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        width = m.widthPixels; height = m.heightPixels; dpi = m.densityDpi

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, bg)
        reader = r
        display = mp.createVirtualDisplay("chifan", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
    }

    private fun stopProjection() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }

    // ---------- 프레임 처리: 멈춘 화면만 읽는다 (상태 기계는 FrameGate, 단위 테스트 FrameGateTest) ----------
    private val timers = HashMap<String, Runnable>()
    private var currentBmp: Bitmap? = null
    private val gate = FrameGate(
        now = { System.currentTimeMillis() },
        postDelayed = { key, delay, run ->
            timers.remove(key)?.let { main.removeCallbacks(it) }
            val r = Runnable { synchronized(gateLock) { run() } }
            timers[key] = r; main.postDelayed(r, delay)
        },
        cancel = { key -> timers.remove(key)?.let { main.removeCallbacks(it) } },
        actions = object : FrameGate.Actions {
            override fun clearLabels() { main.post { tracked.clear(); lastSig = ""; lastLabelSig = ""; overlay?.labels = emptyList() } }
            override fun nudge() { overlay?.nudge() }
            override fun recognize(frameId: Int, seq: Int, withLabels: Boolean) { currentBmp?.let { recognize(it, seq, withLabels) } }
        }
    )
    private val gateLock = Any()

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (paused || !Prefs.labelsOn(this)) return
            val plane = img.planes[0]
            val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
            val padded = Bitmap.createBitmap(rowStride / pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            val g = grid(bmp)
            synchronized(gateLock) { currentBmp = bmp; gate.onFrame(0, g) }
        } finally { img.close() }
    }

    private var tracked: MutableList<OverlayView.Label> = ArrayList()

    /**
     * 새 라벨을 기존 라벨과 맞춰 본다. 같은 글자가 거의 같은 자리에 있으면 기존 라벨(자리 포함)을 그대로 쓴다.
     * OCR은 같은 화면도 매번 상자가 몇 px씩 달라서, 이 단계가 없으면 라벨이 계속 옮겨 다닌다.
     * keep: 라벨을 띄운 채 읽은 결과(작은 변화) — 라벨 아래 글자는 못 읽으니 안 읽힌 옛 라벨도 그대로 둔다.
     * 아니면(큰 변화 뒤 깨끗한 화면) 안 읽힌 라벨은 지운다.
     */
    private fun merge(fresh: List<OverlayView.Label>, keep: Boolean): List<OverlayView.Label> {
        val used = BooleanArray(fresh.size)
        val out = ArrayList<OverlayView.Label>()
        for (old in tracked) {
            for (i in fresh.indices) {
                if (used[i] || !near(fresh[i].bounds, old.bounds)) continue
                // 같은 자리: 글자가 같으면 옛 것(자리째) 유지. 다르면 더 긴(정보 많은) 쪽을 — 새 쪽이면 상자도 새 것으로
                // (0.21까지는 옛 상자에 새 글자를 넣어, 짧게 읽혔던 줄의 좁은 상자에 긴 라벨이 들어가 …로 잘렸다)
                used[i] = true
                out.add(if (fresh[i].text.length > old.text.length) fresh[i] else old)
                break
            }
            if (keep && out.lastOrNull() !== old && fresh.none { overlap(it.bounds, old.bounds) > 0.3f }) out.add(old)
        }
        for (i in fresh.indices) if (!used[i]) out.add(fresh[i])
        tracked = out
        return out.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun near(a: Rect, b: Rect): Boolean {
        val tol = (14 * resources.displayMetrics.density).toInt()
        return Math.abs(a.left - b.left) <= tol && Math.abs(a.top - b.top) <= tol &&
            Math.abs(a.right - b.right) <= tol * 2 && Math.abs(a.bottom - b.bottom) <= tol
    }

    private var lastLabelSig = ""
    /** 라벨을 다시 그린다. 그 직후 들어올 프레임은 기준 화면으로만 쓴다. 라벨이 실제로 바뀌었으면 작은-변화 늦추기를 처음으로. */
    private fun draw(labels: List<OverlayView.Label>, boxes: List<Rect>, keep: Boolean) {
        val merged = merge(labels, keep)
        val sig = merged.joinToString("|") { "${it.text}@${it.bounds}" }
        if (sig == lastLabelSig) return          // 그대로면 건드리지 않는다 (깜빡임·흔들림 방지)
        synchronized(gateLock) { gate.onDrawn(); gate.onChanged() }
        lastLabelSig = sig
        overlay?.set(merged, boxes + merged.map { it.bounds })
    }
    private fun overlap(a: Rect, b: Rect): Float {
        val x = Rect(); if (!x.setIntersect(a, b)) return 0f
        val area = minOf(a.width() * a.height(), b.width() * b.height()).toFloat()
        return if (area <= 0) 0f else x.width() * x.height() / area
    }

    /** 화면을 32×64 밝기 격자로 줄인다. 맨 위 4줄(상태바: 시계·배터리)은 비교에서 뺀다. */
    private fun grid(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 32, 64, true)
        val px = IntArray(32 * 64); s.getPixels(px, 0, 32, 0, 0, 32, 64)
        val g = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            g[i] = if (i < 32 * 4) 0 else ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
        }
        return g
    }

    private var lastMasks: List<Rect> = emptyList()
    private var lastKeep = false

    /** withLabels: 라벨이 떠 있는 화면 — 라벨 아래 줄은 버리고(우리 한국어를 다시 읽지 않게) 옛 라벨은 둔다 */
    private fun recognize(bmp: Bitmap, seq: Int, withLabels: Boolean) {
        val masks = if (withLabels) overlay?.drawnRects() ?: emptyList() else emptyList()
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                if (!synchronized(gateLock) { gate.onResult(seq) }) return@addOnSuccessListener   // 읽는 사이 화면이 움직였다: 옛 결과
                lastText = t
                // 읽은 글자와 자리가 지난번과 같으면 라벨을 건드리지 않는다 (깜빡임·흔들림 방지)
                val sig = t.textBlocks.joinToString("|") { b -> b.lines.joinToString("|") { "${it.text}@${it.boundingBox}" } }
                if (sig != lastSig) {
                    lastSig = sig
                    lastMasks = masks; lastKeep = withLabels
                    draw(toLabels(t, masks), t.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox } }.filter { b -> masks.none { overlap(it, b) > 0.4f } }, withLabels)
                }
            }
            .addOnFailureListener { synchronized(gateLock) { gate.onResult(-1) } }
    }

    /** 마지막 OCR 결과 (읽은 줄 → 붙인 라벨, 없으면 null). 창의 '읽은 글자' 탭에서 보여 준다. */
    @Volatile var lastOcr: List<Pair<String, String?>> = emptyList()

    private val labeler by lazy { Labeler(dict.lexicon) }

    /**
     * OCR 결과 → 라벨. 줄마다 Labeler(숫자 규칙 → 사전 조각 → 한자음)로 만든다.
     * 기계 번역은 설정에서 켠 경우에만, 사전 조각이 하나도 없는 줄에 쓴다 (영어를 거쳐 품질이 낮다).
     */
    private fun toLabels(t: Text, masks: List<Rect> = emptyList()): List<OverlayView.Label> {
        val out = ArrayList<OverlayView.Label>()
        val seen = ArrayList<Pair<String, String?>>()
        val lines = ArrayList<Pair<String, Rect>>()
        val useMt = Mt.ready && Prefs.mtOn(this)
        val found = ArrayList<Pair<String, Paragraphs.Box>>()
        for (block in t.textBlocks) for (line in block.lines) for ((raw, box) in splitByGap(line)) {
            if (!hasHan(raw)) continue
            if (masks.any { overlap(it, box) > 0.4f }) continue      // 우리 라벨 아래(또는 우리 라벨 자체)
            lines.add(raw to Rect(box))
            found.add(raw to Paragraphs.Box(box.left, box.top, box.right, box.bottom))
        }
        // 두 줄로 접힌 이름은 합쳐서 읽는다 (Paragraphs)
        for (p in Paragraphs.group(found, isWord = { dict.exact(it) != null })) {
            val r = labeler.label(p.text)
            // 합친 문단이 안 읽히면 줄마다 따로 (두 줄 제목의 둘째 줄 "牛排150G+意面+饮品"은 혼자서는 읽힌다)
            val units = if (r?.text == null && p.n > 1) p.parts.map { Triple(it.first, it.second, 1) } else listOf(Triple(p.text, p.box, p.n))
            for ((raw, b, rows) in units) {
                val box = Rect(b.l, b.t, b.r, b.b)
                val rr = if (units.size == 1) r else labeler.label(raw)
                // 기계 번역은 사전이 못 읽었거나 글자 뜻(직역)으로 풀린 '문장'(리뷰·안내문)에만. 번역이 없으면 직역을 쓴다
                val tryMt = useMt && sentenceLike(raw) && (rr?.text == null || rr.literal)
                val mt = if (tryMt) clean(raw).let { lt -> Mt.cached(lt).also { if (it == null) Mt.request(lt) { scheduleRebuild() } } } else null
                if (mt != null && mt.isNotEmpty() && !hasHan(mt)) {   // 한자가 남은 번역("荤米线")은 버린다
                    out.add(OverlayView.Label(box, mt, OverlayView.Label.MT, rows)); seen.add(raw to "$mt ⟨번역⟩")
                } else if (rr?.text != null) {
                    out.add(OverlayView.Label(box, rr.text, rr.kind, rows)); seen.add(raw to rr.text)
                } else seen.add(raw to rr?.full)
            }
            if (out.size >= MAX_LABELS) break
        }
        lastOcr = seen
        lastOcrLines = lines
        return out
    }

    /**
     * OCR이 같은 높이의 글자를 한 줄로 합쳐 읽는 경우(왼쪽 분류 "老汤酱肉" + 오른쪽 제목 "老汤酱肉")를 가른다:
     * 단어 사이 가로 간격이 줄 높이의 1.2배를 넘으면 다른 줄로 본다.
     */
    private fun splitByGap(line: com.google.mlkit.vision.text.Text.Line): List<Pair<String, Rect>> {
        val lb = line.boundingBox ?: return emptyList()
        val els = line.elements.filter { it.boundingBox != null }
        if (els.size < 2) return listOf(line.text.trim() to lb)
        val gapMax = lb.height() * 1.2f
        val out = ArrayList<Pair<String, Rect>>()
        var text = StringBuilder(els[0].text); var box = Rect(els[0].boundingBox!!)
        for (i in 1 until els.size) {
            val e = els[i]; val eb = e.boundingBox!!
            if (eb.left - box.right > gapMax) {
                out.add(text.toString().trim() to box)
                text = StringBuilder(e.text); box = Rect(eb)
            } else { text.append(' ').append(e.text); box.union(eb) }
        }
        out.add(text.toString().trim() to box)
        return out
    }

    /** 마지막 OCR 결과 원본 (글자 + 화면 상자). '읽은 글자' 탭의 [OCR 결과 복사]로 내보내 tests/sim 에서 재현한다. */
    @Volatile var lastOcrLines: List<Pair<String, Rect>> = emptyList()
    fun lastOcrJson(): String {
        val sb = StringBuilder("[")
        lastOcrLines.forEachIndexed { i, (t, b) ->
            if (i > 0) sb.append(",")
            sb.append("{\"text\":\"").append(t.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\"box\":[")
                .append(b.left).append(",").append(b.top).append(",").append(b.right).append(",").append(b.bottom).append("]}")
        }
        return sb.append("]").toString()
    }

    /** 한자·숫자·영문만 남긴다 (한글 라벨이 줄에 섞여 읽혀도 매칭이 깨지지 않게) */
    private fun clean(s: String) = s.replace(Regex("[^\\u3400-\\u9FFF0-9A-Za-z]"), "")
    /**
     * 기계 번역을 맡길 만한 줄: 문장 부호가 있거나 한자 16자 이상인 것 (리뷰·안내문).
     * 메뉴 제목(菌锅米线, 【单人超值】美洲原切西冷)은 번역기가 "냄비 냄비 线", "미국 오리지널 커팅의 단일 가치"를 만들어 원문보다 나쁘다.
     */
    private fun sentenceLike(s: String) = s.count { it in '㐀'..'鿿' } >= 16 || s.any { it in "，。！？；～~" }

    private fun overlapRatio(a: Rect, line: Rect): Float {
        val x = Rect()
        if (!x.setIntersect(a, line)) return 0f
        val area = line.width().toFloat() * line.height()
        return if (area <= 0) 0f else x.width() * x.height() / area
    }
    private fun hasHan(s: String) = s.any { it in '㐀'..'鿿' }

    private fun showOverlay() {
        val v = OverlayView(this)
        v.coverMode = Prefs.coverOn(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        overlay = v
    }

    /** 창이 열려 있는 동안은 캡처를 쉰다 (우리 창을 읽지 않도록) */
    fun setPaused(p: Boolean) { paused = p; if (p) overlay?.labels = emptyList() else restart() }
    fun refreshNow() { overlay?.coverMode = Prefs.coverOn(this); restart() }
    /** 처음부터 다시: 라벨 지우고 멈춤 타이머부터 */
    private fun restart() { lastSig = ""; synchronized(gateLock) { gate.restart() } }

    companion object {
        const val ACTION_STOP = "app.chifan.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1
        private const val MAX_LABELS = 80
        @Volatile var instance: CaptureService? = null
    }
}
