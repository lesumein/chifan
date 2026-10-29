package app.chifan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 메뉴 하나 (data/foods.json 한 항목)
class Food(
    val id: String, val cat: String, val ko: String, val aka: List<String>,
    val zh: String, val py: String, val desc: String, val spicy: Int, val emoji: String,
    val q: String?, val check: Boolean
) {
    val searchTerm get() = q ?: zh
}

class Category(val id: String, val ko: String, val zh: String, val emoji: String, val color: Int)
class Request(val id: String, val group: String, val ko: String, val zh: String, val excl: String?)
class Group(val id: String, val ko: String)
class Gloss(val zh: String, val ko: String, val group: String)

/** 덧그리기 결과: 화면 글자 안의 한 구간과 뜻 */
class Hit(val zh: String, val ko: String, val foodId: String?)

/**
 * 사전. assets(레포 data 폴더의 json)에서 한 번 읽어 둔다.
 * 웹앱(js/app.js)의 buildDict/annotate와 같은 규칙: 가장 긴 단어부터 맞추고,
 * 같은 표기는 메뉴 > 요청사항 > 단어장 순으로 우선.
 */
class Dict private constructor(ctx: Context) {
    val categories: List<Category>
    val foods: List<Food>
    val foodById: Map<String, Food>
    val reqGroups: List<Group>
    val requests: List<Request>
    val glossGroups: List<Group>
    val gloss: List<Gloss>

    private val map = HashMap<String, Hit>()
    var maxLen = 1
        private set
    /** 한자 → 한국어 음 (data/hanja.json, 유니코드 Unihan 기반) */
    private val hanja = HashMap<Char, String>()

    init {
        val cats = JSONArray(ctx.assets.open("categories.json").readBytes().decodeToString())
        categories = (0 until cats.length()).map { i ->
            val o = cats.getJSONObject(i)
            Category(o.getString("id"), o.getString("ko"), o.getString("zh"), o.getString("emoji"),
                android.graphics.Color.parseColor(o.getString("color")))
        }
        val fs = JSONArray(ctx.assets.open("foods.json").readBytes().decodeToString())
        foods = (0 until fs.length()).map { i ->
            val o = fs.getJSONObject(i)
            Food(o.getString("id"), o.getString("cat"), o.getString("ko"), strList(o.optJSONArray("aka")),
                o.getString("zh"), o.getString("py"), o.getString("desc"), o.optInt("spicy", 0), o.getString("emoji"),
                o.optString("q").ifEmpty { null }, o.optBoolean("check", false))
        }
        foodById = foods.associateBy { it.id }

        val rq = JSONObject(ctx.assets.open("requests.json").readBytes().decodeToString())
        reqGroups = groups(rq.getJSONArray("groups"))
        val ri = rq.getJSONArray("items")
        requests = (0 until ri.length()).map { i ->
            val o = ri.getJSONObject(i)
            Request(o.getString("id"), o.getString("group"), o.getString("ko"), o.getString("zh"), o.optString("excl").ifEmpty { null })
        }

        val gl = JSONObject(ctx.assets.open("glossary.json").readBytes().decodeToString())
        glossGroups = groups(gl.getJSONArray("groups"))
        val gi = gl.getJSONArray("items")
        gloss = (0 until gi.length()).map { i ->
            val o = gi.getJSONObject(i)
            Gloss(o.getString("zh"), o.getString("ko"), o.getString("group"))
        }

        val hj = JSONObject(ctx.assets.open("hanja.json").readBytes().decodeToString())
        for (k in hj.keys()) if (k.length == 1) hanja[k[0]] = hj.getString(k)

        gloss.forEach { put(it.zh, Hit(it.zh, it.ko, null)) }
        requests.forEach { put(it.zh, Hit(it.zh, it.ko, null)) }
        foods.forEach { f ->
            val ko = f.ko + if (f.spicy > 0) " " + "🌶".repeat(f.spicy) else ""
            put(f.zh, Hit(f.zh, ko, f.id))
            if (f.q != null && f.q != f.zh) put(f.q, Hit(f.q, f.ko, f.id))
        }
    }

    private fun put(zh: String, h: Hit) {
        map[zh] = h
        if (zh.length > maxLen) maxLen = zh.length
    }

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun groups(a: JSONArray): List<Group> =
        (0 until a.length()).map { i -> val o = a.getJSONObject(i); Group(o.getString("id"), o.getString("ko")) }

    fun exact(zh: String): Hit? = map[zh]
    fun reading(c: Char): String? = hanja[c]

    /** 글자 안에서 사전에 있는 구간을 모두 찾는다 (가장 긴 단어 우선, 겹치지 않음). */
    fun annotate(text: String): List<Hit> {
        val out = ArrayList<Hit>()
        var i = 0
        while (i < text.length) {
            var found: Hit? = null
            var n = minOf(maxLen, text.length - i)
            while (n >= 1) {
                val w = text.substring(i, i + n)
                val h = map[w]
                if (h != null) { found = h; break }
                n--
            }
            if (found != null) { out.add(found); i += n } else i++
        }
        return out
    }

    /** 한국어(초성·부분·입력 중) / 중국어 / 병음으로 메뉴 찾기 */
    fun searchFoods(raw: String): List<Food> {
        val q = Hangul.norm(raw)
        if (q.isEmpty()) return emptyList()
        val qpy = if (q.all { it in 'a'..'z' }) q else ""
        return foods.map { f -> f to score(f, q, qpy) }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun score(f: Food, q: String, qpy: String): Double {
        var best = -1.0
        fun tryField(text: String?, w: Int, py: Boolean = false) {
            if (text.isNullOrEmpty()) return
            val t = if (py) Hangul.normPy(text) else Hangul.norm(text)
            val qq = if (py) qpy else q
            val i = Hangul.find(t, qq)
            if (i < 0) return
            val s = w - i * 2 + (if (t.length == qq.length) 25 else 0) - t.length * 0.2
            if (s > best) best = s
        }
        tryField(f.ko, 100)
        f.aka.forEach { tryField(it, 85) }
        tryField(f.zh, 95)
        if (qpy.isNotEmpty()) tryField(f.py, 60, py = true)
        return best
    }

    fun searchGloss(raw: String): List<Gloss> {
        val q = Hangul.norm(raw)
        if (q.isEmpty()) return emptyList()
        return gloss.filter { Hangul.find(Hangul.norm(it.ko), q) >= 0 || it.zh.contains(raw.trim()) }
    }

    companion object {
        @Volatile private var inst: Dict? = null
        fun get(ctx: Context): Dict = inst ?: synchronized(this) {
            inst ?: Dict(ctx.applicationContext).also { inst = it }
        }
    }
}
