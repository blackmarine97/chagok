package ai.chagok.core

import ai.chagok.core.alert.AlertGate
import ai.chagok.core.alert.AlertPolicy
import ai.chagok.core.domain.InMemoryRepository
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Tolerance
import ai.chagok.core.model.AlertAdjustment
import ai.chagok.core.model.Command
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Observation
import ai.chagok.core.pipeline.CommandBus
import ai.chagok.core.pipeline.SignalBus
import ai.chagok.core.sim.SimulationProducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 기획서 시연 시나리오를 엔진에 그대로 흘린다. 엔진 호출은 동기이고, events 수집만 코루틴. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineScenarioTest {
    private val events = ArrayList<EngineEvent>()
    private var trip = "tue"
    private val repo = InMemoryRepository()
    private val engine = Engine(
        repo = repo,
        gate = AlertGate(AlertPolicy(dailyCap = 5, cooldownNs = Ns.sec(60.0)), today = { LocalDate.of(2026, 11, 6) }),
        tolerance = Tolerance(brakeHoldSec = 0.5, steeringDeg = 6.0),   // 시연 조향 -3°·+2°를 한 범위로 묶기 위한 값. P0에서 조정
        tripId = { trip },
    )

    private fun run(evs: List<ai.chagok.core.model.SignalEvent>) = evs.forEach { engine.onSignal(it) }
    private fun <T : EngineEvent> drain(cls: Class<T>): List<T> = events.filterIsInstance(cls)

    @Test fun fullStory() = runTest {
        val collector = launch { engine.events.collect { events.add(it) } }
        advanceUntilIdle()   // 수집기가 구독한 뒤에 이벤트를 흘린다 (replay=0)

        // 화요일 · 민지: 직진 안내 중 브레이크 2.4초, 조향 -3. 발화는 브레이크 중 2.0초 시점.
        trip = "tue"
        run(Fx.drive(0.0, 32.0, brakeAt = 30.0, brakeFor = 2.4, steer = -3.0, g = NaviGuidance.STRAIGHT))
        engine.onCommand(Command.Capture(Ns.sec(32.0), authorId = "minji"))
        run(Fx.drive(32.5, 30.0, brakeAt = 30.0, brakeFor = 2.4, steer = -3.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val rec1 = drain(EngineEvent.RecordCaptured::class.java).single().record
        assertNotNull(rec1.features.brakeHoldSec); assertEquals(-3.0, rec1.features.steeringDeg)
        assertEquals(NaviGuidance.STRAIGHT, rec1.features.navi)

        // 정차 후 새 문제로 등록 → 후보(1건), 알림 꺼짐
        engine.onCommand(Command.LinkRecord(rec1.id, null, "감속할 때 앞쪽 끼익 소리"))
        advanceUntilIdle()
        val issueId = repo.issues().single().id
        assertEquals(ProfileState.CANDIDATE, repo.profile(issueId)!!.state)

        // 수요일 · 준호: 같은 조건을 지나도 알림 없음(후보 상태) → "다음에 알려줘" 켠 뒤에는 알림
        trip = "wed"; events.clear()
        run(Fx.drive(1000.0, 10.0, brakeAt = 1005.0, brakeFor = 2.1, steer = 2.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        assertTrue(drain(EngineEvent.Alert::class.java).isEmpty(), "1건 후보 상태에서는 자동 알림 없음")
        engine.onCommand(Command.ArmAlert(issueId, true))
        run(Fx.drive(1100.0, 7.0, brakeAt = 1105.0, brakeFor = 2.1, steer = 2.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val alert1 = drain(EngineEvent.Alert::class.java).first()
        assertEquals(issueId, alert1.issueId)

        // 준호도 기록(브레이크 중 1.5초 시점) → 정차 후 같은 문제에 연결 → 다른 주행 2건 → 잠정 패턴
        engine.onCommand(Command.Capture(Ns.sec(1106.5), authorId = "junho"))
        run(Fx.drive(1107.0, 20.0, brakeAt = 1105.0, brakeFor = 2.1, steer = 2.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val rec2 = drain(EngineEvent.RecordCaptured::class.java).single().record
        engine.onCommand(Command.LinkRecord(rec2.id, issueId))
        advanceUntilIdle()
        val p2 = repo.profile(issueId)!!
        assertEquals(ProfileState.PROVISIONAL, p2.state); assertEquals(2, p2.distinctTrips)
        assertTrue(p2.steering!!.contains(-3.0) && p2.steering.contains(2.0))

        // 금요일: 자동 알림(켜지 않아도) → 회전 안내 중엔 알림 없음
        trip = "fri"; events.clear()
        engine.onCommand(Command.ArmAlert(issueId, false))
        run(Fx.drive(2000.0, 10.0, brakeAt = 2005.0, brakeFor = 2.5, steer = 8.0, g = NaviGuidance.TURN_AHEAD))
        advanceUntilIdle()
        assertTrue(drain(EngineEvent.Alert::class.java).isEmpty(), "안내 상황 유형이 다르면 알림 없음")
        run(Fx.drive(2100.0, 10.0, brakeAt = 2105.0, brakeFor = 2.5, steer = 8.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        val alert2 = drain(EngineEvent.Alert::class.java).first()

        // 수리 후: 알림마다 정차 후 답 → 3회 연속 못 느낌(미확인은 연속을 깨지 않음) → 사용자 선택 요청 → REDUCE
        // 답은 열린 알림 id로만 할 수 있으므로 알림을 실제로 받아서 답한다.
        engine.markRepairCheck(issueId, true)
        engine.onCommand(Command.Answer(issueId, alert2.alertId, Observation.NOT_FELT, "junho"))
        fun nextAlert(t0: Double): EngineEvent.Alert {
            events.clear()
            run(Fx.drive(t0, 10.0, brakeAt = t0 + 5.0, brakeFor = 2.5, steer = 8.0, g = NaviGuidance.STRAIGHT))
            advanceUntilIdle()
            return drain(EngineEvent.Alert::class.java).single()
        }
        val a3 = nextAlert(2200.0)
        assertTrue(a3.repairCheck, "수리 후 확인 알림"); assertEquals(2, a3.recordCount)
        engine.onCommand(Command.Answer(issueId, a3.alertId, Observation.UNKNOWN, "junho"))     // 미확인은 연속 카운트를 깨지 않음
        engine.onCommand(Command.Answer(issueId, nextAlert(2300.0).alertId, Observation.NOT_FELT, "junho"))
        engine.onCommand(Command.Answer(issueId, nextAlert(2400.0).alertId, Observation.NOT_FELT, "junho"))
        advanceUntilIdle()
        val choice = drain(EngineEvent.UserChoiceNeeded::class.java).single()
        assertTrue(choice.repairCheck, "수리 후 확인 중이면 '해결로 기록할까요'로 묻는다")
        val c = repo.profile(issueId)!!.counts
        assertEquals(3, c.notFelt); assertEquals(1, c.unknown); assertEquals(0, c.felt)
        events.clear()
        run(Fx.drive(2500.0, 10.0, brakeAt = 2505.0, brakeFor = 2.5, steer = 8.0, g = NaviGuidance.STRAIGHT))
        advanceUntilIdle()
        assertTrue(drain(EngineEvent.Alert::class.java).isEmpty(), "선택 전에는 알리지 않음")
        engine.onCommand(Command.AdjustAlert(issueId, AlertAdjustment.REDUCE))
        assertEquals(ProfileState.REDUCED, repo.profile(issueId)!!.state)

        collector.cancel()
    }

    @Test fun simulationProducerReplaysThroughBus() = runTest {
        val bus = SignalBus(); val cmds = CommandBus()
        val repo = InMemoryRepository()
        val engine = Engine(repo, tripId = { "t" })
        val seen = ArrayList<EngineEvent>()
        val col = launch { engine.events.collect { seen.add(it) } }
        val job = engine.start(this, bus, cmds)
        advanceUntilIdle()   // 엔진이 버스를 구독한 뒤에 리플레이 시작
        val sim = SimulationProducer.fromText("""
            0,navi,STRAIGHT
            0,steer,-2.0
            0,brake,0
            500,steer,-2.0
            500,brake,1
            1000,steer,-2.0
            1000,brake,1
            2000,steer,-2.0
            2000,brake,0
        """.trimIndent())
        sim.start(this, bus)
        advanceUntilIdle()
        assertEquals(Ns.sec(2.0), engine.state.value!!.tsMonoNs, "마지막 이벤트까지 버스를 지나 엔진에 닿았다")
        assertEquals(1.5, engine.state.value!!.lastCompletedHoldSec!!, 1e-6)   // 500ms ON → 2000ms OFF
        job.cancel(); col.cancel()
    }
}
