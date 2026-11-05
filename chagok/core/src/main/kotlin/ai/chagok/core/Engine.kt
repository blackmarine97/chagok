package ai.chagok.core

import ai.chagok.core.alert.AlertCandidate
import ai.chagok.core.alert.AlertGate
import ai.chagok.core.alert.Decision
import ai.chagok.core.buffer.Capture
import ai.chagok.core.buffer.CaptureService
import ai.chagok.core.derive.DerivedState
import ai.chagok.core.derive.StateDeriver
import ai.chagok.core.domain.*
import ai.chagok.core.handover.Briefing
import ai.chagok.core.handover.BriefingBuilder
import ai.chagok.core.match.ConditionMatcher
import ai.chagok.core.match.MatchResult
import ai.chagok.core.model.AlertAdjustment
import ai.chagok.core.model.Command
import ai.chagok.core.model.Observation
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.pipeline.CommandBus
import ai.chagok.core.pipeline.SignalBus
import ai.chagok.core.pipeline.SignalProducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI·TTS로 나가는 것들. 엔진은 이것만 내고, 화면·음성 출력은 바깥에서 한다.
 * 모두 "한 번 일어난 일"이라 손실되면 안 된다. 연속 상태(DerivedState)는 여기 없고 [Engine.state]로 따로 나간다.
 */
sealed class EngineEvent {
    data class Alert(val alertId: String, val issueId: String, val repairCheck: Boolean, val tsMonoNs: Long) : EngineEvent()
    data class RecordCaptured(val record: SymptomRecord) : EngineEvent()
    data class RecordFailed(val reason: String) : EngineEvent()
    data class ProfileChanged(val profile: ConditionProfile) : EngineEvent()
    data class UserChoiceNeeded(val issueId: String) : EngineEvent()
    /** Issue 상태·읽음·해결이 바뀌었다. 목록 화면 갱신용. */
    data class IssueChanged(val issue: Issue) : EngineEvent()
    /** 탑승 브리핑. 정차 중 화면·음성으로 읽어 준다. */
    data class BriefingReady(val briefing: Briefing) : EngineEvent()
}

/**
 * 파이프라인 배선. 신호·명령·Producer 상태를 한 코루틴에서 순서대로 처리하므로 내부 상태에 락이 없다.
 * Producers → SignalBus → [StateDeriver, CaptureService] → ConditionMatcher → AlertGate → EngineEvent.
 *
 * 출력은 세 갈래다. 신호와 명령의 백프레셔를 나눈 것과 같은 이유다.
 *  - [events]: Alert·RecordCaptured 같은 이산 이벤트. 손실 금지(Channel.UNLIMITED). 구독자가 아직 없어도 쌓아 둔다.
 *    소비자는 하나(앱의 이벤트 디스패처)여야 하고, UI·TTS로의 fan-out은 그쪽에서 한다.
 *  - [state]: 신호마다 바뀌는 DerivedState. 최신 값만 의미가 있으므로 StateFlow.
 *  - [sourceStatus]: 소스별 AVAILABLE / STALE / PERMISSION_DENIED / UNSUPPORTED / NOT_STARTED. 설정 화면·누락 표시용.
 *
 * onSignal/onCommand/onProducerStatus는 테스트가 직접 부를 수 있게 공개했지만, start()로 돌아가는 중에는 바깥에서 부르면 안 된다(락 없음).
 */
