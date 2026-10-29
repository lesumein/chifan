package app.chifan

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import java.io.File
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 설정 화면: 다른 앱 위에 표시 허용 → 덧그리기 시작(화면 캡처 허용) → 메이퇀 열기. 나머지는 떠다니는 버튼에서. */
class MainActivity : Activity() {
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private lateinit var status: TextView
    private lateinit var overlayBtn: Button
    private lateinit var startBtn: Button
    private lateinit var meituanBtn: Button
    private lateinit var mtBtn: Button
    private lateinit var mtBar: ProgressBar
    private lateinit var mtState: TextView
    private var mtReady = false
    private val tick = object : Runnable {
        override fun run() { refreshMt(); if (Mt.downloading) status.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(text("치판 吃饭", 26f, getColor(R.color.ink), bold = true))
        root.addView(text("중국 앱 화면 위에 한국어 뜻을 덧그리고, 메뉴 검색어를 복사해 줘요.", 15f, getColor(R.color.muted)))

        status = text("", 15f, getColor(R.color.ink)).apply {
            background = round(0xFFFFFFFF.toInt(), 14); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(status, lp(top = 18))

        overlayBtn = button("1. 다른 앱 위에 표시 허용") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.addView(overlayBtn, lp(top = 12))

        startBtn = button("2. 덧그리기 시작", primary = true) { toggleService() }
        root.addView(startBtn, lp(top = 8))

        meituanBtn = button("3. 메이퇀 열기") { openMeituan() }
        root.addView(meituanBtn, lp(top = 8))

        mtBtn = button("번역 모델 받기 (약 60MB, 한국에서 미리)") {
            Mt.download(this) { ok, err ->
                runOnUiThread {
                    Toast.makeText(this, if (ok) "번역 모델을 받았어요. 이제 오프라인에서도 번역돼요" else "받지 못했어요: ${err ?: "네트워크 확인"}", Toast.LENGTH_LONG).show()
                    mtReady = ok; refresh()
                }
            }
            status.removeCallbacks(tick); status.post(tick)
        }
        root.addView(mtBtn, lp(top = 8))
        mtBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = android.view.View.GONE }
        root.addView(mtBar, lp(top = 4))
        mtState = text("", 13f, getColor(R.color.muted))
        root.addView(mtState, lp(top = 2))

        val swMt = Switch(this).apply {
            text = "사전이 못 읽는 문장은 기계 번역으로 (리뷰·안내문, 파란 라벨, 품질 거침)"
            textSize = 15f; setTextColor(getColor(R.color.ink))
            isChecked = Prefs.mtOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setMtOn(this@MainActivity, on); CaptureService.instance?.refreshNow() }
        }
        root.addView(swMt, lp(top = 18))

        val swCover = Switch(this).apply {
            text = "원래 글자 위에 덮어쓰기 (끄면 옆에 작은 라벨)"
            textSize = 15f; setTextColor(getColor(R.color.ink))
            isChecked = Prefs.coverOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setCoverOn(this@MainActivity, on); CaptureService.instance?.refreshNow() }
        }
        root.addView(swCover, lp(top = 8))

