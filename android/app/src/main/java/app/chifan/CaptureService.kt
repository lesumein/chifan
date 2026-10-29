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
    private var lastCaptureAt = 0L
    private var lastGrid: IntArray? = null   // 라벨이 그려진 상태의 화면(축소 밝기 격자). 이것과 거의 같으면 아무것도 안 함
    private var bigChange = false            // 화면이 크게 바뀜(스크롤·페이지 전환) → 옛 라벨을 다 버린다
    private var lastSig = ""             // 마지막 OCR 결과 서명 (같으면 라벨을 다시 그리지 않음)
    @Volatile private var busy = false
    @Volatile private var paused = false
    // 되먹임 방지: 캡처는 라벨을 숨긴 깨끗한 화면으로만 한다
    @Volatile private var waitingClean = false
    @Volatile private var hideAt = 0L
    @Volatile private var settleNext = false   // 우리가 라벨을 다시 그린 직후의 프레임은 '기준 화면'으로만 삼는다

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
            draw(toLabels(t), t.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox } })
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

    /**
     * 새 프레임. 흐름:
     * 1) 라벨이 그려진 화면이 지난번과 같으면 아무것도 안 한다.
     * 2) 달라졌으면 라벨을 숨기고(한 프레임), 숨긴 뒤 들어온 깨끗한 프레임을 OCR에 넘기고 라벨을 바로 되살린다.
     * 3) 라벨을 새로 그린 직후 들어오는 프레임은 비교 기준으로만 삼는다 (우리 그림을 '변화'로 보지 않게).
     */
    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (paused || !Prefs.labelsOn(this)) return
            val plane = img.planes[0]
            val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
            val padded = Bitmap.createBitmap(rowStride / pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)

            if (waitingClean) {
                if (now - hideAt < 25) return                 // 아직 라벨이 보이던 프레임일 수 있음
                if (now - hideAt > 600) { waitingClean = false; main.post { overlay?.visibility = android.view.View.VISIBLE }; return }
                waitingClean = false
                main.post { overlay?.visibility = android.view.View.VISIBLE }
                lastCaptureAt = now; busy = true
                recognize(bmp)
                return
            }
            val g = grid(bmp)
            if (settleNext) { settleNext = false; lastGrid = g; return }
            val diff = diffRatio(lastGrid, g)
            if (diff < 0.006f || busy || now - lastCaptureAt < 600) return   // 0.6% 미만(커서·아이콘 깜빡임)은 변화로 안 봄
            bigChange = diff > 0.30f
            lastGrid = g
            waitingClean = true; hideAt = now
            main.post { overlay?.visibility = android.view.View.INVISIBLE }
        } finally { img.close() }
    }

    private class Tracked(val label: OverlayView.Label, var missing: Int)
    private var tracked: MutableList<Tracked> = ArrayList()

    /**
     * 새 라벨을 기존 라벨과 맞춰 본다. 같은 글자가 거의 같은 자리에 있으면 기존 라벨(자리 포함)을 그대로 쓴다.
     * OCR은 같은 화면도 매번 상자가 몇 px씩 달라서, 이 단계가 없으면 라벨이 계속 옮겨 다닌다.
     * 한 번 안 읽힌 라벨은 한 바퀴 더 두었다가 지운다 (화면이 크게 바뀐 뒤에는 바로 다 버린다).
     */
    private fun merge(fresh: List<OverlayView.Label>): List<OverlayView.Label> {
        if (bigChange) { tracked.clear(); bigChange = false }
        val used = BooleanArray(fresh.size)
        val out = ArrayList<Tracked>()
        for (old in tracked) {
            var idx = -1
            for (i in fresh.indices) if (!used[i] && fresh[i].text == old.label.text && near(fresh[i].bounds, old.label.bounds)) { idx = i; break }
            if (idx >= 0) { used[idx] = true; out.add(Tracked(old.label, 0)) }
            else if (old.missing < 1) out.add(Tracked(old.label, old.missing + 1))
        }
        for (i in fresh.indices) if (!used[i]) out.add(Tracked(fresh[i], 0))
        tracked = out
        return out.map { it.label }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun near(a: Rect, b: Rect): Boolean {
        val tol = (14 * resources.displayMetrics.density).toInt()
        return Math.abs(a.left - b.left) <= tol && Math.abs(a.top - b.top) <= tol &&
            Math.abs(a.right - b.right) <= tol * 2 && Math.abs(a.bottom - b.bottom) <= tol
    }

    /** 라벨을 다시 그린다. 그 직후 들어올 프레임은 기준 화면으로만 쓴다. */
    private fun draw(labels: List<OverlayView.Label>, boxes: List<Rect>) {
        settleNext = true
        overlay?.set(merge(labels), boxes)
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

    /** 바뀐 칸의 비율 (밝기 차 24 넘는 칸) */
    private fun diffRatio(a: IntArray?, b: IntArray): Float {
        if (a == null) return 1f
        var n = 0
        for (i in b.indices) if (Math.abs(a[i] - b[i]) > 24) n++
        return n.toFloat() / b.size
    }

    private fun recognize(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                lastText = t
                // 읽은 글자와 자리가 지난번과 같으면 라벨을 건드리지 않는다 (깜빡임·흔들림 방지)
                val sig = t.textBlocks.joinToString("|") { b -> b.lines.joinToString("|") { "${it.text}@${it.boundingBox}" } }
                if (sig != lastSig) {
                    lastSig = sig
                    draw(toLabels(t), t.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox } })
                }
                busy = false
            }
            .addOnFailureListener { busy = false }
    }

    /** 마지막 OCR 결과 (읽은 줄 → 붙인 라벨, 없으면 null). 창의 '읽은 글자' 탭에서 보여 준다. */
    @Volatile var lastOcr: List<Pair<String, String?>> = emptyList()

    private val labeler by lazy { Labeler(dict) }

    /**
     * OCR 결과 → 라벨. 줄마다 Labeler(숫자 규칙 → 사전 조각 → 한자음)로 만든다.
     * 기계 번역은 설정에서 켠 경우에만, 사전 조각이 하나도 없는 줄에 쓴다 (영어를 거쳐 품질이 낮다).
     */
    private fun toLabels(t: Text): List<OverlayView.Label> {
        val out = ArrayList<OverlayView.Label>()
        val seen = ArrayList<Pair<String, String?>>()
        val useMt = Mt.ready && Prefs.mtOn(this)
        for (block in t.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            val raw = line.text.trim()
            if (!hasHan(raw)) continue
            val r = labeler.label(raw)
            if (r != null && (r.dictParts > 0 || !useMt)) {
                out.add(OverlayView.Label(box, r.text, r.kind)); seen.add(raw to r.text)
            } else if (useMt) {
                val lt = clean(raw)
                val mt = Mt.cached(lt)
                if (mt == null) { Mt.request(lt) { scheduleRebuild() }; seen.add(raw to (r?.text)); r?.let { out.add(OverlayView.Label(box, it.text, it.kind)) } }
                else if (mt.isNotEmpty()) { out.add(OverlayView.Label(box, mt, OverlayView.Label.MT)); seen.add(raw to "$mt ⟨번역⟩") }
                else { seen.add(raw to r?.text); r?.let { out.add(OverlayView.Label(box, it.text, it.kind)) } }
            } else seen.add(raw to null)
            if (out.size >= MAX_LABELS) break
        }
        lastOcr = seen
        return out
    }

    /** 한자·숫자·영문만 남긴다 (한글 라벨이 줄에 섞여 읽혀도 매칭이 깨지지 않게) */
    private fun clean(s: String) = s.replace(Regex("[^\\u3400-\\u9FFF0-9A-Za-z]"), "")

    private fun overlapRatio(a: Rect, line: Rect): Float {
        val x = Rect()
        if (!x.setIntersect(a, line)) return 0f
        val area = line.width().toFloat() * line.height()
        return if (area <= 0) 0f else x.width() * x.height() / area
    }
    private fun hasHan(s: String) = s.any { it in '㐀'..'鿿' }

    private fun showOverlay() {
        val v = OverlayView(this)
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
    fun setPaused(p: Boolean) { paused = p; if (p) overlay?.labels = emptyList() else { lastGrid = null; lastSig = ""; tracked.clear() } }
    fun refreshNow() { overlay?.labels = emptyList(); lastGrid = null; lastSig = ""; tracked.clear() }

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
