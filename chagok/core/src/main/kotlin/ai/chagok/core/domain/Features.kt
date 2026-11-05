package ai.chagok.core.domain

import ai.chagok.core.buffer.Capture
import ai.chagok.core.derive.StateDeriver
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.SignalValue

/**
 * Capture(30초 원시 이벤트)에서 비교용 특징값을 뽑는다. 발화 시점 직전의 상태를 쓴다.
 * 창 시작 직전의 소스별 마지막 값(baseline)으로 상태를 시작한다. 시각은 창 시작으로 맞춰 넣는다:
 * "그 전부터 이 값이었다"는 뜻이고, 그래서 창 시작부터 이어진 브레이크 유지 시간은 하한으로만 센다.
 */
object FeatureExtractor {
    private val order = compareBy<SignalEvent>({ it.tsMonoNs }, { it.seq })

    fun extract(c: Capture): RecordFeatures {
        val d = StateDeriver()
        var holdAtTrigger: Double? = null
        var completedBeforeTrigger: Double? = null
        var steerAtTrigger: Double? = null
        var naviAtTrigger = NaviGuidance.DATA_UNAVAILABLE
        var odometer: Double? = null
        fun take(e: SignalEvent, s: ai.chagok.core.derive.DerivedState) {
            holdAtTrigger = s.brakeHoldSoFarSec
            completedBeforeTrigger = s.lastCompletedHoldSec ?: completedBeforeTrigger
            steerAtTrigger = s.steeringDeg
            naviAtTrigger = s.navi
            (e.value as? SignalValue.Odometer)?.let { odometer = it.totalMeters }
        }
        for (b in c.baseline.sortedWith(order)) take(b, d.onEvent(b.copy(tsMonoNs = c.windowStartNs)))
        for (e in c.events.sortedWith(order)) {
            val s = d.onEvent(e)
            if (e.tsMonoNs <= c.triggerTsNs) take(e, s)
        }
        // 사람은 증상을 느끼고 몇 초 뒤에 말하므로, 발화 시점에 밟는 중이면 그 값, 아니면 직전에 끝난 유지 시간
        return RecordFeatures(brakeHoldSec = holdAtTrigger ?: completedBeforeTrigger, steeringDeg = steerAtTrigger, navi = naviAtTrigger, odometerMeters = odometer)
    }
}
