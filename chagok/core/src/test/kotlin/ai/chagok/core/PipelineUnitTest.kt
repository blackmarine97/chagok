package ai.chagok.core

import ai.chagok.core.alert.AlertCandidate
import ai.chagok.core.alert.AlertGate
import ai.chagok.core.alert.AlertPolicy
import ai.chagok.core.alert.Decision
import ai.chagok.core.alert.DenyReason
import ai.chagok.core.alert.InMemoryAlertLedger
import ai.chagok.core.buffer.Capture
import ai.chagok.core.buffer.CaptureService
import ai.chagok.core.buffer.TimedRing
import ai.chagok.core.derive.DerivedState
import ai.chagok.core.derive.StateDeriver
import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.Counts
import ai.chagok.core.domain.FeatureExtractor
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Range
import ai.chagok.core.match.BaseFilter
import ai.chagok.core.match.ConditionMatcher
import ai.chagok.core.match.MatchResult
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deriver · 링버퍼 · 특징값 · 매처 · 게이트의 세부 규칙. */
class PipelineUnitTest {
    // ── StateDeriver ──

    @Test fun newPressAfterInvalidHoldIsValidAgain() {
        val d = StateDeriver()
        d.onEvent(Fx.brake(0.0, true)); assertNull(d.onEvent(Fx.brake(3.0, false)).lastCompletedHoldSec, "3초 공백 → 무효")
        d.onEvent(Fx.brake(4.0, true)); assertEquals(1.0, d.onEvent(Fx.brake(5.0, false)).lastCompletedHoldSec!!, 1e-6)
    }

    @Test fun gapOnlyMattersWhileBrakeIsHeld() {
        val d = StateDeriver()
        d.onEvent(Fx.steer(0.0, 0.0)); d.onEvent(Fx.steer(10.0, 0.0))   // 10초 공백, 브레이크는 OFF
        d.onEvent(Fx.brake(10.0, true))
        assertEquals(1.0, d.onEvent(Fx.brake(11.0, false)).lastCompletedHoldSec!!, 1e-6)
    }

    @Test fun completedHoldPersistsUntilNextAndSoFarIsNullWhenOff() {
        val d = StateDeriver()
        d.onEvent(Fx.brake(0.0, true)); d.onEvent(Fx.brake(1.0, false))
        val later = d.onEvent(Fx.steer(2.0, 0.0))
        assertEquals(1.0, later.lastCompletedHoldSec!!, 1e-6); assertNull(later.brakeHoldSoFarSec); assertFalse(later.brakeOn)
    }

    @Test fun producerStatusMakesSourceUnavailable() {
        val d = StateDeriver()
        d.sourceStatus = mapOf(Source.STEERING to SourceStatus.PERMISSION_DENIED, Source.NAVI to SourceStatus.UNSUPPORTED, Source.BRAKE to SourceStatus.AVAILABLE)
        d.onEvent(Fx.navi(0.0, NaviGuidance.STRAIGHT)); d.onEvent(Fx.steer(0.0, 5.0))
        val s = d.onEvent(Fx.brake(0.0, true))
        assertEquals(setOf(Source.STEERING, Source.NAVI), s.unavailable)
        assertNull(s.steeringDeg, "권한 없는 조향은 값이 있어도 숨긴다"); assertEquals(NaviGuidance.DATA_UNAVAILABLE, s.navi)
        assertFalse(s.usable(Source.STEERING)); assertTrue(s.usable(Source.BRAKE))
        d.sourceStatus = emptyMap()
        assertEquals(5.0, d.onEvent(Fx.brake(0.5, true)).steeringDeg, "상태가 풀리면 다시 보인다")
    }

    @Test fun noGuidanceIsANormalValueAndNoOdometerMeansNotStopped() {
        val d = StateDeriver()
        d.onEvent(Fx.navi(0.0, NaviGuidance.NO_GUIDANCE))
        val s = d.onEvent(Fx.brake(1.0, false))
        assertEquals(NaviGuidance.NO_GUIDANCE, s.navi); assertFalse(s.stopped)
        assertTrue(Source.ODOMETER in s.stale, "한 번도 안 온 소스는 stale")
    }

