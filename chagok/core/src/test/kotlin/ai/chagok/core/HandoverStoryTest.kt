package ai.chagok.core

import ai.chagok.core.alert.AlertGate
import ai.chagok.core.alert.AlertPolicy
import ai.chagok.core.domain.EntrySource
import ai.chagok.core.domain.InMemoryRepository
import ai.chagok.core.domain.IssueStatus
import ai.chagok.core.domain.Member
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Tolerance
import ai.chagok.core.model.Command
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Observation
import ai.chagok.core.report.ReportBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기획서 03장 6장면: 순간 기록 → 탑승 브리핑 → 재현 알림 → 정비 리포트 → 수리 후 확인 → 해결과 전달. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandoverStoryTest {
    private val events = ArrayList<EngineEvent>()
    private var trip = "tue"
    private var clock = 1_000_000L
    private val repo = InMemoryRepository().apply { saveMember(Member("minji", "민지")); saveMember(Member("junho", "준호")) }
    private val engine = Engine(
        repo = repo,
        gate = AlertGate(AlertPolicy(dailyCap = 5, cooldownNs = Ns.sec(60.0)), today = { LocalDate.of(2026, 11, 6) }, ledger = repo),
        tolerance = Tolerance(brakeHoldSec = 0.5, steeringDeg = 6.0),
        tripId = { trip }, wallClockMs = { clock++ },
    )
    private fun run(evs: List<ai.chagok.core.model.SignalEvent>) = evs.forEach { engine.onSignal(it) }
    private inline fun <reified T : EngineEvent> drain(): List<T> = events.filterIsInstance<T>()
    private fun TestScope.briefingFor(member: String): ai.chagok.core.handover.Briefing {
        events.clear(); engine.onCommand(Command.Board(member)); advanceUntilIdle()
        return drain<EngineEvent.BriefingReady>().single().briefing
    }

    @Test fun sixScenes() = runTest {
        val collector = launch { engine.events.collect { events.add(it) } }
        advanceUntilIdle()

        // 장면 1 · 민지가 "기록해". 정차 후 새 문제로 등록하고 묘사를 덧붙인다.
        trip = "tue"
        run(Fx.drive(0.0, 32.0, brakeAt = 30.0, brakeFor = 2.4, steer = -3.0, g = NaviGuidance.STRAIGHT))
        engine.onCommand(Command.Capture(Ns.sec(32.0), "minji"))
        run(Fx.drive(32.5, 30.0, brakeAt = 30.0, brakeFor = 2.4, steer = -3.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val rec1 = drain<EngineEvent.RecordCaptured>().single().record
        assertNotNull(rec1.features.odometerMeters, "리포트용 주행거리가 기록에 남는다")
        engine.onCommand(Command.LinkRecord(rec1.id, null, "감속할 때 앞쪽 끼익 소리"))
        engine.onCommand(Command.Describe(rec1.id, "앞쪽에서 끼익"))
        advanceUntilIdle()
        val issueId = repo.issues().single().id
        assertEquals(IssueStatus.NEW, repo.issue(issueId)!!.status)
        assertEquals("앞쪽에서 끼익", repo.record(rec1.id)!!.description)

        // 장면 2 · 다음 날 준호 탑승: 브리핑에 민지의 미해결 1건이 "안 읽음"으로 나오고, 들으면 읽음·확인함이 된다.
        val b1 = briefingFor("junho")
        assertTrue(b1.resolved.isEmpty())
        val line = b1.unresolved.single()
        assertEquals(issueId, line.issue.id); assertTrue(line.unread); assertEquals("민지", line.lastAuthorName); assertEquals(1, line.recordCount)
        assertEquals(IssueStatus.ACKNOWLEDGED, repo.issue(issueId)!!.status, "다른 구성원이 확인하면 확인함")
        assertTrue(repo.issue(issueId)!!.isReadBy("junho"))
        assertTrue(briefingFor("junho").unresolved.single().unread.not(), "두 번째 탑승에는 읽음")
        assertTrue(briefingFor("minji").unresolved.single().unread.not(), "기록자 본인은 항상 읽음")

        // 장면 3 · 수요일 준호: 알림 켜고 같은 조건 → 알림 → 준호도 기록 → 같은 문제에 연결 → 잠정 패턴
        trip = "wed"; events.clear()
        engine.onCommand(Command.ArmAlert(issueId, true))
        run(Fx.drive(1100.0, 6.5, brakeAt = 1105.0, brakeFor = 2.1, steer = 2.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val alert1 = drain<EngineEvent.Alert>().single()
        assertTrue(!alert1.repairCheck, "수리 전 알림")
        engine.onCommand(Command.Capture(Ns.sec(1106.5), "junho"))
        run(Fx.drive(1107.0, 20.0, brakeAt = 1105.0, brakeFor = 2.1, steer = 2.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val rec2 = drain<EngineEvent.RecordCaptured>().single().record
        engine.onCommand(Command.LinkRecord(rec2.id, issueId))
        engine.onCommand(Command.Answer(issueId, alert1.alertId, Observation.FELT))
        advanceUntilIdle()
        assertEquals(ProfileState.PROVISIONAL, repo.profile(issueId)!!.state)

        // 장면 4 · 정비 리포트: 기록 2건, 출처 구분(차량 수신 수치 / 사용자 입력 묘사), 정비 노트 없음
        val report1 = ReportBuilder.build(repo, issueId)!!
        assertEquals(2, report1.observations.size)
        assertEquals(listOf("민지", "준호"), report1.observations.map { it.authorName })
        assertEquals(EntrySource.USER_INPUT, report1.observations[0].sourceOf("description"))
        assertEquals(EntrySource.VEHICLE, report1.observations[0].sourceOf("brakeHoldSec"))
        assertEquals("앞쪽에서 끼익", report1.observations[0].description)
        assertNull(report1.observations[1].description)
        assertEquals(1, report1.counts.felt)
        assertTrue(ReportBuilder.render(report1).contains("조향 -9°~8°"), ReportBuilder.render(report1))

        // 장면 5 · 준호가 정비 등록(패드 교체) → 수리 후 확인 모드. 같은 조건을 지나면 "확인 조건" 알림, 정차 후 못 느낌.
        events.clear()
        engine.onCommand(Command.StartRepairCheck(issueId, "junho", "패드 교체"))
        advanceUntilIdle()
        assertEquals(IssueStatus.IN_REPAIR_CHECK, repo.issue(issueId)!!.status)
        trip = "fri"
        run(Fx.drive(2100.0, 10.0, brakeAt = 2105.0, brakeFor = 2.0, steer = 0.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val alert2 = drain<EngineEvent.Alert>().single()
        assertTrue(alert2.repairCheck, "수리 후 확인 알림은 문구가 다르다")
        engine.onCommand(Command.Answer(issueId, alert2.alertId, Observation.NOT_FELT))
        val b2 = briefingFor("minji")
        assertEquals(issueId, b2.inRepairCheck.single().issue.id, "확인 중 항목으로 따로 나온다")
        assertTrue(b2.unresolved.isEmpty())
        val report2 = ReportBuilder.build(repo, issueId)!!
        assertEquals(1, report2.maintenance.size)
        assertEquals(EntrySource.USER_INPUT, report2.maintenance.single().source)
        assertEquals("junho", report2.maintenance.single().authorId)

        // 장면 6 · 준호가 해결로 기록 → 알림 멈춤 → 민지의 다음 탑승 브리핑 첫 줄로 전달 → 한 번 전달되면 다시 안 나옴
        events.clear()
        engine.onCommand(Command.ResolveIssue(issueId, "junho", "패드 교체 후 2주 소리 없음"))
        advanceUntilIdle()
        assertEquals(IssueStatus.RESOLVED, repo.issue(issueId)!!.status)
        assertEquals(ProfileState.STOPPED, repo.profile(issueId)!!.state)
        run(Fx.drive(2200.0, 10.0, brakeAt = 2205.0, brakeFor = 2.0, steer = 0.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        assertTrue(drain<EngineEvent.Alert>().isEmpty(), "해결 후에는 알리지 않는다")
        val b3 = briefingFor("minji")
        val resolvedLine = b3.resolved.single()
        assertEquals("준호", resolvedLine.resolvedByName); assertEquals("패드 교체 후 2주 소리 없음", resolvedLine.note)
        assertTrue(b3.unresolved.isEmpty() && b3.inRepairCheck.isEmpty())
        assertTrue(briefingFor("minji").isEmpty, "전달이 끝나면 브리핑에 더 나오지 않는다")
        assertTrue(briefingFor("junho").isEmpty, "해결한 사람에게는 전달하지 않는다")

        // 재발 · 민지가 같은 문제에 새 기록을 붙이면 다시 열리고 알림도 되살아난다
        events.clear(); trip = "sat"
        run(Fx.drive(3000.0, 32.0, brakeAt = 3030.0, brakeFor = 2.4, steer = -3.0, g = NaviGuidance.STRAIGHT))
        engine.onCommand(Command.Capture(Ns.sec(3032.0), "minji"))
        run(Fx.drive(3032.5, 30.0, brakeAt = 3030.0, brakeFor = 2.4, steer = -3.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val rec3 = drain<EngineEvent.RecordCaptured>().single().record
        engine.onCommand(Command.LinkRecord(rec3.id, issueId))
        advanceUntilIdle()
        assertEquals(IssueStatus.REOPENED, repo.issue(issueId)!!.status)
        val p = repo.profile(issueId)!!
        assertEquals(ProfileState.PROVISIONAL, p.state); assertEquals(3, p.recordCount)
        assertEquals(1, p.counts.felt); assertEquals(1, p.counts.notFelt); assertEquals(0, p.counts.consecutiveNotFelt)
        assertNull(repo.issue(issueId)!!.resolution)
        assertEquals(issueId, briefingFor("junho").unresolved.single().issue.id, "재발은 다시 미해결로 브리핑된다")

        collector.cancel()
    }
}
