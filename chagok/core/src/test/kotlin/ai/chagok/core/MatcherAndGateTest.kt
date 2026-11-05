package ai.chagok.core

import ai.chagok.core.alert.AlertCandidate
import ai.chagok.core.alert.AlertGate
import ai.chagok.core.alert.AlertPolicy
import ai.chagok.core.alert.Decision
import ai.chagok.core.derive.DerivedState
import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Range
import ai.chagok.core.match.ConditionMatcher
import ai.chagok.core.match.MatchResult
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Source
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatcherAndGateTest {
    private fun st(hold: Double?, steer: Double?, navi: NaviGuidance, stale: Set<Source> = emptySet()) =
        DerivedState(0L, hold != null, hold, null, steer, navi, stale, false)
    private val prof = ConditionProfile("i1", ProfileState.PROVISIONAL, Range(1.3, 2.9), Range(-6.0, 11.0), setOf(NaviGuidance.STRAIGHT))

    @Test fun baseFilterRejectsShortBrakeAndBigSteer() {
        val m = ConditionMatcher()
        assertEquals(MatchResult.NoMatch, m.match(st(1.0, 0.0, NaviGuidance.STRAIGHT), prof))
        assertEquals(MatchResult.NoMatch, m.match(st(2.0, 15.0, NaviGuidance.STRAIGHT), prof))
    }

    @Test fun profileNarrowsBeyondBaseFilter() {
        val m = ConditionMatcher()
        // 기본 필터는 통과(2.0초, 9도)하지만 프로파일 조향 범위(-6~11) 안이라 매치, 조향 -9는 범위 밖이라 거절
        assertTrue(m.match(st(2.0, 9.0, NaviGuidance.STRAIGHT), prof) is MatchResult.Match)
        assertEquals(MatchResult.NoMatch, m.match(st(2.0, -9.0, NaviGuidance.STRAIGHT), prof))
    }

    @Test fun naviTypeMustMatchUnlessUnavailable() {
        val m = ConditionMatcher()
        assertEquals(MatchResult.NoMatch, m.match(st(2.0, 0.0, NaviGuidance.TURN_AHEAD), prof))
        val r = m.match(st(2.0, 0.0, NaviGuidance.DATA_UNAVAILABLE), prof)
        assertTrue(r is MatchResult.Match && !r.naviChecked)
    }

    @Test fun staleIsDataUnavailable() {
        assertEquals(MatchResult.DataUnavailable, ConditionMatcher().match(st(2.0, 0.0, NaviGuidance.STRAIGHT, setOf(Source.BRAKE)), prof))
    }

    @Test fun gateEnforcesCooldownAndDailyCap() {
        var day = LocalDate.of(2026, 11, 6)
        val g = AlertGate(AlertPolicy(dailyCap = 2, cooldownNs = Ns.sec(600.0)), today = { day })
        fun c(t: Double, navi: Boolean = true) = AlertCandidate("i1", Ns.sec(t), navi, false)
        assertTrue(g.decide(c(0.0), prof) is Decision.Allow)
        assertTrue(g.decide(c(100.0), prof) is Decision.Deny)        // 쿨다운
        assertTrue(g.decide(c(700.0), prof) is Decision.Allow)
        assertTrue(g.decide(c(1400.0), prof) is Decision.Deny)       // 하루 2회
        day = day.plusDays(1)
        assertTrue(g.decide(c(90000.0), prof) is Decision.Allow)     // 다음 날
    }

    @Test fun gateDeniesCandidateProfileUnlessArmed() {
        val g = AlertGate(today = { LocalDate.of(2026, 11, 6) })
        val cand = prof.copy(state = ProfileState.CANDIDATE)
        assertTrue(g.decide(AlertCandidate("i1", 0L, true, false), cand) is Decision.Deny)
        assertTrue(g.decide(AlertCandidate("i1", 0L, true, false), cand.copy(armedByUser = true)) is Decision.Allow)
    }
}