    // ── TimedRing / CaptureService ──

    @Test fun ringEnforcesEventCapAndKeepsEvictedBaseline() {
        val ring = TimedRing(windowNs = Ns.sec(1000.0), maxEvents = 3)
        (0..4).forEach { ring.append(Fx.steer(it.toDouble(), it.toDouble())) }
        assertEquals(3, ring.size); assertEquals(Ns.sec(2.0), ring.oldestTs())
        val base = ring.baselineBefore(Ns.sec(2.0)).single()
        assertEquals(Ns.sec(1.0), base.tsMonoNs, "밀려난 것 중 마지막 값이 baseline")
        assertEquals(listOf(Ns.sec(2.0), Ns.sec(3.0)), ring.slice(Ns.sec(2.0), Ns.sec(3.0)).map { it.tsMonoNs }, "slice는 양끝 포함")
    }

    @Test fun baselinePrefersLatestInWindowValueBeforeFrom() {
        val ring = TimedRing(windowNs = Ns.sec(6.0))
        ring.append(Fx.steer(0.0, 0.0)); ring.append(Fx.navi(0.0, NaviGuidance.NO_GUIDANCE))
        ring.append(Fx.steer(5.0, 5.0)); ring.append(Fx.steer(8.0, 8.0)); ring.append(Fx.navi(8.0, NaviGuidance.STRAIGHT))
        val b = ring.baselineBefore(Ns.sec(7.0)).associateBy { it.source }
        assertEquals(Ns.sec(5.0), b[Source.STEERING]!!.tsMonoNs, "창 안에 더 최근 값이 있으면 그것")
        assertEquals(Ns.sec(0.0), b[Source.NAVI]!!.tsMonoNs, "창 밖으로 밀린 내비는 evicted에서")
        ring.clear(); assertTrue(ring.baselineBefore(Ns.sec(100.0)).isEmpty(), "clear는 evicted도 지운다")
    }

    @Test fun twoOverlappingCapturesBothComplete() {
        val cs = CaptureService()
        (0..60).forEach { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }          // 0~30초
        cs.capture(Ns.sec(30.0)); cs.capture(Ns.sec(33.0))
        val done = (61..90).flatMap { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }   // 30.5~45초
        assertEquals(listOf(Ns.sec(30.0), Ns.sec(33.0)), done.map { it.triggerTsNs })
        assertTrue(done.all { it.complete })
        assertEquals(Ns.sec(10.0), done[0].events.first().tsMonoNs); assertEquals(Ns.sec(13.0), done[1].events.first().tsMonoNs)
    }

    @Test fun lateCaptureCommandUsesAlreadyBufferedPostWindow() {
        val cs = CaptureService()
        (0..120).forEach { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }   // 0~60초가 이미 버퍼에 있다
        val late = assertNotNull(cs.capture(Ns.sec(40.0)), "뒤 10초가 이미 있으면 바로 완성")
        assertTrue(late.complete); assertEquals(Ns.sec(20.0), late.events.first().tsMonoNs); assertEquals(Ns.sec(50.0), late.events.last().tsMonoNs)
        assertNull(cs.capture(Ns.sec(55.0)), "뒤 10초가 일부만 있으면 pending")
        val done = (121..132).flatMap { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }.single()   // 60.5~66초
        assertEquals(Ns.sec(35.0), done.events.first().tsMonoNs); assertEquals(Ns.sec(65.0), done.events.last().tsMonoNs)
        assertEquals(61, done.events.size, "명령 전에 쌓인 55~60초와 뒤에 온 60.5~65초가 모두 들어간다")
    }

