package app.chifan

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphsTest {
    private fun box(l: Int, t: Int, r: Int, b: Int) = Paragraphs.Box(l, t, r, b)

    private val words = setOf("鸿运", "鸿运当头", "龙抬头", "小吃", "艾窝窝")

    @Test fun wrappedCategoryIsJoined() {
        // 왼쪽 분류 칸: 폭 90, 줄 높이 20, 줄 간격 4. 鸿|运 에 걸친 鸿运 이 있어 합친다
        val ps = Paragraphs.group(listOf("春龙抬首鸿" to box(10, 100, 100, 120), "运当头" to box(10, 124, 64, 144), "音乐" to box(10, 200, 50, 220))) { it in words }
        assertEquals(2, ps.size)
        assertEquals("春龙抬首鸿运当头", ps[0].text); assertEquals(2, ps[0].n)
        assertEquals(100, ps[0].box.t); assertEquals(144, ps[0].box.b); assertEquals(100, ps[0].box.r)
    }

    @Test fun differentColumnOrFarApartStaysSeparate() {
        val ps = Paragraphs.group(listOf("老汤酱肉" to box(10, 100, 100, 120), "老汤酱肉" to box(200, 100, 300, 120), "月售12" to box(200, 150, 260, 165)))
        assertEquals(3, ps.size)
    }

    @Test fun titleThenBranchNameStaysSeparate() {
        // 제목 + 지점 이름: 모양은 접힌 줄과 같지만 경계에 걸친 말이 없다 → 따로
        val ps = Paragraphs.group(listOf("锦芳小吃 | 艾窝窝" to box(200, 100, 600, 140), "鲜鱼口店" to box(200, 160, 380, 194))) { it in words }
        assertEquals(2, ps.size)
    }

    @Test fun straddle() {
        org.junit.Assert.assertTrue(Paragraphs.straddles({ it in words }, "二月二龙", "抬头"))
        org.junit.Assert.assertFalse(Paragraphs.straddles({ it in words }, "三醒三擀", "手工好面"))
    }

    @Test fun titleThenSmallSubtitleStaysSeparate() {
        // 제목(높이 24) 바로 아래의 작은 설명(높이 14)은 같은 문단이 아니다
        val ps = Paragraphs.group(listOf("香熏猪脸肉套餐" to box(200, 100, 400, 124), "月售1丨约900克" to box(200, 128, 330, 142)))
        assertEquals(2, ps.size)
    }

    @Test fun splitLabelBalancesParts() {
        assertEquals(listOf("봄맞이 행사", "행운 가득"), Paragraphs.splitLabel("봄맞이 행사 · 행운 가득", 2))
        assertEquals(listOf("2월 2일 용두절 · 훈제 · 돼지 볼살", "2~3인 세트 · 900g"), Paragraphs.splitLabel("2월 2일 용두절 · 훈제 · 돼지 볼살 · 2~3인 세트 · 900g", 2))
        assertEquals(listOf("훈제 특대", "통닭다리"), Paragraphs.splitLabel("훈제 특대 통닭다리", 2))
        assertEquals(listOf("2월 2일", "용두절"), Paragraphs.splitLabel("2월 2일 용두절", 2))
        assertEquals(listOf("간식"), Paragraphs.splitLabel("간식", 2))
        assertEquals(listOf("단독"), Paragraphs.splitLabel("단독", 1))
    }
}
