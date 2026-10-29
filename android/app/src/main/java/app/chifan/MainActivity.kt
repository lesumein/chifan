package app.chifan

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 설정 화면: 접근성 서비스 켜기 → 메이퇀 열기. 나머지는 메이퇀 위에 뜨는 버튼에서 한다. */
class MainActivity : Activity() {
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private lateinit var status: TextView
    private lateinit var stepBtn: Button
    private lateinit var meituanBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(text("치판 吃饭", 26f, getColor(R.color.ink), bold = true))
        root.addView(text("중국 배달앱 화면에 한국어를 덧그리고, 메뉴 검색어를 복사해 줘요.", 15f, getColor(R.color.muted)))

        status = text("", 15f, getColor(R.color.ink)).apply {
            background = round(0xFFFFFFFF.toInt(), 14); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(status, lp(top = 18))

        stepBtn = button("1. 접근성 서비스 켜기", primary = true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "목록에서 ‘치판 한국어 덧그리기’를 찾아 켜 주세요", Toast.LENGTH_LONG).show()
        }
        root.addView(stepBtn, lp(top = 12))

        meituanBtn = button("2. 메이퇀 열기") { openMeituan() }
        root.addView(meituanBtn, lp(top = 8))

        val sw = Switch(this).apply {
            text = "화면 글자에 한국어 라벨 붙이기"
            textSize = 15f; setTextColor(getColor(R.color.ink))
            isChecked = Prefs.labelsOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setLabelsOn(this@MainActivity, on); ChifanService.instance?.refreshNow() }
        }
        root.addView(sw, lp(top = 18))

        root.addView(text("이렇게 써요", 16f, getColor(R.color.ink), bold = true), lp(top = 22))
        root.addView(text(
            "• 메이퇀을 열면 오른쪽에 치판 버튼이 떠요. 누르면 메뉴 고르기·요청사항·단어장이 작은 창으로 열려요.\n" +
            "• 메뉴를 누르면 중국어 검색어가 복사돼요. 메이퇀 검색창을 길게 눌러 붙여넣으면 돼요.\n" +
            "• 메이퇀 화면의 메뉴·옵션 글자 옆에 한국어 뜻이 작게 붙어요. 사전에 있는 말만 붙어요.\n" +
            "• 버튼은 위아래로 끌어 옮길 수 있어요.", 14f, getColor(R.color.muted)))

        root.addView(text("하지 않는 것", 16f, getColor(R.color.ink), bold = true), lp(top = 18))
        root.addView(text(
            "• 메이퇀 화면을 대신 누르거나 입력하지 않아요. 주문은 끝까지 직접 하세요.\n" +
            "• 읽은 글자를 어디에도 보내지 않아요. 서버가 없고, 인터넷도 안 써요.\n" +
            "• 메이퇀 앱이나 서버에 접근하지 않아요. 내 폰 화면을 내가 읽는 것뿐이에요.\n" +
            "• 접근성 권한은 메이퇀 앱 화면에만 쓰도록 제한돼 있어요(다른 앱 화면은 들어오지 않음).", 14f, getColor(R.color.muted)))

        root.addView(text("치판은 메이퇀과 관계없는 개인 도구예요. 메뉴 이름 중 ‘확인 중’ 표시는 중국어 표기를 아직 검증하지 못한 거예요.", 12f, getColor(R.color.muted)), lp(top = 18))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val on = serviceEnabled()
        val meituan = packageManager.getLaunchIntentForPackage(ChifanService.MEITUAN) != null
        status.text = (if (on) "✓ 접근성 서비스 켜짐 — 메이퇀을 열면 버튼이 떠요" else "○ 접근성 서비스가 꺼져 있어요") + "\n" +
            (if (meituan) "✓ 메이퇀 앱 있음" else "○ 메이퇀 앱이 없어요 — 아래 버튼으로 설치")
        stepBtn.text = if (on) "접근성 설정 열기" else "1. 접근성 서비스 켜기"
        meituanBtn.text = if (meituan) "2. 메이퇀 열기" else "2. 메이퇀 앱 설치하기"
    }

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, ChifanService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, true) || it.equals(ComponentName(this, ChifanService::class.java).flattenToShortString(), true) }
    }

    private fun openMeituan() {
        val launch = packageManager.getLaunchIntentForPackage(ChifanService.MEITUAN)
        if (launch != null) { startActivity(launch); return }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ChifanService.MEITUAN))) }
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
}