    @Test fun discardTempDropsPendingAndTemp() {
        val cs = CaptureService()
        (0..60).forEach { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }
        cs.capture(Ns.sec(30.0))
        val flushed = cs.discardTemp().single()
        assertEquals(Ns.sec(30.0), flushed.triggerTsNs); assertTrue(!flushed.complete, "\"기록해\"로 고정된 것은 버리지 않고 미완성으로 마감해 돌려준다")
        assertEquals(Ns.sec(10.0), flushed.events.first().tsMonoNs)
        assertTrue((61..90).flatMap { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }.isEmpty(), "마감된 pending은 다시 완성되지 않는다")
        assertNull(cs.captureAt(Ns.sec(30.0)), "폐기 직후 임시 버퍼는 비어 있다(새 이벤트 30.5초부터라 20초 전이 없음)")
    }

    @Test fun captureAtBoundaries() {
        val cs = CaptureService()
        (0..100).forEach { cs.onEvent(Fx.steer(it * 0.5, 0.0)) }   // 0~50초, 임시 창 10분이라 아무것도 안 밀림
        assertNotNull(cs.captureAt(Ns.sec(20.0)), "from == oldest 는 허용")
        assertNull(cs.captureAt(Ns.sec(19.9)), "앞 20초가 조금이라도 모자라면 null")
        val tail = assertNotNull(cs.captureAt(Ns.sec(48.0)))
        assertTrue(tail.events.last().tsMonoNs <= Ns.sec(50.0), "뒤 10초가 아직 없으면 있는 만큼만"); assertTrue(!tail.complete, "그리고 미완성으로 표시")
        assertTrue(assertNotNull(cs.captureAt(Ns.sec(40.0))).complete, "뒤 10초가 다 있으면 완성")
    }

    // ── FeatureExtractor ──

    private fun steers(from: Double, to: Double) = generateSequence(from) { it + 0.5 }.takeWhile { it <= to }.map { Fx.steer(it, -3.0) }.toList()

    @Test fun featuresFallBackToCompletedHoldWhenBrakeReleasedBeforeSpeech() {
        val events = steers(0.0, 25.0) + Fx.brake(10.0, true) + Fx.brake(12.4, false) + Fx.odo(10.0, 1000.0) + Fx.odo(14.0, 1500.0) + Fx.odo(16.0, 1600.0)
        val c = Capture(triggerTsNs = Ns.sec(15.0), events = events, preNs = Ns.sec(20.0), postNs = Ns.sec(10.0), complete = true)
        val f = FeatureExtractor.extract(c)
        assertEquals(2.4, f.brakeHoldSec!!, 1e-6, "발화 때 밟고 있지 않으면 직전에 끝난 유지 시간")
        assertEquals(-3.0, f.steeringDeg); assertEquals(NaviGuidance.DATA_UNAVAILABLE, f.navi, "내비 이벤트가 없으면 누락")
        assertEquals(1500.0, f.odometerMeters, "발화 시점 이전 마지막 주행거리")
    }

    @Test fun featuresUseBaselineBrakeAsLowerBoundFromWindowStart() {
        // 변화 시점에만 오는 브레이크: 창(10~30초) 전부터 밟고 있었고 창 안에는 브레이크 이벤트가 없다
        val c = Capture(triggerTsNs = Ns.sec(30.0), events = steers(10.0, 30.0), preNs = Ns.sec(20.0), postNs = Ns.sec(10.0), complete = true,
            baseline = listOf(Fx.brake(2.0, true), Fx.navi(1.0, NaviGuidance.STRAIGHT)))
        val f = FeatureExtractor.extract(c)
        assertEquals(20.0, f.brakeHoldSec!!, 1e-6, "창 시작부터 센 하한")
        assertEquals(NaviGuidance.STRAIGHT, f.navi)
    }

