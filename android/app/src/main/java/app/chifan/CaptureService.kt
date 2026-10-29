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
 * 안 하는 것: 어떤 앱도 누르거나 조작하지 않는다. 인터넷 권한이 없어 읽은 화면은 폰 밖으로 나가지 않는다.
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
    private var lastFrameAt = 0L
    private var lastHash = 0L
    @Volatile private var busy = false
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        dict = Dict.get(this)
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

    /** 새 프레임. 0.6초에 한 번, 화면이 바뀌었을 때만 OCR을 돌린다. */
    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = System.currentTimeMillis()
            if (busy || paused || !Prefs.labelsOn(this) || now - lastFrameAt < 600) return
            val plane = img.planes[0]
            val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
            val padded = Bitmap.createBitmap(rowStride / pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            val hash = frameHash(bmp)
            if (hash == lastHash) return
            lastHash = hash; lastFrameAt = now; busy = true
            recognize(bmp)
        } finally { img.close() }
    }

    /** 작게 줄인 그림의 픽셀을 합쳐 만든 값. 같으면 화면이 안 바뀐 것으로 본다. */
    private fun frameHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 24, 48, false)
        val px = IntArray(24 * 48); s.getPixels(px, 0, 24, 0, 0, 24, 48)
        var h = 1125899906842597L
        for (p in px) h = h * 31 + (p shr 4 and 0x0F0F0F)
        return h
    }

    private fun recognize(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                val boxes = t.textBlocks.flatMap { b -> b.lines.mapNotNull { it.boundingBox } }
                overlay?.set(toLabels(t), boxes); busy = false
            }
            .addOnFailureListener { busy = false }
    }

    /** 마지막 OCR 결과 (읽은 줄 → 붙인 라벨, 없으면 null). 창의 '읽은 글자' 탭에서 보여 준다. */
    @Volatile var lastOcr: List<Pair<String, String?>> = emptyList()

    /**
     * OCR 결과 → 라벨. 줄 전체가 사전에 있으면 그 뜻, 아니면 단어별로, 그래도 없으면 줄 안에서 찾은 뜻들을 이어 붙인다.
     * 이어 붙일 때는 한 글자짜리(甜, 冰, 热…)는 버리고, 찾은 말이 한자의 절반도 못 덮으면 라벨을 안 붙인다 — 틀린 라벨보다 빈 게 낫다.
     */
    private fun toLabels(t: Text): List<OverlayView.Label> {
        val out = ArrayList<OverlayView.Label>()
        val seen = ArrayList<Pair<String, String?>>()
        // 우리가 그린 라벨이 다음 캡처에서 다시 읽히는 것 방지: 라벨 자리를 절반 넘게 차지하는 줄은 버린다
        val own = overlay?.drawnRects() ?: emptyList()
        for (block in t.textBlocks) for (line in block.lines) {
            val box = line.boundingBox ?: continue
            if (own.any { overlapRatio(it, box) >= 0.5f }) continue
            val lt = clean(line.text)
            if (!hasHan(lt)) continue
            val whole = dict.exact(lt)
            if (whole != null) { out.add(OverlayView.Label(box, whole.ko, whole.foodId != null)); seen.add(lt to whole.ko); continue }
            val elLabels = ArrayList<String>()
            for (el in line.elements) {
                val et = clean(el.text); val eb = el.boundingBox ?: continue
                val h = dict.exact(et) ?: continue
                out.add(OverlayView.Label(eb, h.ko, h.foodId != null)); elLabels.add(h.ko)
            }
            if (elLabels.isNotEmpty()) { seen.add(lt to elLabels.joinToString(" · ")); continue }
            val hits = dict.annotate(lt).filter { it.zh.length >= 2 }
            val han = lt.count { it in '\u3400'..'\u9FFF' }
            if (hits.isEmpty() || hits.sumOf { it.zh.length } * 2 < han) { seen.add(lt to null); continue }
            val shown = hits.distinctBy { it.zh }.take(3)
            val s = shown.joinToString(" · ") { it.ko } + if (hits.size > 3) " …" else ""
            out.add(OverlayView.Label(box, s, shown.any { it.foodId != null }))
            seen.add(lt to s)
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
    fun setPaused(p: Boolean) { paused = p; if (p) overlay?.labels = emptyList() else lastHash = 0 }
    fun refreshNow() { overlay?.labels = emptyList(); lastHash = 0 }

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
