package app.chifan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 사전 로딩과 줄당 라벨 시간이 폰에서 감당할 만한지 (JVM 기준, 폰은 몇 배 느릴 수 있다) */
class LabelerSpeedTest {
    @Test fun speed() {
        val data = File("../../data")
        fun f(n: String) = File(data, n).readText()
        val t0 = System.nanoTime()
        val lex = Lexicon(f("requests.json"), f("glossary.json"), f("foods.json"), f("hanja.json"), f("zi.json"), f("t2s.json"), f("sound.json"))
        val load = (System.nanoTime() - t0) / 1e6
        val lab = Labeler(lex)
        val lines = listOf("【爆款双拼】10块无骨炸鸡+薯条", "月售200+ 门店销量第2名", "现酱后熏口感加倍（三十六味升级版熏酱上新，现酱后熏）",
            "刚出锅的炸鸡，香喷喷热乎乎，团购验券很顺利，出餐很快。满意，推荐。", "¥29.8起", "选规格", "老汤酱肉", "秘爽西品大黑双", "香葱胡萝卜饼", "黄焖鸡米饭（微辣）")
        repeat(200) { for (l in lines) lab.label(l) }   // 데우기
        val n = 5000
        val t1 = System.nanoTime()
        repeat(n / lines.size) { for (l in lines) lab.label(l) }
        val per = (System.nanoTime() - t1) / 1e3 / n
        println("사전 로딩 ${"%.0f".format(load)}ms, 줄당 ${"%.1f".format(per)}µs, maxLen ${lex.maxLen}")
        assertTrue("줄당 ${per}µs", per < 2000)
    }
}
