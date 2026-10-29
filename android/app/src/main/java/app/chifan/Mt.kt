package app.chifan

import android.content.Context
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * 폰 안 번역 (중국어 → 한국어). 모델을 한 번 내려받은 뒤에는 오프라인으로 돈다.
 * 사전에 없는 줄에만 쓴다. 같은 줄은 한 번만 번역하고 기억한다.
 */
object Mt {
    private val zh = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
    private val ko = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()
    private val translator by lazy {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.KOREAN).build())
    }
    private val cache = LruCache<String, String>(800)
    private val inFlight = HashSet<String>()
    @Volatile var ready = false
        private set
    @Volatile var downloading = false
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /** 모델이 받아져 있는지 확인하고 ready를 갱신한다 */
    fun check(cb: (Boolean) -> Unit) {
        val mm = RemoteModelManager.getInstance()
        mm.isModelDownloaded(zh).addOnSuccessListener { a ->
            mm.isModelDownloaded(ko).addOnSuccessListener { b -> ready = a && b; cb(ready) }
                .addOnFailureListener { cb(false) }
        }.addOnFailureListener { cb(false) }
    }

    /** 모델 내려받기 (약 60MB). wifiOnly면 와이파이에서만. */
    fun download(wifiOnly: Boolean, onDone: (Boolean, String?) -> Unit) {
        if (downloading) return
        downloading = true; startedAt = System.currentTimeMillis(); lastError = null
        val c = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        translator.downloadModelIfNeeded(c)
            .addOnSuccessListener { ready = true; downloading = false; onDone(true, null) }
            .addOnFailureListener { ready = false; downloading = false; lastError = it.message; onDone(false, it.message) }
    }

    fun cached(text: String): String? = cache.get(text)

    /** 번역을 요청한다. 결과가 오면 onResult. 이미 요청 중이면 아무것도 안 한다. */
    fun request(text: String, onResult: () -> Unit) {
        if (!ready || text.length < 2) return
        synchronized(inFlight) { if (!inFlight.add(text)) return }
        translator.translate(text)
            .addOnSuccessListener { r ->
                val t = r.trim()
                if (t.isNotEmpty() && t != text) cache.put(text, t) else cache.put(text, "")
                synchronized(inFlight) { inFlight.remove(text) }
                onResult()
            }
            .addOnFailureListener { synchronized(inFlight) { inFlight.remove(text) } }
    }

    fun close() { runCatching { translator.close() } }
}