class Engine(
    private val repo: Repository,
    private val deriver: StateDeriver = StateDeriver(),
    private val captures: CaptureService = CaptureService(),
    private val matcher: ConditionMatcher = ConditionMatcher(),
    private val gate: AlertGate = AlertGate(ledger = repo),
    private val tolerance: Tolerance = Tolerance(),
    private val tripId: () -> String = { "trip" },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val _events = Channel<EngineEvent>(Channel.UNLIMITED)
    val events: Flow<EngineEvent> = _events.receiveAsFlow()
    private val _state = MutableStateFlow<DerivedState?>(null)
    val state: StateFlow<DerivedState?> = _state.asStateFlow()
    private val _sourceStatus = MutableStateFlow(Source.entries.associateWith { SourceStatus.NOT_STARTED })
    val sourceStatus: StateFlow<Map<Source, SourceStatus>> = _sourceStatus.asStateFlow()

    private val pendingAuthors = HashMap<Long, String>()      // triggerTs → authorId
    private val openAlerts = HashMap<String, String>()         // alertId → issueId (답을 아직 못 받은 알림)
    private val producerStatus = HashMap<String, Map<Source, SourceStatus>>()   // producer name → 소스별 상태

    private sealed class In {
        data class Sig(val e: SignalEvent) : In()
        data class Cmd(val c: Command) : In()
        data class Status(val producer: String, val s: Map<Source, SourceStatus>) : In()
    }

    /** producers를 넘기면 그 상태(권한 없음·미지원)도 같은 코루틴에서 받아 판정 불가로 반영한다. */
    fun start(scope: CoroutineScope, signals: SignalBus, commands: CommandBus, producers: List<SignalProducer> = emptyList()): Job = scope.launch {
        val statusFlows = producers.map { p -> p.status.map { In.Status(p.name, it) as In } }
        merge(signals.events.map { In.Sig(it) as In }, commands.commands.map { In.Cmd(it) as In }, *statusFlows.toTypedArray()).collect { msg ->
            when (msg) {
                is In.Sig -> onSignal(msg.e)
                is In.Cmd -> onCommand(msg.c)
                is In.Status -> onProducerStatus(msg.producer, msg.s)
            }
        }
    }

    private fun emit(e: EngineEvent) { _events.trySend(e) }   // UNLIMITED라 항상 성공

    fun onSignal(e: SignalEvent) {
        val s = deriver.onEvent(e)
        _state.value = s
        publishSourceStatus(s)
        captures.onEvent(e).forEach { finishCapture(it) }
        evaluate(s)
    }

    fun onCommand(c: Command) {
        when (c) {
            is Command.Capture -> { pendingAuthors[c.tsMonoNs] = c.authorId; captures.capture(c.tsMonoNs) }
            is Command.CaptureAt -> {
                val cap = captures.captureAt(c.tsMonoNs)
                if (cap == null) emit(EngineEvent.RecordFailed("temp buffer no longer covers that time"))
                else saveRecord(cap, c.authorId)
            }
            is Command.LinkRecord -> linkRecord(c)
            is Command.Describe -> repo.record(c.recordId)?.let { r -> val nr = r.copy(description = c.text); repo.saveRecord(nr); emit(EngineEvent.RecordCaptured(nr)) }
            is Command.ArmAlert -> repo.profile(c.issueId)?.let { p -> val np = p.copy(armedByUser = c.on); repo.saveProfile(np); emit(EngineEvent.ProfileChanged(np)) }
            is Command.Answer -> answer(c)
            is Command.AdjustAlert -> adjust(c)
            is Command.Board -> board(c.memberId)
            is Command.AcknowledgeIssue -> acknowledge(c.issueId, c.memberId)
            is Command.StartRepairCheck -> startRepairCheck(c)
            is Command.LogMaintenance -> logMaintenance(c.issueId, c.text, c.authorId, c.fromVehicle)
            is Command.ResolveIssue -> resolve(c)
            is Command.ReopenIssue -> reopen(c.issueId)
            is Command.EndDrive -> endDrive(c.tsMonoNs)
            is Command.DiscardTemp -> { captures.discardTemp(); pendingAuthors.clear() }
        }
    }

    // ── Producer 상태 ──

    /**
     * Producer 상태. 소스별로 어느 Producer든 AVAILABLE이면 사용 가능, 아니면 가장 나쁜 상태를 취한다.
     * PERMISSION_DENIED·UNSUPPORTED는 deriver로 들어가 그 소스를 판정 불가로 만든다.
     */
    fun onProducerStatus(producer: String, s: Map<Source, SourceStatus>) {
        producerStatus[producer] = s
        val combined = Source.entries.associateWith { src ->
            val all = producerStatus.values.mapNotNull { it[src] }
            when {
                all.isEmpty() -> SourceStatus.NOT_STARTED
                SourceStatus.AVAILABLE in all -> SourceStatus.AVAILABLE
                else -> all.maxBy { statusRank(it) }
            }
        }
        deriver.sourceStatus = combined
        _sourceStatus.value = _state.value?.let { overlayStale(combined, it) } ?: combined
    }

    private fun statusRank(s: SourceStatus) = when (s) {
        SourceStatus.AVAILABLE -> 0; SourceStatus.NOT_STARTED -> 1; SourceStatus.STALE -> 2
        SourceStatus.UNSUPPORTED -> 3; SourceStatus.PERMISSION_DENIED -> 4
    }

    private fun overlayStale(base: Map<Source, SourceStatus>, s: DerivedState) =
        base.mapValues { (src, st) -> if (st == SourceStatus.AVAILABLE && src in s.stale) SourceStatus.STALE else st }

    private fun publishSourceStatus(s: DerivedState) {
        val base = if (producerStatus.isEmpty()) Source.entries.associateWith { SourceStatus.AVAILABLE } else deriver.sourceStatus
        _sourceStatus.value = overlayStale(base, s)
    }

    // ── 순간 기록 ──

    private fun finishCapture(cap: Capture) {
        val author = pendingAuthors.remove(cap.triggerTsNs) ?: "unknown"
        saveRecord(cap, author)
    }

    private fun saveRecord(cap: Capture, author: String) {
        val r = SymptomRecord(
            id = newId(), issueId = null, authorId = author, tripId = tripId(), capture = cap,
            features = FeatureExtractor.extract(cap), recordedAtEpochMs = wallClockMs(),
        )
        repo.saveRecord(r)
        emit(EngineEvent.RecordCaptured(r))
    }

    private fun linkRecord(c: Command.LinkRecord) {
        val r = repo.record(c.recordId) ?: return
        val issue = c.issueId?.let { repo.issue(it) } ?: run {
            val now = wallClockMs()
            Issue(c.issueId ?: newId(), c.newIssueTitle ?: "증상", IssueStatus.NEW, r.authorId, createdAtEpochMs = now)
                .also { repo.saveIssue(it); emit(EngineEvent.IssueChanged(it)) }
        }
        repo.saveRecord(r.copy(issueId = issue.id))
        // 해결된 문제에 새 기록이 붙으면 재발
        if (issue.status == IssueStatus.RESOLVED) reopen(issue.id) else rebuildProfile(issue.id)
    }

    private fun rebuildProfile(issueId: String, prev: ConditionProfile? = repo.profile(issueId)) {
        val p = ProfileBuilder.build(issueId, repo.records(), tolerance, prev)
        repo.saveProfile(p)
        emit(EngineEvent.ProfileChanged(p))
    }

    // ── 재현 알림 ──

    /**
     * 기획서 "다른 문제가 겹치면 먼저 기록된 것만": Issue 생성 순으로 보다가 조건에 맞는 첫 문제에서 멈춘다.
     * 그 문제가 쿨다운·상한으로 거절돼도 뒤의 문제로 넘어가지 않는다(같은 주행 조건에 두 문제가 번갈아 울리는 것을 막는다).
     */
    private fun evaluate(s: DerivedState) {
        for (issue in repo.issues()) {
            val p = repo.profile(issue.id) ?: continue
            if (!p.alertsEnabled) continue
            val m = matcher.match(s, p)
            if (m !is MatchResult.Match) continue
            val cand = AlertCandidate(p.issueId, s.tsMonoNs, m.naviChecked, repairCheck = issue.status == IssueStatus.IN_REPAIR_CHECK)
            when (val d = gate.decide(cand, p)) {
                is Decision.Allow -> { openAlerts[d.alertId] = p.issueId; emit(EngineEvent.Alert(d.alertId, p.issueId, cand.repairCheck, s.tsMonoNs)) }
                is Decision.Deny -> Unit
            }
            return
        }
    }

    private fun answer(c: Command.Answer) {
        openAlerts.remove(c.alertId)
        applyObservation(c.issueId, c.answer)
    }

    private fun applyObservation(issueId: String, o: Observation) {
        val p = repo.profile(issueId) ?: return
        val np = p.copy(counts = p.counts.apply(o))
        repo.saveProfile(np)
        emit(EngineEvent.ProfileChanged(np))
        if (np.needsUserChoice) emit(EngineEvent.UserChoiceNeeded(np.issueId))
    }

    private fun adjust(c: Command.AdjustAlert) {
        val p = repo.profile(c.issueId) ?: return
        val np = when (c.choice) {
            AlertAdjustment.KEEP -> p.copy(counts = p.counts.copy(consecutiveNotFelt = 0))
            AlertAdjustment.REDUCE -> p.copy(state = ProfileState.REDUCED, counts = p.counts.copy(consecutiveNotFelt = 0))
            AlertAdjustment.STOP -> p.copy(state = ProfileState.STOPPED)
        }
        repo.saveProfile(np); emit(EngineEvent.ProfileChanged(np))
    }

    // ── 인수인계·해결 ──

    private fun saveIssue(i: Issue) { repo.saveIssue(i.copy(updatedAtEpochMs = wallClockMs())); emit(EngineEvent.IssueChanged(repo.issue(i.id)!!)) }

    /** 탑승: 브리핑을 내고, 거기 실린 해결은 이 구성원에게 전달 완료로, 미해결 목록은 읽음으로 표시한다. */
    private fun board(memberId: String) {
        val b = BriefingBuilder.build(repo, memberId)
        emit(EngineEvent.BriefingReady(b))
        for (line in b.resolved) {
            val i = repo.issue(line.issue.id) ?: continue
            val r = i.resolution ?: continue
            saveIssue(i.copy(resolution = r.copy(deliveredTo = r.deliveredTo + memberId)))
        }
        for (line in b.unresolved + b.inRepairCheck) acknowledge(line.issue.id, memberId)
    }

    /** 읽음. 기록자 본인이 아닌 구성원이 처음 확인하면 NEW → ACKNOWLEDGED. */
    private fun acknowledge(issueId: String, memberId: String) {
        val i = repo.issue(issueId) ?: return
        if (i.isReadBy(memberId)) return
        val status = if (i.status == IssueStatus.NEW && memberId != i.createdBy) IssueStatus.ACKNOWLEDGED else i.status
        saveIssue(i.copy(readBy = i.readBy + memberId, status = status))
    }

    private fun startRepairCheck(c: Command.StartRepairCheck) {
        val i = repo.issue(c.issueId) ?: return
        if (!i.isOpen) return
        logMaintenance(c.issueId, c.note, c.memberId, fromVehicle = false)
        saveIssue(i.copy(status = IssueStatus.IN_REPAIR_CHECK, readBy = i.readBy + c.memberId))
    }

    private fun logMaintenance(issueId: String?, text: String, authorId: String?, fromVehicle: Boolean) {
        repo.saveMaintenance(MaintenanceEntry(
            id = newId(), issueId = issueId, source = if (fromVehicle) EntrySource.VEHICLE else EntrySource.USER_INPUT,
            text = text, authorId = if (fromVehicle) null else authorId, atEpochMs = wallClockMs(),
        ))
    }

    /** 해결: Issue RESOLVED, 알림 중지(프로파일 STOPPED), 전달 대상은 Board 때 BriefingBuilder가 정한다. */
    private fun resolve(c: Command.ResolveIssue) {
        val i = repo.issue(c.issueId) ?: return
        if (!i.isOpen) return
        saveIssue(i.copy(status = IssueStatus.RESOLVED, resolution = Resolution(c.memberId, c.note, wallClockMs()), readBy = i.readBy + c.memberId))
        repo.profile(c.issueId)?.let { p -> val np = p.copy(state = ProfileState.STOPPED); repo.saveProfile(np); emit(EngineEvent.ProfileChanged(np)) }
        openAlerts.entries.removeIf { it.value == c.issueId }
    }

    /** 재발: 다시 열고(REOPENED), 프로파일은 기록 수로 상태를 다시 계산한다. 카운트 이력은 남기고 연속 못 느낌만 초기화. */
    private fun reopen(issueId: String) {
        val i = repo.issue(issueId) ?: return
        if (i.status != IssueStatus.RESOLVED) return
        saveIssue(i.copy(status = IssueStatus.REOPENED, resolution = null))
        val prev = repo.profile(issueId)?.let { it.copy(state = ProfileState.NONE, counts = it.counts.copy(consecutiveNotFelt = 0)) }
        rebuildProfile(issueId, prev)
    }

    // ── 수명주기 ──

    /** 주행 종료: 미완성 Capture는 지금까지로 저장, 답 없는 알림은 미확인(기획서: 답이 없으면 미확인으로 두고 어느 쪽으로도 세지 않음). */
    private fun endDrive(nowTs: Long) {
        captures.flush(nowTs).forEach { finishCapture(it) }
        val unanswered = openAlerts.values.toList(); openAlerts.clear()
        unanswered.forEach { applyObservation(it, Observation.UNKNOWN) }
    }

    /** 수리 후 확인 모드는 Issue 상태(IN_REPAIR_CHECK)가 결정한다. 호환용. */
    fun markRepairCheck(issueId: String, on: Boolean) {
        val i = repo.issue(issueId) ?: return
        saveIssue(i.copy(status = if (on) IssueStatus.IN_REPAIR_CHECK else IssueStatus.ACKNOWLEDGED))
    }
}