    @Test fun featuresPreferTheCompletedHoldThatWasInProgressAtSpeech() {
        // 30.0초 ON, 발화 32.0초(진행 중 2.0초), 32.4초 OFF → 기획서 정의(ON→OFF)대로 2.4초
        val events = steers(10.0, 42.0) + Fx.brake(30.0, true) + Fx.brake(32.4, false)
        val f = FeatureExtractor.extract(Capture(Ns.sec(32.0), events, Ns.sec(20.0), Ns.sec(10.0), true))
        assertEquals(2.4, f.brakeHoldSec!!, 1e-6); assertTrue(f.brakeHoldCompleted)
        // 뒤 10초 안에 안 끝나면 발화 시점까지의 하한
        val longHold = FeatureExtractor.extract(Capture(Ns.sec(32.0), steers(10.0, 42.0) + Fx.brake(30.0, true), Ns.sec(20.0), Ns.sec(10.0), true))
        assertEquals(2.0, longHold.brakeHoldSec!!, 1e-6); assertFalse(longHold.brakeHoldCompleted)
        // 발화 뒤에 끝났지만 공백으로 무효가 된 유지 → 역시 하한
        val gapped = FeatureExtractor.extract(Capture(Ns.sec(32.0), steers(10.0, 32.0) + Fx.brake(30.0, true) + Fx.brake(36.0, false), Ns.sec(20.0), Ns.sec(10.0), true))
        assertEquals(2.0, gapped.brakeHoldSec!!, 1e-6); assertFalse(gapped.brakeHoldCompleted)
    }

    @Test fun featuresIgnoreEventsAfterTrigger() {
        val events = steers(0.0, 30.0) + Fx.brake(21.0, true) + Fx.brake(24.0, false)   // 발화(20초) 뒤에 생긴 유지
        val f = FeatureExtractor.extract(Capture(Ns.sec(20.0), events, Ns.sec(20.0), Ns.sec(10.0), true))
        assertNull(f.brakeHoldSec, "발화 뒤의 일은 특징값에 넣지 않는다")
    }

    // ── ConditionMatcher ──

    private fun st(hold: Double?, steer: Double?, navi: NaviGuidance, stale: Set<Source> = emptySet(), unavailable: Set<Source> = emptySet()) =
        DerivedState(0L, hold != null, hold, null, steer, navi, stale, false, unavailable)
    private val prof = ConditionProfile("i1", ProfileState.PROVISIONAL, Range(1.3, 2.9), Range(-6.0, 11.0), setOf(NaviGuidance.STRAIGHT))

    @Test fun matcherChecksBrakeRangeBothEnds() {
        val m = ConditionMatcher()
        assertTrue(m.match(st(2.9, 0.0, NaviGuidance.STRAIGHT), prof) is MatchResult.Match, "상한 포함")
        assertEquals(MatchResult.NoMatch, m.match(st(5.0, 0.0, NaviGuidance.STRAIGHT), prof), "기록된 어떤 유지보다 길어지면 더는 이 조건이 아니다(먼저 기록된 문제가 뒤 문제를 가리는 창도 여기서 닫힌다)")
        assertEquals(MatchResult.DataUnavailable, m.match(st(2.0, 0.0, NaviGuidance.STRAIGHT).copy(brakeHoldInvalid = true, brakeHoldSoFarSec = null), prof), "유지 중 공백은 판정 불가")
        assertEquals(MatchResult.NoMatch, m.match(st(1.29, 0.0, NaviGuidance.STRAIGHT), prof.copy(brakeHold = Range(1.3, 2.9))))
        assertEquals(MatchResult.NoMatch, m.match(st(null, 0.0, NaviGuidance.STRAIGHT), prof), "밟고 있지 않으면 매치 없음")
    }

    @Test fun matcherDoesNotMatchWhileStopped() {
        val stopped = DerivedState(0L, true, 2.0, null, 0.0, NaviGuidance.STRAIGHT, emptySet(), stopped = true)
        assertEquals(MatchResult.NoMatch, ConditionMatcher().match(stopped, prof), "신호등 앞 브레이크 유지는 매치하지 않는다")
        assertTrue(ConditionMatcher().match(stopped.copy(stopped = false), prof) is MatchResult.Match)
    }

    @Test fun matcherTreatsMissingSteeringAsUnavailableAndEmptyNaviProfileAsAny() {
        val m = ConditionMatcher()
        assertEquals(MatchResult.DataUnavailable, m.match(st(2.0, null, NaviGuidance.STRAIGHT), prof))
        assertEquals(MatchResult.DataUnavailable, m.match(st(2.0, 0.0, NaviGuidance.STRAIGHT, unavailable = setOf(Source.STEERING)), prof))
        val anyNavi = prof.copy(navi = emptySet())
        val r = m.match(st(2.0, 0.0, NaviGuidance.TURN_AHEAD), anyNavi)
        assertTrue(r is MatchResult.Match && r.naviChecked, "기록된 내비 유형이 없으면 내비는 조건이 아니다")
    }

