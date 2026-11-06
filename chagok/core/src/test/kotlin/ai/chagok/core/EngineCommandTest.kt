package ai.chagok.core

import ai.chagok.core.buffer.Capture
import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.Counts
import ai.chagok.core.domain.EntrySource
import ai.chagok.core.domain.InMemoryRepository
import ai.chagok.core.domain.Issue
import ai.chagok.core.domain.IssueStatus
import ai.chagok.core.domain.Member
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Range
import ai.chagok.core.domain.RecordFeatures
import ai.chagok.core.domain.SymptomRecord
import ai.chagok.core.handover.BriefingBuilder
import ai.chagok.core.model.AlertAdjustment
import ai.chagok.core.model.Command
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.report.ReportBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine 명령 하나하나, 그리고 브리핑·리포트 빌더. 시나리오 테스트가 지나가지 않는 가지들. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineCommandTest {
    private val repo = InMemoryRepository().apply {
        saveMember(Member("minji", "민지")); saveMember(Member("junho", "준호")); saveMember(Member("sora", "소라"))
    }
    private var clock = 1000L
    private val engine = Engine(repo, tripId = { "t" }, wallClockMs = { clock++ })
    private val events = ArrayList<EngineEvent>()
    private val cap = Capture(0L, emptyList(), 0L, 0L, true)
    private fun record(id: String, issue: String?, author: String, desc: String? = null) =
        SymptomRecord(id, issue, author, "t", cap, RecordFeatures(2.0, -3.0, NaviGuidance.STRAIGHT, odometerMeters = 32410.0), desc, recordedAtEpochMs = clock++)
    private fun issue(id: String, by: String, status: IssueStatus = IssueStatus.NEW) = Issue(id, "문제 $id", status, by, createdAtEpochMs = clock++)
    private inline fun <reified T : EngineEvent> drain() = events.filterIsInstance<T>()
    private fun TestScope.send(c: Command) { engine.onCommand(c); advanceUntilIdle() }

    private fun TestScope.withCollector(block: TestScope.() -> Unit) {
        val col = launch { engine.events.collect { events.add(it) } }
        advanceUntilIdle(); block(); col.cancel()
    }

    @Test fun captureAtOutsideTempBufferFails() = runTest { withCollector {
        send(Command.CaptureAt(Ns.sec(5.0), "minji"))
        assertEquals(1, drain<EngineEvent.RecordFailed>().size); assertTrue(repo.records().isEmpty())
    } }

    @Test fun describeUpdatesRecordAndReemits() = runTest { withCollector {
        repo.saveRecord(record("r1", null, "minji"))
        send(Command.Describe("r1", "앞쪽에서 끼익"))
        assertEquals("앞쪽에서 끼익", repo.record("r1")!!.description)
        assertEquals("앞쪽에서 끼익", drain<EngineEvent.RecordUpdated>().single().record.description)
        assertTrue(drain<EngineEvent.RecordCaptured>().isEmpty(), "묘사 수정은 새 기록이 아니다")
        send(Command.Describe("nope", "x")); assertEquals(1, drain<EngineEvent.RecordUpdated>().size, "없는 기록은 무시")
    } }

    @Test fun discardTempKeepsAConfirmedCaptureAsIncompleteRecord() = runTest { withCollector {
        Fx.drive(0.0, 30.0, brakeAt = 100.0, brakeFor = 0.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        engine.onCommand(Command.Capture(Ns.sec(30.0), "minji"))
        send(Command.DiscardTemp)
        val rec = drain<EngineEvent.RecordCaptured>().single().record
        assertEquals("minji", rec.authorId); assertTrue(!rec.capture.complete, "\"기록해\"는 정식 기록이라 폐기되지 않고 미완성으로 저장된다")
        Fx.drive(30.5, 15.0, brakeAt = 100.0, brakeFor = 0.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        advanceUntilIdle()
        assertEquals(1, drain<EngineEvent.RecordCaptured>().size, "같은 기록이 다시 완성되지 않는다")
        assertTrue(drain<EngineEvent.RecordFailed>().isEmpty())
    } }

    @Test fun relinkingARecordRecomputesTheOldIssueToo() = runTest { withCollector {
        repo.saveIssue(issue("A", "minji")); repo.saveIssue(issue("B", "minji"))
        repo.saveRecord(record("r1", null, "minji").copy(tripId = "t1")); repo.saveRecord(record("r2", null, "junho").copy(tripId = "t2"))
        send(Command.LinkRecord("r1", "A")); send(Command.LinkRecord("r2", "A"))
        assertEquals(ProfileState.PROVISIONAL, repo.profile("A")!!.state)
        send(Command.LinkRecord("r2", "B"))   // 잘못 붙인 기록을 B로 옮김
        assertEquals(ProfileState.CANDIDATE, repo.profile("A")!!.state, "기록이 빠진 A는 후보로 내려간다"); assertEquals(1, repo.profile("A")!!.recordCount)
        assertEquals(ProfileState.CANDIDATE, repo.profile("B")!!.state); assertEquals("B", repo.record("r2")!!.issueId)
        assertEquals(3, drain<EngineEvent.RecordUpdated>().size, "연결 변경은 RecordUpdated")
    } }

    @Test fun resumeReenablesAlertsFromRecordCount() = runTest { withCollector {
        repo.saveRecord(record("r1", "A", "minji").copy(tripId = "t1")); repo.saveRecord(record("r2", "A", "junho").copy(tripId = "t2"))
        repo.saveProfile(ai.chagok.core.domain.ProfileBuilder.build("A", repo.records()).copy(state = ProfileState.STOPPED, counts = Counts(notFelt = 3, consecutiveNotFelt = 3)))
        send(Command.AdjustAlert("A", AlertAdjustment.RESUME))
        val p = repo.profile("A")!!
        assertEquals(ProfileState.PROVISIONAL, p.state, "중지를 풀면 기록 수로 다시 계산"); assertEquals(0, p.counts.consecutiveNotFelt); assertEquals(3, p.counts.notFelt, "이력은 남는다")
        assertTrue(p.alertsEnabled)
    } }

    @Test fun issueWaitingForUserChoiceDoesNotShadowLaterIssues() = runTest { withCollector {
        // A가 먼저 생성됐지만 3회 못 느낌으로 선택 대기 중. 같은 조건의 B는 울려야 한다.
        repo.saveIssue(issue("A", "minji")); repo.saveIssue(issue("B", "junho"))
        val base = ConditionProfile("A", ProfileState.PROVISIONAL, Range(1.5, 2.5), Range(-6.0, 6.0), setOf(NaviGuidance.STRAIGHT))
        repo.saveProfile(base.copy(counts = Counts(notFelt = 3, consecutiveNotFelt = 3))); repo.saveProfile(base.copy(issueId = "B"))
        Fx.drive(0.0, 10.0, brakeAt = 3.0, brakeFor = 2.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        advanceUntilIdle()
        assertEquals(listOf("B"), drain<EngineEvent.Alert>().map { it.issueId }, "선택 대기 중인 문제는 뒤 문제를 가리지 않는다")
    } }

    @Test fun linkRecordToUnknownRecordIsNoOpAndToGivenIssueDoesNotCreateIssue() = runTest { withCollector {
        send(Command.LinkRecord("nope", null, "x")); assertTrue(repo.issues().isEmpty())
        repo.saveIssue(issue("A", "minji")); repo.saveRecord(record("r1", null, "junho"))
        send(Command.LinkRecord("r1", "A"))
        assertEquals(1, repo.issues().size); assertEquals("A", repo.record("r1")!!.issueId)
        assertEquals(ProfileState.CANDIDATE, repo.profile("A")!!.state)
    } }

    @Test fun adjustAlertKeepAndStop() = runTest { withCollector {
        repo.saveProfile(ConditionProfile("A", ProfileState.PROVISIONAL, Range(1.5, 2.5), null, emptySet(), counts = Counts(notFelt = 3, consecutiveNotFelt = 3)))
        send(Command.AdjustAlert("A", AlertAdjustment.KEEP))
        val kept = repo.profile("A")!!
        assertEquals(ProfileState.PROVISIONAL, kept.state); assertEquals(0, kept.counts.consecutiveNotFelt); assertEquals(3, kept.counts.notFelt, "이력은 남는다")
        send(Command.AdjustAlert("A", AlertAdjustment.STOP))
        assertEquals(ProfileState.STOPPED, repo.profile("A")!!.state); assertFalse(repo.profile("A")!!.alertsEnabled)
        assertEquals(2, drain<EngineEvent.ProfileChanged>().size)
    } }

    @Test fun armAlertOnUnknownIssueIsNoOp() = runTest { withCollector {
        send(Command.ArmAlert("nope", true)); assertTrue(drain<EngineEvent.ProfileChanged>().isEmpty())
    } }

    @Test fun resolveTwiceAndReopenOpenIssueAreNoOps() = runTest { withCollector {
        repo.saveIssue(issue("A", "minji"))
        send(Command.ReopenIssue("A")); assertEquals(IssueStatus.NEW, repo.issue("A")!!.status, "열려 있는 문제는 재발 처리 안 함")
        send(Command.ResolveIssue("A", "junho", "패드 교체"))
        send(Command.ResolveIssue("A", "sora", "다른 사람"))
        val i = repo.issue("A")!!
        assertEquals(IssueStatus.RESOLVED, i.status); assertEquals("junho", i.resolution!!.byMemberId); assertEquals("패드 교체", i.resolution.note)
        assertTrue(i.isReadBy("junho"))
        assertEquals(1, drain<EngineEvent.IssueChanged>().size, "두 번째 해결은 아무 일도 안 함")
        send(Command.StartRepairCheck("A", "junho", "x")); assertEquals(IssueStatus.RESOLVED, repo.issue("A")!!.status, "해결된 문제는 정비 등록 안 됨")
        val history = repo.maintenance()
        assertEquals(1, history.size, "해결 1건만 이력에 남고, 두 번째 해결과 해결 후 정비 등록은 남지 않는다")
        assertEquals("해결: 패드 교체", history.single().text); assertEquals("junho", history.single().authorId); assertEquals(EntrySource.USER_INPUT, history.single().source)
    } }

    @Test fun acknowledgeByCreatorDoesNotChangeStatusButByOtherDoes() = runTest { withCollector {
        repo.saveIssue(issue("A", "minji"))
        send(Command.AcknowledgeIssue("A", "minji")); assertEquals(IssueStatus.NEW, repo.issue("A")!!.status)
        assertTrue(drain<EngineEvent.IssueChanged>().isEmpty(), "이미 읽은 사람의 읽음은 변화 없음")
        send(Command.AcknowledgeIssue("A", "junho")); assertEquals(IssueStatus.ACKNOWLEDGED, repo.issue("A")!!.status)
        send(Command.AcknowledgeIssue("A", "junho")); assertEquals(1, drain<EngineEvent.IssueChanged>().size)
        repo.saveIssue(issue("B", "minji", IssueStatus.REOPENED))
        send(Command.AcknowledgeIssue("B", "junho")); assertEquals(IssueStatus.REOPENED, repo.issue("B")!!.status, "NEW가 아닌 상태는 그대로")
    } }

    @Test fun logMaintenanceFromVehicleHasNoAuthor() = runTest { withCollector {
        send(Command.LogMaintenance("A", "주행거리 32,410 km", authorId = "junho", fromVehicle = true))
        send(Command.LogMaintenance(null, "와이퍼 교체", authorId = "junho"))
        val (veh, user) = repo.maintenance()
        assertEquals(EntrySource.VEHICLE, veh.source); assertNull(veh.authorId); assertEquals("A", veh.issueId)
        assertEquals(EntrySource.USER_INPUT, user.source); assertEquals("junho", user.authorId); assertNull(user.issueId)
    } }

    @Test fun markRepairCheckToggles() = runTest { withCollector {
        repo.saveIssue(issue("A", "minji", IssueStatus.ACKNOWLEDGED))
        engine.markRepairCheck("A", true); assertEquals(IssueStatus.IN_REPAIR_CHECK, repo.issue("A")!!.status)
        engine.markRepairCheck("A", false); assertEquals(IssueStatus.ACKNOWLEDGED, repo.issue("A")!!.status)
    } }

    @Test fun producerStatusCombinesAcrossProducers() {
        engine.onProducerStatus("a", mapOf(Source.BRAKE to SourceStatus.PERMISSION_DENIED, Source.STEERING to SourceStatus.UNSUPPORTED))
        engine.onProducerStatus("b", mapOf(Source.BRAKE to SourceStatus.AVAILABLE, Source.STEERING to SourceStatus.PERMISSION_DENIED))
        val s = engine.sourceStatus.value
        assertEquals(SourceStatus.AVAILABLE, s[Source.BRAKE], "하나라도 사용 가능하면 사용 가능")
        assertEquals(SourceStatus.PERMISSION_DENIED, s[Source.STEERING], "둘 다 안 되면 더 나쁜 쪽")
        assertEquals(SourceStatus.NOT_STARTED, s[Source.ODOMETER], "아무도 보고하지 않은 소스")
        assertEquals(SourceStatus.NOT_STARTED, s[Source.NAVI])
    }

    @Test fun endDriveWithNothingPendingIsHarmless() = runTest { withCollector {
        send(Command.EndDrive(Ns.sec(1.0)))
        assertTrue(events.isEmpty()); assertTrue(repo.records().isEmpty())
    } }

    // ── Answer · 알림 수명 ──

    /** 두 주행(t1,t2)의 기록 2건으로 잠정 패턴을 만들고, 40초 주행 끝에 브레이크를 밟아 알림을 하나 받는다. */
    private fun TestScope.alertAfterDrive(t0: Double = 0.0): EngineEvent.Alert {
        if (repo.issue("A") == null) {
            repo.saveIssue(issue("A", "minji"))
            repo.saveRecord(record("r1", "A", "minji").copy(tripId = "t1")); repo.saveRecord(record("r2", "A", "junho").copy(tripId = "t2"))
            repo.saveProfile(ai.chagok.core.domain.ProfileBuilder.build("A", repo.records()))
        }
        events.clear()
        Fx.drive(t0, 40.0, brakeAt = t0 + 33.0, brakeFor = 3.0, steer = 0.0, g = NaviGuidance.STRAIGHT).forEach { engine.onSignal(it) }
        advanceUntilIdle()
        return drain<EngineEvent.Alert>().single()
    }

    @Test fun feltAnswerCapturesTheAlertMomentAndLinksIt() = runTest { withCollector {
        val a = alertAfterDrive()
        assertEquals(2, a.recordCount, "알림 문구용 기록 수"); assertFalse(a.repairCheck)
        assertEquals(ProfileState.PROVISIONAL, repo.profile("A")!!.state)
        send(Command.Answer("A", a.alertId, ai.chagok.core.model.Observation.FELT, memberId = "junho"))
        val rec = drain<EngineEvent.RecordCaptured>().single().record
        assertEquals("A", rec.issueId, "느꼈어요 → 알림 시점 30초를 기록으로 만들어 같은 문제에 연결"); assertEquals("junho", rec.authorId)
        assertEquals(a.tsMonoNs, rec.capture.triggerTsNs); assertTrue(rec.capture.events.isNotEmpty())
        val p = repo.profile("A")!!
        assertEquals(3, p.recordCount); assertEquals(1, p.counts.felt); assertEquals(ProfileState.PROVISIONAL, p.state)
    } }

    @Test fun feltAnswerWithoutBufferCoverageOnlyCounts() = runTest { withCollector {
        val a = alertAfterDrive()
        send(Command.DiscardTemp)   // 정차 화면을 닫아 임시 버퍼가 비워진 뒤에 답한 경우
        send(Command.Answer("A", a.alertId, ai.chagok.core.model.Observation.FELT, memberId = "junho"))
        assertTrue(drain<EngineEvent.RecordCaptured>().isEmpty()); assertEquals(1, repo.profile("A")!!.counts.felt); assertEquals(2, repo.profile("A")!!.recordCount)
    } }

    @Test fun answersToUnknownOrAlreadyClosedAlertsAreIgnored() = runTest { withCollector {
        val a = alertAfterDrive()
        send(Command.Answer("A", "nope", ai.chagok.core.model.Observation.NOT_FELT))
        assertEquals(Counts(), repo.profile("A")!!.counts, "모르는 알림 id는 무시")
        send(Command.EndDrive(Ns.sec(41.0)))
        assertEquals(1, repo.profile("A")!!.counts.unknown, "답 없이 주행 종료 → 미확인")
        send(Command.Answer("A", a.alertId, ai.chagok.core.model.Observation.FELT, memberId = "junho"))
        val c = repo.profile("A")!!.counts
        assertEquals(0, c.felt, "이미 미확인으로 센 알림에 늦게 답해도 이중 집계하지 않는다"); assertEquals(1, c.unknown)
        assertTrue(drain<EngineEvent.RecordCaptured>().isEmpty())
    } }

    @Test fun userChoiceOutsideRepairCheckIsNotFlaggedAsRepair() = runTest { withCollector {
        val a = alertAfterDrive()
        repo.saveProfile(repo.profile("A")!!.copy(counts = Counts(notFelt = 2, consecutiveNotFelt = 2)))
        send(Command.Answer("A", a.alertId, ai.chagok.core.model.Observation.NOT_FELT))
        val choice = drain<EngineEvent.UserChoiceNeeded>().single()
        assertEquals("A", choice.issueId); assertFalse(choice.repairCheck, "수리 전이면 축소·중지를 묻는다")
    } }

    @Test fun noAlertWhileStoppedWithBrakeHeld() = runTest { withCollector {
        alertAfterDrive()   // 프로파일 준비 + 알림 1건(쿨다운 600초 시작)
        events.clear()
        // 1000초 뒤 신호등: 이미 5초 서 있는 차(주행거리 고정)가 브레이크를 10초 밟는다. 멈추는 중의 감속 구간은 여기서 다루지 않는다.
        var t = 1000.0
        while (t <= 1015.0) { engine.onSignal(Fx.steer(t, 0.0)); engine.onSignal(Fx.brake(t, t >= 1005.0)); engine.onSignal(Fx.odo(t, 50000.0)); engine.onSignal(Fx.navi(t, NaviGuidance.STRAIGHT)); t += 0.5 }
        advanceUntilIdle()
        assertTrue(engine.state.value!!.stopped)
        assertTrue(drain<EngineEvent.Alert>().isEmpty(), "정차 중 브레이크 유지는 조건이 아니다")
    } }

    @Test fun startTwiceReturnsTheRunningJob() = runTest {
        val signals = ai.chagok.core.pipeline.SignalBus(); val commands = ai.chagok.core.pipeline.CommandBus()
        val j1 = engine.start(this, signals, commands)
        val j2 = engine.start(this, signals, commands)
        assertTrue(j1 === j2, "두 번 start해도 소비자는 하나")
        j1.cancel()
    }

    // ── Briefing ──

    @Test fun briefingPutsUnreadFirstAndSkipsUninvolvedOrResolverForResolutions() {
        repo.saveIssue(issue("A", "minji")); repo.saveIssue(issue("B", "minji").copy(readBy = setOf("junho")))
        val b = BriefingBuilder.build(repo, "junho")
        assertEquals(listOf("A", "B"), b.unresolved.map { it.issue.id }, "안 읽은 것이 먼저")
        assertEquals(listOf(true, false), b.unresolved.map { it.unread })
        assertNull(b.unresolved[0].lastRecord); assertEquals(0, b.unresolved[0].recordCount)

        repo.saveRecord(record("r1", "A", "sora"))
        repo.saveIssue(repo.issue("A")!!.copy(status = IssueStatus.RESOLVED, resolution = ai.chagok.core.domain.Resolution("junho", "done", 5000)))
        assertEquals("A", BriefingBuilder.build(repo, "minji").resolved.single().issue.id, "만든 사람에게 전달")
        assertEquals("준호", BriefingBuilder.build(repo, "sora").resolved.single().resolvedByName, "기록을 붙인 사람에게도 전달")
        assertTrue(BriefingBuilder.build(repo, "junho").resolved.isEmpty(), "해결한 사람 본인에게는 안 함")
        repo.saveMember(Member("dad", "아빠"))
        assertTrue(BriefingBuilder.build(repo, "dad").resolved.isEmpty(), "관여하지 않은 구성원에게는 안 함")
        val dadLine = BriefingBuilder.build(repo, "dad").unresolved.single()
        assertEquals("B", dadLine.issue.id, "해결된 A는 미해결에 없다"); assertNull(dadLine.lastAuthorName, "기록이 없는 문제는 작성자도 없다")
    }

    // ── Report ──

    @Test fun reportProvenanceAndRendering() = runTest { withCollector {
        assertNull(ReportBuilder.build(repo, "nope"))
        repo.saveIssue(issue("A", "minji")); repo.saveIssue(issue("B", "minji"))
        repo.saveRecord(record("r1", "A", "minji", desc = "끼익")); repo.saveRecord(record("r2", "B", "junho"))
        send(Command.LinkRecord("r1", "A"))
        send(Command.LogMaintenance("A", "패드 교체", "junho")); send(Command.LogMaintenance("B", "다른 문제 노트", "junho"))
        send(Command.LogMaintenance("A", "주행거리 32,410 km", null, fromVehicle = true))
        val r = ReportBuilder.build(repo, "A")!!
        assertEquals(1, r.observations.size); assertEquals(32.41, r.observations[0].odometerKm!!, 1e-9)
        assertEquals(EntrySource.USER_INPUT, r.observations[0].sourceOf("description")); assertEquals(EntrySource.VEHICLE, r.observations[0].sourceOf("odometerKm"))
        assertEquals(listOf("패드 교체", "주행거리 32,410 km"), r.maintenance.map { it.text }, "다른 문제의 노트는 섞이지 않는다")
        val text = ReportBuilder.render(r)
        assertTrue(text.contains("[사용자 입력] 패드 교체") && text.contains("[차량 수신] 주행거리"), text)
        assertTrue(text.contains("민지: 브레이크 2초, 조향 -3°, 내비 STRAIGHT, 주행거리 32.4km · \"끼익\""), text)
        assertEquals("조향 -3°", r.steeringSummary, "기록 하나면 단일 값")
    } }
}
