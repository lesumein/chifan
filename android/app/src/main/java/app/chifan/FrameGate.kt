package app.chifan

/**
 * "멈춘 화면만 읽는다"의 상태 기계. 안드로이드에 의존하지 않아 단위 테스트로 스크롤 시나리오를 돌릴 수 있다 (FrameGateTest).
 *
 * 캡처→인식→그리기에 0.3~0.6초가 걸리므로, 움직이는 동안 읽으면 라벨이 "예전 자리"에 찍힌다. 그래서:
 *  - 기준 화면과 12% 넘게 달라지면(스크롤·화면 전환 — 오른쪽 목록만 스크롤돼도 이 정도는 바뀐다) → 라벨을 즉시 지우고, 멈춘 뒤 프레임을 하나 요청(nudge)해 읽는다.
 *  - 0.6%~12% 달라지면(썸네일 로딩·작은 애니메이션) → 라벨은 그대로 두고, 멈춘 뒤 라벨을 한 프레임만 숨겨 깨끗한 화면을 읽은 뒤
 *    바로 되살린다. 결과가 같으면 라벨을 건드리지 않는다 (처음 뜰 때 깜빡이던 원인).
 *  - 프레임이 더 안 들어온 지 settleMs(200ms)가 지나면 멈춘 것으로 본다.
 *  - 읽는 사이 다시 움직이면 늦게 온 결과는 버린다 (seq).
 *  - 우리가 라벨을 그린 직후 들어오는 프레임은 비교 기준으로만 삼는다 (우리 그림을 변화로 보지 않게).
 *
 * 프레임은 32×64 밝기 격자(IntArray)로 받는다. 시간·타이머·동작은 밖에서 넣어 준다.
 */
class FrameGate(
    private val now: () -> Long,
    /** key 별로 하나만 걸리는 타이머. 같은 key 로 다시 걸면 앞 것은 취소 */
    private val postDelayed: (key: String, delayMs: Long, run: () -> Unit) -> Unit,
    private val cancel: (key: String) -> Unit,
    private val actions: Actions,
    private val settleMs: Long = 200,
    private val renudgeMs: Long = 150,
    private val changeThreshold: Float = 0.006f,
    private val bigThreshold: Float = 0.12f
) {
    interface Actions {
        fun clearLabels()
        /** 라벨을 잠깐 숨긴다 (숨기면 화면이 바뀌어 프레임이 하나 들어온다) */
        fun hideLabels()
        fun showLabels()
        /** 화면이 가만히 있을 때 프레임을 하나 만들어 달라 (라벨이 비어 있을 때) */
        fun nudge()
        /** 깨끗한 프레임을 읽어라. seq 는 결과를 돌려줄 때 그대로 */
        fun recognize(frameId: Int, seq: Int)
    }

    var moving = false; private set
    var waitingClean = false; private set
    var settleNext = false; private set
    var captureSeq = 0; private set
    var busy = false
    /** true: 작은 변화라 라벨을 지우지 않고 숨겨서 읽는 중 */
    var soft = false; private set
    private var baseline: IntArray? = null    // 라벨이 그려진 상태의 화면
    private var prev: IntArray? = null        // 직전 프레임 (움직임 감지용)
    private var hideAt = 0L
    private var nudges = 0
    private var hidden = false

    /** 프레임이 들어왔다. frameId 는 recognize 에 그대로 넘긴다. */
    fun onFrame(frameId: Int, g: IntArray) {
        val t = now()
        val dPrev = diffRatio(prev, g)
        prev = g
        if (settleNext) { settleNext = false; baseline = g; return }      // 우리가 그린 직후 프레임 = 기준
        if (moving) {                                                       // 아직 움직이는 중: 멈춤 타이머만 미룬다
            if (soft && diffRatio(baseline, g) >= bigThreshold) { enterMoving(soft = false); return }   // 작은 변화인 줄 알았는데 커짐
            if (dPrev >= changeThreshold) postDelayed("settle", settleMs, settle)
            return
        }
        if (waitingClean) {
            if (t - hideAt < 10) return
            // 숨겨서 읽을 땐 숨긴 직후의 첫 프레임이 당연히 많이 다르다 — 그 프레임이 바로 읽을 것
            if (!soft && dPrev >= changeThreshold && t - hideAt > 60) { enterMoving(soft = false); return }
            waitingClean = false
            cancel("renudge")
            baseline = g; busy = true
            actions.recognize(frameId, captureSeq)
            if (hidden) { actions.showLabels(); hidden = false; settleNext = true }   // 되살리면 프레임이 하나 더 온다: 기준으로만
            return
        }
        val d = diffRatio(baseline, g)
        if (d >= bigThreshold) enterMoving(soft = false)                   // 스크롤·화면 전환
        else if (d >= changeThreshold) enterMoving(soft = true)            // 썸네일 로딩 같은 작은 변화
    }

    /** OCR 결과가 왔다. true 면 쓰고, false 면 옛 화면 결과라 버린다 */
    fun onResult(seq: Int): Boolean {
        busy = false
        return seq == captureSeq && !moving
    }

    /** 라벨을 그렸다 (또는 다시 그렸다): 다음 프레임은 기준으로만 */
    fun onDrawn() { settleNext = true }

    /** 처음부터 (설정 변경·일시정지 해제) */
    fun restart() { baseline = null; enterMoving(soft = false) }

    private val settle: () -> Unit = {
        moving = false; waitingClean = true; hideAt = now(); nudges = 0
        if (soft) { actions.hideLabels(); hidden = true; postDelayed("renudge", renudgeMs, renudge) }
        else nudge()
    }
    private val renudge: () -> Unit = {
        if (waitingClean && nudges < 4) nudge()
        else if (waitingClean) { waitingClean = false; if (hidden) { actions.showLabels(); hidden = false }; enterMoving(soft = false) }
    }
    private fun nudge() { actions.nudge(); nudges++; postDelayed("renudge", renudgeMs, renudge) }

    private fun enterMoving(soft: Boolean) {
        captureSeq++
        this.soft = soft
        moving = true; waitingClean = false
        cancel("renudge")
        if (hidden) { actions.showLabels(); hidden = false }
        if (!soft) actions.clearLabels()
        postDelayed("settle", settleMs, settle)
    }

    companion object {
        /** 바뀐 칸의 비율 (밝기 차 24 넘는 칸). 기준이 없으면 1 */
        fun diffRatio(a: IntArray?, b: IntArray): Float {
            if (a == null || a.size != b.size) return 1f
            var n = 0
            for (i in b.indices) if (Math.abs(a[i] - b[i]) > 24) n++
            return n.toFloat() / b.size
        }
    }
}
