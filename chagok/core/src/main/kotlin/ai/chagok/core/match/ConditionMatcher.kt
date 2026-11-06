package ai.chagok.core.match

import ai.chagok.core.derive.DerivedState
import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Source
import kotlin.math.abs

/** 기본 필터. 흔한 주행을 걸러내는 첫 겹. 초안값, P0에서 조정. */
data class BaseFilter(val minBrakeHoldSec: Double = 1.5, val maxAbsSteeringDeg: Double = 10.0)

sealed class MatchResult {
    object NoMatch : MatchResult()
    object DataUnavailable : MatchResult()
    /** 기본 필터와 프로파일 범위를 모두 통과. naviChecked=false면 내비 항목 없이 판정(문턱 상향 대상). */
    data class Match(val naviChecked: Boolean) : MatchResult()
}

/**
 * 2단 판정: 기본 필터 → Issue별 프로파일 범위. 진행 중인 브레이크 유지(brakeHoldSoFarSec)로 판정하므로
 * 조건에 "들어가는 중"에 알릴 수 있다. 속도는 쓰지 않는다.
 */
class ConditionMatcher(private val base: BaseFilter = BaseFilter()) {
    fun match(s: DerivedState, p: ConditionProfile): MatchResult {
        // 브레이크·조향이 stale이거나 권한 없음·미지원이면 판정 불가. 알리지 않는다.
        if (!s.usable(Source.BRAKE) || !s.usable(Source.STEERING)) return MatchResult.DataUnavailable
        // 유지 중 신호 공백 → 유지 시간을 믿을 수 없다 → 판정 불가 (기획서: 신호 누락 구간은 판정 불가로 표시)
        if (s.brakeHoldInvalid) return MatchResult.DataUnavailable
        // 신호등 앞에서 브레이크를 밟고 서 있는 것은 "브레이크 유지 중 주행"이 아니다. 주행거리가 늘지 않으면 매치하지 않는다.
        // (주행거리 소스가 없으면 stopped=false라 판정은 계속된다. 정차 판단 규칙 자체는 P0 확인 항목.)
        if (s.stopped) return MatchResult.NoMatch
        val hold = s.brakeHoldSoFarSec ?: return MatchResult.NoMatch
        val steer = s.steeringDeg ?: return MatchResult.DataUnavailable
        // 1겹: 기본 필터
        if (hold < base.minBrakeHoldSec || abs(steer) > base.maxAbsSteeringDeg) return MatchResult.NoMatch
        // 2겹: 프로파일 범위. 하한을 넘는 순간 "진입"이고, 상한을 넘으면 기록된 어떤 유지보다 길어진 것이라 더는 이 조건이 아니다.
        //       (상한이 없으면 먼저 기록된 문제가 긴 유지에서도 계속 매치돼 뒤 문제를 영원히 가린다.)
        p.brakeHold?.let { if (hold < it.min || hold > it.max) return MatchResult.NoMatch }
        p.steering?.let { if (steer !in it) return MatchResult.NoMatch }
        // 내비: DATA_UNAVAILABLE이면 항목 제외, 아니면 유형 일치 필요
        if (s.navi == NaviGuidance.DATA_UNAVAILABLE) return MatchResult.Match(naviChecked = false)
        if (p.navi.isNotEmpty() && s.navi !in p.navi) return MatchResult.NoMatch
        return MatchResult.Match(naviChecked = true)
    }
}
