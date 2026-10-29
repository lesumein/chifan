package app.chifan

import org.json.JSONArray
import org.json.JSONObject

/**
 * 라벨러가 쓰는 사전 (안드로이드에 의존하지 않는다: JVM 단위 테스트에서 data 폴더의 json 으로 바로 만든다).
 * 덮어쓰는 순서는 tests/sim/labeler.py 와 같다: 요청사항 → 단어장(짧은 표기 우선) → 메뉴.
 */
class Lexicon(requests: String, glossary: String, foods: String, hanja: String, zi: String, t2s: String, sound: String) {
    private val map = HashMap<String, Hit>()
    var maxLen = 1
        private set
    private val hanjaMap = HashMap<Char, String>()
    private val ziMap = HashMap<Char, String>()
    private val t2sMap = HashMap<Char, Char>()
    private val soundMap = HashMap<Char, String>()

    init {
        val ri = JSONObject(requests).getJSONArray("items")
        for (i in 0 until ri.length()) { val o = ri.getJSONObject(i); put(Hit(o.getString("zh"), o.getString("ko"), null)) }
        val gi = JSONObject(glossary).getJSONArray("items")
        for (i in 0 until gi.length()) {
            val o = gi.getJSONObject(i); val ko = o.getString("ko")
            put(Hit(o.getString("zh"), ko, null, o.optString("short").ifEmpty { ko }))
        }
        val fs = JSONArray(foods)
        for (i in 0 until fs.length()) {
            val o = fs.getJSONObject(i); val zh = o.getString("zh"); val q = o.optString("q").ifEmpty { null }
            val ko = o.getString("ko")
            put(Hit(zh, ko, o.getString("id")))
            if (q != null && q != zh) put(Hit(q, ko, o.getString("id")))
        }
        val hj = JSONObject(hanja); for (k in hj.keys()) if (k.length == 1) hanjaMap[k[0]] = hj.getString(k)
        val zc = JSONObject(zi).getJSONObject("chars"); for (k in zc.keys()) if (k.length == 1) ziMap[k[0]] = zc.getString(k)
        val sd = JSONObject(sound).getJSONObject("chars"); for (k in sd.keys()) if (k.length == 1) soundMap[k[0]] = sd.getString(k)
        val ts = JSONObject(t2s); for (k in ts.keys()) if (k.length == 1) { val v = ts.getString(k); if (v.length == 1) t2sMap[k[0]] = v[0] }
    }

    private fun put(h: Hit) { map[h.zh] = h; if (h.zh.length > maxLen) maxLen = h.zh.length }

    fun exact(zh: String): Hit? = map[zh]
    /** 한국 한자음 */
    fun hanja(c: Char): String? = hanjaMap[c]
    /** 중국어 발음 한글 표기 (data/sound.json): 음역어·한자음 없는 글자용 */
    fun sound(c: Char): String? = soundMap[c]
    /** 글자 뜻. "~" = 소리만 빌린 글자, "-" = 뜻 없는 글자 */
    fun zi(c: Char): String? = ziMap[c]
    /** 번체 → 간체 */
    fun simp(s: String): String = buildString { for (c in s) append(t2sMap[c] ?: c) }
}
