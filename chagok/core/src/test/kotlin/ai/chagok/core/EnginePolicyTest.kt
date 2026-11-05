package ai.chagok.core

import ai.chagok.core.alert.AlertCandidate
import ai.chagok.core.alert.AlertGate
import ai.chagok.core.alert.AlertPolicy
import ai.chagok.core.alert.Decision
import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.InMemoryRepository
import ai.chagok.core.domain.Issue
import ai.chagok.core.domain.IssueStatus
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Range
import ai.chagok.core.model.Command
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 알림 정책·수명주기·Producer 상태 등 시연 흐름 밖의 규칙. */
@OptIn(ExperimentalCoroutinesApi::class)
class EnginePolicyTest {
    private val day = LocalDate.of(2026, 11, 6)
    private fun provisional(issueId: String) =
        ConditionProfile(issueId, ProfileState.PROVISIONAL, Range(1.5, 3.0), Range(-10.0, 10.0), setOf(NaviGuidance.STRAIGHT))

    @Test fun dailyCapSurvivesGateRestartThroughRepository() {
        val repo = InMemoryRepository()
        val prof = provisional("i1")
        fun gate() = AlertGate(AlertPolicy(dailyCap = 2, cooldownNs = Ns.sec(1.0)), today = { day }, ledger = repo)
        val g1 = gate()
        assertTrue(g1.decide(AlertCandidate("i1", Ns.sec(0.0), true, false), prof) is Decision.Allow)
        assertTrue(g1.decide(AlertCandidate("i1", Ns.sec(10.0), true, false), prof) is Decision.Allow)
        val g2 = gate()   // 앱 재시작: 게이트는 새로 만들어지지만 장부는 저장소에 남아 있다
        assertTrue(g2.decide(AlertCandidate("i1", Ns.sec(20.0), true, false), prof) is Decision.Deny, "재시작해도 하루 2회를 넘기지 않는다")
        assertEquals(2, repo.alertCount("i1", day))
    }

    @Test fun overlappingIssuesAlertOnlyTheFirstRecorded() = runTest {
        val repo = InMemoryRepository()
        // B가 먼저 저장됐지만 A가 먼저 생성된 문제. 생성 시각 순으로 A가 우선.
        repo.saveIssue(Issue("B", "진동", IssueStatus.NEW, "junho", createdAtEpochMs = 2000))
        repo.saveIssue(Issue("A", "끼익", IssueStatus.NEW, "minji", createdAtEpochMs = 1000))
        repo.saveProfile(provisional("B")); repo.saveProfile(provisional("A"))
        val engine = Engine(repo, gate = AlertGate(AlertPolicy(dailyCap = 5, cooldownNs = Ns.sec(60.0)), today = { day }, ledger = repo))
        val seen = ArrayList<EngineEvent>()
        val col = launch { engine.events.collect { seen.add(it) } }
        advanceUntilIdle()
        Fx.drive(0.0, 10.0, brakeAt = 3.0, brakeFor = 3.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        advanceUntilIdle()
        val alerts = seen.filterIsInstance<EngineEvent.Alert>()
        assertEquals(1, alerts.size, "한 번의 브레이크 유지에 알림은 하나")
        assertEquals("A", alerts.single().issueId, "먼저 기록된 문제만 알린다")
        col.cancel()
    }

    @Test fun endDriveFlushesCaptureAndCountsUnansweredAsUnknown() = runTest {
        val repo = InMemoryRepository()
        repo.saveIssue(Issue("A", "끼익", IssueStatus.NEW, "minji", createdAtEpochMs = 1000))
        repo.saveProfile(provisional("A"))
        val engine = Engine(repo, gate = AlertGate(AlertPolicy(dailyCap = 5, cooldownNs = Ns.sec(60.0)), today = { day }, ledger = repo))
        val seen = ArrayList<EngineEvent>()
        val col = launch { engine.events.collect { seen.add(it) } }
        advanceUntilIdle()
        Fx.drive(0.0, 10.0, brakeAt = 3.0, brakeFor = 3.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        engine.onCommand(Command.Capture(Ns.sec(10.0), "minji"))   // post 10초를 채우기 전에 주행 종료
        engine.onCommand(Command.EndDrive(Ns.sec(12.0)))
        advanceUntilIdle()
        assertEquals(1, seen.filterIsInstance<EngineEvent.Alert>().size)
        val rec = seen.filterIsInstance<EngineEvent.RecordCaptured>().single().record
        assertTrue(!rec.capture.complete, "마감된 Capture는 미완성으로 표시")
        assertEquals("minji", rec.authorId)
        val counts = repo.profile("A")!!.counts
        assertEquals(1, counts.unknown); assertEquals(0, counts.notFelt); assertEquals(0, counts.consecutiveNotFelt)
        col.cancel()
    }

    @Test fun permissionDeniedSourceBlocksMatchingAndShowsInStatus() {
        val repo = InMemoryRepository()
        repo.saveIssue(Issue("A", "끼익", IssueStatus.NEW, "minji", createdAtEpochMs = 1000))
        repo.saveProfile(provisional("A"))
        val engine = Engine(repo, gate = AlertGate(AlertPolicy(dailyCap = 5, cooldownNs = Ns.sec(60.0)), today = { day }, ledger = repo))
        engine.onProducerStatus("pleos", mapOf(
            Source.BRAKE to SourceStatus.AVAILABLE, Source.STEERING to SourceStatus.PERMISSION_DENIED,
            Source.ODOMETER to SourceStatus.AVAILABLE, Source.NAVI to SourceStatus.UNSUPPORTED,
        ))
        Fx.drive(0.0, 10.0, brakeAt = 3.0, brakeFor = 3.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        assertEquals(0, repo.alertCount("A", day), "조향 권한이 없으면 판정 불가, 알림 없음")
        val st = engine.state.value!!
        assertEquals(null, st.steeringDeg); assertEquals(NaviGuidance.DATA_UNAVAILABLE, st.navi)
        assertEquals(SourceStatus.PERMISSION_DENIED, engine.sourceStatus.value[Source.STEERING])
        assertEquals(SourceStatus.UNSUPPORTED, engine.sourceStatus.value[Source.NAVI])
        assertEquals(SourceStatus.AVAILABLE, engine.sourceStatus.value[Source.BRAKE])
    }

    @Test fun staleShowsInSourceStatusWhenProducerIsAvailable() {
        val engine = Engine(InMemoryRepository())
        engine.onProducerStatus("pleos", Source.entries.associateWith { SourceStatus.AVAILABLE })
        engine.onSignal(Fx.steer(0.0, 0.0)); engine.onSignal(Fx.brake(40.0, true))
        assertEquals(SourceStatus.STALE, engine.sourceStatus.value[Source.STEERING])
        assertEquals(SourceStatus.AVAILABLE, engine.sourceStatus.value[Source.BRAKE])
    }
}