        val sw = Switch(this).apply {
            text = "화면 글자에 한국어 라벨 붙이기"
            textSize = 15f; setTextColor(getColor(R.color.ink))
            isChecked = Prefs.labelsOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setLabelsOn(this@MainActivity, on); CaptureService.instance?.refreshNow() }
        }
        root.addView(sw, lp(top = 8))

        root.addView(text("이렇게 써요", 16f, getColor(R.color.ink), bold = true), lp(top = 22))
        root.addView(text(
            "• [덧그리기 시작]을 누르면 폰이 화면 캡처를 허용할지 물어요. ‘전체 화면’으로 허용하세요. 읽는 동안 상태바에 표시돼요.\n" +
            "• 메이퇀(또는 어떤 앱이든) 화면의 중국어 글자 위에 한국어 뜻을 덮어요. 빨강·갈색은 사전(메뉴·옵션·앱 화면 단어 2,000여 개를 조각으로 이어 붙임), 파랑은 사전이 못 읽은 문장(리뷰·안내문)을 기계 번역한 것이에요.\n" +
            "• 기계 번역은 번역 모델(약 60MB)을 한 번 받아야 돌아요. 구글 서버에서 받기 때문에 중국에 가기 전에 받아 두세요. 받은 뒤엔 인터넷 없이 돌아요.\n" +
            "• 오른쪽에 뜨는 치판 버튼을 누르면 메뉴 고르기·요청사항·단어장이 작은 창으로 열려요. 메뉴를 누르면 검색어가 복사돼요.\n" +
            "• 버튼은 위아래로 끌어 옮길 수 있어요. 끝나면 알림의 [중지]나 여기 [중지]를 누르세요.", 14f, getColor(R.color.muted)))

        root.addView(text("하지 않는 것", 16f, getColor(R.color.ink), bold = true), lp(top = 18))
        root.addView(text(
            "• 다른 앱 화면을 대신 누르거나 입력하지 않아요. 주문은 끝까지 직접 하세요.\n" +
            "• 읽은 화면·글자를 어디에도 보내지 않아요. 글자 인식도 번역도 폰 안에서만 돌아요. 인터넷은 번역 모델을 받을 때만 써요.\n" +
            "• 캡처한 화면을 저장하지 않아요. 글자를 읽자마자 버려요.\n" +
            "• 메이퇀 앱이나 서버에 접근하지 않아요. 내 폰 화면을 내가 읽는 것뿐이에요.", 14f, getColor(R.color.muted)))

        root.addView(text("치판은 메이퇀과 관계없는 개인 도구예요. 메뉴 이름 중 ‘확인 중’ 표시는 중국어 표기를 아직 검증하지 못한 거예요.", 12f, getColor(R.color.muted)), lp(top = 18))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume(); refresh()
        Mt.check { ok -> runOnUiThread { mtReady = ok; refresh() } }
        status.removeCallbacks(tick); status.post(tick)
    }
    override fun onPause() { super.onPause(); status.removeCallbacks(tick) }

    private var baseBytes = -1L

    /** 번역 모델 받기 상태: 받는 중(진행률) / 받음 / 실패 */
    private fun refreshMt() {
        val dl = Mt.downloading
        mtBar.visibility = if (dl) android.view.View.VISIBLE else android.view.View.GONE
        mtBtn.isEnabled = !dl && !mtReady
        mtBtn.text = when { mtReady -> "번역 모델: 받음 ✓"; dl -> "받는 중…"; else -> "번역 모델 받기 (약 60MB, 와이파이·데이터 모두 됨)" }
        if (!dl) baseBytes = -1
        mtState.text = when {
            dl -> progressText()
            mtReady -> "번역 모델이 있어요. 사전이 못 읽는 문장(리뷰·안내문)을 번역해요 (품질 거침)."
            Mt.lastError != null -> "받지 못했어요: ${Mt.lastError}. 인터넷 연결 확인 후 다시 눌러 주세요."
            else -> "선택 사항이에요. 사전·한자음만으로도 라벨이 붙어요."
        }
    }

    /** 다운로드 관리자에 잡힌 우리 앱의 받기 진행률. 없으면 앱 저장 공간이 늘어난 양으로 대신 보여 준다. */
    private fun progressText(): String {
        val sec = (System.currentTimeMillis() - Mt.startedAt) / 1000
        val dm = getSystemService(DownloadManager::class.java)
        var sofar = 0L; var total = 0L; var n = 0; var state = ""
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                val iSo = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTo = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iSt = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val iRe = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                while (c.moveToNext()) {
                    n++; sofar += c.getLong(iSo); total += maxOf(0L, c.getLong(iTo))
                    state = when (c.getInt(iSt)) {
                        DownloadManager.STATUS_PENDING -> "대기 중"
                        DownloadManager.STATUS_RUNNING -> "받는 중"
                        DownloadManager.STATUS_PAUSED -> when (c.getInt(iRe)) {
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "네트워크 기다리는 중"
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "와이파이 기다리는 중"
                            else -> "잠시 멈춤"
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> "받기 완료, 설치 중"
                        DownloadManager.STATUS_FAILED -> "실패"
                        else -> ""
                    }
                }
            }
        }
        if (n > 0) {
            val pct = if (total > 0) " (${sofar * 100 / total}%)" else ""
            return "$state… ${mb(sofar)} / ${if (total > 0) mb(total) else "?"}$pct · ${sec}초"
        }
        // 다운로드 관리자에 안 잡히면 앱 저장 공간 변화로
        val now = dirSize(File(applicationInfo.dataDir))
        if (baseBytes < 0) baseBytes = now
        return "받는 중… 저장 공간 +${mb(now - baseBytes)} · ${sec}초 (진행률을 못 읽는 방식이라 늘어나는 양만 보여요)"
    }

    private fun mb(b: Long) = String.format("%.1fMB", b / 1048576.0)
    private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val running = CaptureService.instance != null
        val meituan = packageManager.getLaunchIntentForPackage(MEITUAN) != null
        status.text = (if (overlayOk) "✓ 다른 앱 위에 표시: 허용됨" else "○ 다른 앱 위에 표시: 아직 허용 안 됨") + "\n" +
            (if (running) "✓ 덧그리기 실행 중 — 다른 앱으로 가면 버튼이 떠요" else "○ 덧그리기 꺼짐") + "\n" +
            (if (meituan) "✓ 메이퇀 앱 있음" else "○ 메이퇀 앱이 없어요 — 아래 버튼으로 설치") + "\n" +
            (if (mtReady) "✓ 번역 모델 있음 (선택 기능)" else if (Mt.downloading) "⏳ 번역 모델 받는 중…" else "○ 번역 모델 없음 (선택 기능, 없어도 됨)")
        overlayBtn.text = if (overlayOk) "다른 앱 위에 표시: 허용됨" else "1. 다른 앱 위에 표시 허용"
        overlayBtn.isEnabled = !overlayOk
        startBtn.text = if (running) "덧그리기 중지" else "2. 덧그리기 시작"
        meituanBtn.text = if (meituan) "3. 메이퇀 열기" else "3. 메이퇀 앱 설치하기"
        refreshMt()
    }

    private fun toggleService() {
        if (CaptureService.instance != null) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            status.postDelayed({ refresh() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "먼저 1번 ‘다른 앱 위에 표시’를 허용해 주세요", Toast.LENGTH_LONG).show(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { Toast.makeText(this, "화면 캡처를 허용해야 글자를 읽을 수 있어요", Toast.LENGTH_LONG).show(); return }
        val i = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "시작했어요. 메이퇀을 열면 오른쪽에 치판 버튼이 떠요", Toast.LENGTH_LONG).show()
        status.postDelayed({ refresh() }, 500)
    }

    private fun openMeituan() {
        val launch = packageManager.getLaunchIntentForPackage(MEITUAN)
        if (launch != null) { startActivity(launch); return }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$MEITUAN"))) }
            .onFailure { Toast.makeText(this, "스토어에서 美团 을 검색해 설치해 주세요", Toast.LENGTH_LONG).show() }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(0f, 1.25f)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else getColor(R.color.ink))
        background = round(if (primary) getColor(R.color.accent) else 0xFFFFFFFF.toInt(), 12)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * d }

    companion object {
        const val MEITUAN = "com.sankuai.meituan"
        private const val REQ_CAPTURE = 11
    }
}
