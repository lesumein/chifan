package app.chifan

import android.content.Context

/** 기기 안에만 저장되는 설정 */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("chifan", Context.MODE_PRIVATE)
    fun labelsOn(ctx: Context) = p(ctx).getBoolean("labels", true)
    fun setLabelsOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("labels", v).apply()
    fun mtOn(ctx: Context) = p(ctx).getBoolean("mt", true)    // 0.21: 사전이 못 읽은 줄(리뷰·안내문)에만 쓰므로 기본 켜짐 (모델을 받았을 때만 동작)
    fun setMtOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("mt", v).apply()
    fun coverOn(ctx: Context) = p(ctx).getBoolean("cover", true)
    fun setCoverOn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("cover", v).apply()
    fun bubbleY(ctx: Context) = p(ctx).getInt("bubbleY", -1)
    fun setBubbleY(ctx: Context, y: Int) = p(ctx).edit().putInt("bubbleY", y).apply()
    fun memo(ctx: Context): MutableSet<String> = HashSet(p(ctx).getStringSet("memo", emptySet()) ?: emptySet())
    fun setMemo(ctx: Context, v: Set<String>) = p(ctx).edit().putStringSet("memo", v).apply()
    fun lastCat(ctx: Context) = p(ctx).getString("cat", "noodle") ?: "noodle"
    fun setLastCat(ctx: Context, v: String) = p(ctx).edit().putString("cat", v).apply()
}
