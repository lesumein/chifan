package app.chifan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Kotlin 라벨러가 파이썬 시뮬레이터(tests/sim/labeler.py)와 같은 결과를 내는지 대조한다.
 * 기본 문장 몇 개는 항상 돌리고, CHIFAN_PARITY=<tsv 파일>(원문\t파이썬 결과)을 주면 그 파일 전체를 대조한다:
 *   python3 tests/sim/parity.py 말뭉치.txt > /tmp/p.tsv && CHIFAN_PARITY=/tmp/p.tsv ./gradlew testDebugUnitTest
 */
class LabelerParityTest {
    private val data = File("../../data")
    private fun f(n: String) = File(data, n).readText()
    private val labeler = Labeler(Lexicon(f("requests.json"), f("glossary.json"), f("foods.json"), f("hanja.json"), f("zi.json"), f("t2s.json"), f("sound.json")))

    private fun lab(s: String) = labeler.label(s)?.text ?: "—"

    @Test fun basics() {
        assertEquals("월 13 판매", lab("月售13"))
        assertEquals("초특가 전용 · 황먼지 · 1인 세트", lab("【狠爆专享】黄焖鸡单人餐"))
        assertEquals("—", lab("¥59.8"))
        assertEquals("만두", lab("餃子"))                 // 번체
        assertEquals("게 · 죽", lab("蟹粥"))             // 글자 뜻 단계에서 한 글자 사전 항목
    }

    @Test fun corpus() {
        val path = System.getenv("CHIFAN_PARITY") ?: return
        var bad = 0; var n = 0
        val diffs = ArrayList<String>()
        File(path).forEachLine { line ->
            val (src, want) = line.split('\t', limit = 2).let { it[0] to it.getOrElse(1) { "—" } }
            n++
            val got = lab(src)
            if (got != want) { bad++; if (diffs.size < 30) diffs.add("$src\n  py: $want\n  kt: $got") }
        }
        assertEquals("다른 줄 $bad / $n\n" + diffs.joinToString("\n"), 0, bad)
    }
}
