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
        if (!s.usable(Source.BRAKE) || !s.usable(Source.STEERING)) return MatchResult.DataUnavailable
        val hold = s.brakeHoldSoFarSec ?: return MatchResult.NoMatch
        val steer = s.steeringDeg ?: return MatchResult.DataUnavailable
        // 1겹: 기본 필터
        if (hold < base.minBrakeHoldSec || abs(steer) > base.maxAbsSteeringDeg) return MatchResult.NoMatch
        // 2겹: 프로파일 범위 (브레이크는 진행 중이므로 상한은 아직 모른다 → 하한만 본다)
        p.brakeHold?.let { if (hold < it.min) return MatchResult.NoMatch }
        p.steering?.let { if (steer !in it) return MatchResult.NoMatch }
        // 내비: DATA_UNAVAILABLE이면 항목 제외, 아니면 유형 일치 필요
        if (s.navi == NaviGuidance.DATA_UNAVAILABLE) return MatchResult.Match(naviChecked = false)
        if (p.navi.isNotEmpty() && s.navi !in p.navi) return MatchResult.NoMatch
        return MatchResult.Match(naviChecked = true)
    }
}
