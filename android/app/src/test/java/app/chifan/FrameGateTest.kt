package app.chifan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스크롤 시나리오를 가짜 시계로 돌려 본다. 프레임은 32×64 격자 대신 작은 IntArray 로 흉내 낸다.
 * 화면을 "밝기 값"으로 표현: 같은 값이면 안 바뀐 것, 다르면 바뀐 것. 스크롤은 값이 계속 바뀌는 프레임 열.
 */
class FrameGateTest {
    private var clock = 0L
    private val timers = HashMap<String, Pair<Long, () -> Unit>>()
    private val log = ArrayList<String>()
    private val gate = FrameGate(
        now = { clock },
        postDelayed = { key, delay, run -> timers[key] = (clock + delay) to run },
        cancel = { key -> timers.remove(key) },
        actions = object : FrameGate.Actions {
            override fun clearLabels() { log.add("clear") }
            override fun hideLabels() { log.add("hide") }
            override fun showLabels() { log.add("show") }
            override fun nudge() { log.add("nudge") }
            override fun recognize(frameId: Int, seq: Int) { log.add("recognize#$frameId seq$seq") }
        }
    )
    private var frameId = 0

    /** 시간을 ms 만큼 보내며, 그 사이 걸린 타이머를 순서대로 실행 */
    private fun advance(ms: Long) {
        val end = clock + ms
        while (true) {
            val due = timers.entries.filter { it.value.first <= end }.minByOrNull { it.value.first } ?: break
            clock = due.value.first; timers.remove(due.key); due.value.second()
        }
        clock = end
    }

    /** 화면 값 v 로 프레임 하나 (전체가 같은 밝기) */
    private fun frame(v: Int, changedCells: Int = 100): Int {
        val g = IntArray(100) { i -> if (i < changedCells) v else 0 }
        gate.onFrame(++frameId, g)
        return frameId
    }

    /** 멈춘 뒤 nudge 로 들어오는 깨끗한 프레임 */
    private fun nudgedFrame(v: Int): Int { advance(20); return frame(v) }

    private fun settleAndRead(v: Int): Int {
        advance(200)                 // 멈춤 타이머 → nudge
        assertTrue(log.last() == "nudge")
        return nudgedFrame(v)        // 깨끗한 프레임 → recognize
    }

    @Test fun `처음 화면은 멈춘 뒤 한 번만 읽고 가만히 있으면 아무것도 안 한다`() {
        frame(10)
        assertEquals(listOf("clear"), log)
        val id = settleAndRead(10)
        assertEquals("recognize#$id seq1", log.last())
        assertTrue(gate.onResult(1))
        gate.onDrawn()
        frame(12)                    // 라벨이 그려진 프레임 → 기준으로만
        val before = log.size
        repeat(5) { advance(100); frame(12) }   // 같은 화면
        assertEquals(before, log.size)
        assertFalse(gate.moving)
    }

    @Test fun `스크롤 중에는 라벨을 지우고 읽지 않다가 멈추면 한 번 읽는다`() {
        frame(10); settleAndRead(10); gate.onResult(1); gate.onDrawn(); frame(12)
        log.clear()
        // 1초 동안 16ms 마다 다른 화면 (스크롤)
        var v = 60
        repeat(60) { advance(16); frame(v); v += 40 }   // 프레임마다 크게 다름 (내용이 움직임)
        assertEquals(listOf("clear"), log)        // 지우기 한 번뿐, 읽기 없음
        assertTrue(gate.moving)
        val id = settleAndRead(v)
        assertEquals(listOf("clear", "nudge", "recognize#$id seq2"), log)
        assertTrue(gate.onResult(2))
    }

    @Test fun `읽는 도중 다시 스크롤하면 늦게 온 결과는 버린다`() {
        frame(10)
        val id = settleAndRead(10)   // recognize seq1 진행 중 (busy)
        advance(100); frame(50)      // 읽는 사이 화면이 움직임 → moving, seq2
        assertTrue(gate.moving)
        assertFalse(gate.onResult(1))            // 옛 결과: 버림
        val id2 = settleAndRead(50)
        assertEquals("recognize#$id2 seq2", log.last())
        assertTrue(gate.onResult(2))
    }

    @Test fun `작은 깜빡임은 변화로 보지 않는다`() {
        frame(10); settleAndRead(10); gate.onResult(1); gate.onDrawn(); frame(12)
        log.clear()
        repeat(5) { advance(300); frame(99, changedCells = 0); frame(12) }  // 0칸 바뀜 = 같은 화면
        // 0.5% 미만 변화: 100칸 중 0칸
        assertTrue(log.isEmpty())
    }

    @Test fun `멈춘 뒤 프레임이 안 오면 다시 요청하고 끝내 안 오면 처음부터`() {
        frame(10)
        advance(200)                                  // settle → nudge 1
        assertEquals(listOf("clear", "nudge"), log)
        advance(150); advance(150); advance(150)      // renudge 2,3,4
        assertEquals(4, log.count { it == "nudge" })
        advance(150)                                  // 5번째: 포기하고 처음부터 (clear)
        assertEquals("clear", log.last())
        assertTrue(gate.moving)
        val id = settleAndRead(10)                    // 이번엔 프레임이 옴
        assertEquals("recognize#$id seq2", log.last())
    }

    @Test fun `썸네일 로딩 같은 작은 변화엔 라벨을 지우지 않고 한 프레임만 숨겨 다시 읽는다`() {
        frame(10); settleAndRead(10); gate.onResult(1); gate.onDrawn(); frame(12)
        log.clear()
        advance(300); frame(80, changedCells = 10)     // 10% 칸만 바뀜 (이미지 하나 로딩)
        assertTrue(gate.moving); assertTrue(gate.soft)
        assertTrue(log.isEmpty())                       // 지우지 않음
        advance(200)                                    // 멈춤 → 숨기기
        assertEquals(listOf("hide"), log)
        advance(20); val id = frame(80, changedCells = 10)   // 숨긴 뒤 들어온 깨끗한 프레임 → 읽고 바로 되살림
        assertEquals(listOf("hide", "recognize#$id seq2", "show"), log)
        assertTrue(gate.onResult(2))
        gate.onDrawn(); frame(12)                       // 그려진 프레임 = 기준
        assertFalse(gate.moving)
    }

    @Test fun `작은 변화로 시작했다가 크게 바뀌면 라벨을 지운다`() {
        frame(10); settleAndRead(10); gate.onResult(1); gate.onDrawn(); frame(12)
        log.clear()
        advance(100); frame(80, changedCells = 10)     // 작은 변화
        advance(16); frame(90)                          // 전체가 바뀜 (스크롤 시작)
        assertEquals(listOf("clear"), log)
        assertFalse(gate.soft)
    }

    @Test fun `우리가 그린 직후의 프레임은 움직임으로 보지 않는다`() {
        frame(10); settleAndRead(10); gate.onResult(1); gate.onDrawn()
        log.clear()
        frame(60)                                     // 라벨이 그려져 화면이 크게 달라진 프레임
        assertTrue(log.isEmpty()); assertFalse(gate.moving)
        advance(500); frame(60)                       // 그대로
        assertTrue(log.isEmpty())
        frame(120)                                    // 진짜로 바뀜
        assertEquals(listOf("clear"), log)
    }
}
