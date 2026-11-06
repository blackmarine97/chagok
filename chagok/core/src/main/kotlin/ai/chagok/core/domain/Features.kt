package ai.chagok.core.domain

import ai.chagok.core.buffer.Capture
import ai.chagok.core.derive.DerivedState
import ai.chagok.core.derive.StateDeriver
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.SignalValue

/**
 * Capture(30초 원시 이벤트)에서 비교용 특징값을 뽑는다.
 *
 * 브레이크 유지 시간은 기획서 정의대로 ON→OFF 완료값을 쓴다. 발화 시점에 밟고 있던 유지가 뒤 10초 안에 끝나면 그 완료값,
 * 끝나지 않으면 발화 시점까지의 진행 중 값(하한), 발화 시점에 밟고 있지 않았으면 직전에 끝난 유지.
 * 진행 중 값만 쓰면 알림이 (기록값 − 오차)에서 울리고 "느꼈어요"가 그 시점 값을 또 기록해 하한이 매번 오차만큼 내려간다.
 *
 * 조향·내비·주행거리는 발화 시점 직전 값. 창 시작 직전의 소스별 마지막 값(baseline)으로 상태를 시작하고, 시각은 창 시작으로
 * 맞춰 넣는다("그 전부터 이 값이었다"는 뜻. 그래서 창 시작부터 이어진 유지는 창 시작부터 하한으로 센다).
 */
object FeatureExtractor {
    private val order = compareBy<SignalEvent>({ it.tsMonoNs }, { it.seq })

    fun extract(c: Capture): RecordFeatures {
        val d = StateDeriver()
        var holdAtTrigger: Double? = null
        var completedBeforeTrigger: Double? = null
        var completedAfterTrigger: Double? = null
        var steerAtTrigger: Double? = null
        var naviAtTrigger = NaviGuidance.DATA_UNAVAILABLE
        var odometer: Double? = null
        var brakeOnAtTrigger = false
        fun take(e: SignalEvent, s: DerivedState) {
            holdAtTrigger = s.brakeHoldSoFarSec
            brakeOnAtTrigger = s.brakeOn
            completedBeforeTrigger = s.lastCompletedHoldSec ?: completedBeforeTrigger
            steerAtTrigger = s.steeringDeg
            naviAtTrigger = s.navi
            (e.value as? SignalValue.Odometer)?.let { odometer = it.totalMeters }
        }
        for (b in c.baseline.sortedWith(order)) take(b, d.onEvent(b.copy(tsMonoNs = c.windowStartNs)))
        for (e in c.events.sortedWith(order)) {
            val s = d.onEvent(e)
            if (e.tsMonoNs <= c.triggerTsNs) take(e, s)
            else if (brakeOnAtTrigger && completedAfterTrigger == null && !s.brakeOn) {
                // 발화 시점에 밟고 있던 유지가 끝났다. 공백으로 무효가 됐으면 null → 진행 중 값으로 떨어진다.
                completedAfterTrigger = s.lastCompletedHoldSec
                brakeOnAtTrigger = false   // 뒤 구간의 새 유지는 보지 않는다
            }
        }
        val hold = completedAfterTrigger ?: holdAtTrigger ?: completedBeforeTrigger
        val completed = completedAfterTrigger != null || (holdAtTrigger == null && completedBeforeTrigger != null)
        return RecordFeatures(brakeHoldSec = hold, steeringDeg = steerAtTrigger, navi = naviAtTrigger, odometerMeters = odometer, brakeHoldCompleted = completed)
    }
}