    @Test fun matcherWithoutProfileRangesIsJustTheBaseFilter() {
        val m = ConditionMatcher(BaseFilter(minBrakeHoldSec = 1.0, maxAbsSteeringDeg = 5.0))
        val bare = prof.copy(brakeHold = null, steering = null, navi = emptySet())
        assertTrue(m.match(st(1.0, -5.0, NaviGuidance.NO_GUIDANCE), bare) is MatchResult.Match)
        assertEquals(MatchResult.NoMatch, m.match(st(0.9, 0.0, NaviGuidance.NO_GUIDANCE), bare))
        assertEquals(MatchResult.NoMatch, m.match(st(2.0, 5.1, NaviGuidance.NO_GUIDANCE), bare))
    }

    // ── AlertGate ──

    private fun cand(t: Double, navi: Boolean = true, issue: String = "i1") = AlertCandidate(issue, Ns.sec(t), navi, false)

    @Test fun gateDeniesWhenDrivingRestrictedOrChoicePending() {
        val restricted = AlertGate(drivingGate = { false }, today = { LocalDate.of(2026, 11, 6) })
        assertEquals(Decision.Deny(DenyReason.DRIVING), restricted.decide(cand(0.0), prof))
        val g = AlertGate(today = { LocalDate.of(2026, 11, 6) })
        val pending = prof.copy(counts = Counts(notFelt = 3, consecutiveNotFelt = 3))
        assertTrue(g.decide(cand(0.0), pending) is Decision.Deny)
        assertEquals(Decision.Deny(DenyReason.PROFILE), g.decide(cand(0.0), prof.copy(state = ProfileState.STOPPED)))
    }

    @Test fun gateDoublesCooldownWhenNaviWasUnavailable() {
        val g = AlertGate(AlertPolicy(dailyCap = 10, cooldownNs = Ns.sec(100.0)), today = { LocalDate.of(2026, 11, 6) })
        assertTrue(g.decide(cand(0.0, navi = false), prof) is Decision.Allow)
        assertEquals(Decision.Deny(DenyReason.COOLDOWN), g.decide(cand(150.0, navi = false), prof), "내비 없이 판정한 후보는 200초")
        assertTrue(g.decide(cand(250.0, navi = false), prof) is Decision.Allow)
    }

    @Test fun gateUsesReducedCapAndKeepsIssuesSeparate() {
        val ledger = InMemoryAlertLedger()
        val g = AlertGate(AlertPolicy(dailyCap = 2, cooldownNs = Ns.sec(1.0), reducedDailyCap = 1), today = { LocalDate.of(2026, 11, 6) }, ledger = ledger)
        val reduced = prof.copy(state = ProfileState.REDUCED)
        assertTrue(g.decide(cand(0.0), reduced) is Decision.Allow)
        assertEquals(Decision.Deny(DenyReason.DAILY_CAP), g.decide(cand(10.0), reduced), "축소 상태는 하루 1회")
        assertTrue(g.decide(cand(10.0, issue = "i2"), prof.copy(issueId = "i2")) is Decision.Allow, "다른 문제는 별도 장부")
        assertEquals(1, ledger.alertCount("i1", LocalDate.of(2026, 11, 6))); assertEquals(1, ledger.alertCount("i2", LocalDate.of(2026, 11, 6)))
        assertEquals(0, ledger.alertCount("i1", LocalDate.of(2026, 11, 7)))
    }

    @Test fun gateAlertIdsAreUniqueWithinSession() {
        val g = AlertGate(AlertPolicy(dailyCap = 10, cooldownNs = 0L), today = { LocalDate.of(2026, 11, 6) })
        val ids = (1..3).map { (g.decide(cand(it.toDouble()), prof) as Decision.Allow).alertId }.toSet()
        assertEquals(3, ids.size)
    }
}
