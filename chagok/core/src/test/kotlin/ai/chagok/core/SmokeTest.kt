package ai.chagok.core

import ai.chagok.core.domain.InMemoryRepository
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.model.Command
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.pipeline.CommandBus
import ai.chagok.core.pipeline.SignalBus
import ai.chagok.core.sim.SimulationProducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 스모크 테스트: 실제 배선으로 끝까지 한 번 돈다.
 * fixture 파일(P0 실측 로그와 같은 형식) → SimulationProducer → SignalBus → Engine.start → CommandBus 명령 → events.
 * 엔진 메서드를 직접 부르지 않는다. 여기서 깨지면 단위 테스트가 모두 통과해도 조립이 틀린 것이다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmokeTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "fixture $name missing" }.readText()

    @Test fun replayThroughBusesEndToEnd() = runTest {
        val repo = InMemoryRepository()
        val engine = Engine(repo)                      // 기본 정책·기본 오차·기본 clock. 시연용 조정값 없음
        val signals = SignalBus(); val commands = CommandBus()
        val seen = ArrayList<EngineEvent>()
        val collector = launch { engine.events.collect { seen.add(it) } }

        val part1 = SimulationProducer.fromText(fixture("smoke_part1.csv"))
        val job = engine.start(this, signals, commands, producers = listOf(part1))
        advanceUntilIdle()
        assertEquals(SourceStatus.NOT_STARTED, engine.sourceStatus.value[Source.BRAKE], "시작 전 Producer 상태가 엔진까지 왔다")

        // 1. 40초 주행 리플레이. 브레이크 30.0~32.4초.
        part1.start(this, signals); advanceUntilIdle()
        val s1 = assertNotNull(engine.state.value)
        assertEquals(Ns.sec(40.0), s1.tsMonoNs, "마지막 이벤트까지 버스를 지나왔다")
        assertEquals(2.5, assertNotNull(s1.lastCompletedHoldSec), 1e-6)   // 0.5초 간격 fixture: ON 30.0, 첫 OFF 32.5
        assertEquals(NaviGuidance.STRAIGHT, s1.navi)
        assertTrue(engine.sourceStatus.value.values.all { it == SourceStatus.AVAILABLE }, engine.sourceStatus.value.toString())

        // 2. 명령 버스로 "기록해"(발화 32.0초, 브레이크 중). 뒤 10초는 다음 리플레이가 채운다.
        commands.send(Command.Capture(Ns.sec(32.0), "minji")); advanceUntilIdle()
        assertTrue(seen.filterIsInstance<EngineEvent.RecordCaptured>().isEmpty(), "post 구간 전에는 기록이 완성되지 않는다")
        SimulationProducer.fromText(fixture("smoke_part2.csv"), baseTsNs = Ns.sec(40.5)).start(this, signals); advanceUntilIdle()
        val rec = seen.filterIsInstance<EngineEvent.RecordCaptured>().single().record
        assertTrue(rec.capture.complete); assertEquals("minji", rec.authorId)
        assertEquals(2.5, assertNotNull(rec.features.brakeHoldSec), 1e-6)   // 발화(32.0) 뒤 32.5 OFF까지 기다린 완료값
        assertTrue(rec.features.brakeHoldCompleted)
        assertEquals(-3.0, rec.features.steeringDeg); assertEquals(NaviGuidance.STRAIGHT, rec.features.navi)
        assertEquals(Ns.sec(12.0), rec.capture.events.first().tsMonoNs); assertTrue(rec.capture.events.last().tsMonoNs <= Ns.sec(42.0))

        // 3. 정차 후 새 문제로 등록 → 후보 → "다음에 알려줘"
        commands.send(Command.LinkRecord(rec.id, null, "감속할 때 끼익")); advanceUntilIdle()
        val issueId = repo.issues().single().id
        assertEquals(ProfileState.CANDIDATE, repo.profile(issueId)!!.state)
        commands.send(Command.ArmAlert(issueId, true)); advanceUntilIdle()

        // 4. 다른 시각(1000초)에 같은 조건(조향 -2, 브레이크 2.1초) → 알림 1건
        SimulationProducer.fromText(fixture("smoke_part3.csv"), baseTsNs = Ns.sec(1000.0)).start(this, signals); advanceUntilIdle()
        val alerts = seen.filterIsInstance<EngineEvent.Alert>()
        assertEquals(1, alerts.size, "쿨다운 안에서는 한 번만")
        assertEquals(issueId, alerts.single().issueId)
        assertTrue(alerts.single().tsMonoNs in Ns.sec(1006.0)..Ns.sec(1007.1), "유지 1.5초를 넘기는 순간(진입 직전)에 알린다: ${alerts.single().tsMonoNs}")
        assertTrue(seen.filterIsInstance<EngineEvent.RecordFailed>().isEmpty())

        // 5. 주행 종료: 답 없는 알림은 미확인
        commands.send(Command.EndDrive(Ns.sec(1011.0))); advanceUntilIdle()
        assertEquals(1, repo.profile(issueId)!!.counts.unknown)
        assertTrue(engine.sourceStatus.value.values.all { it == SourceStatus.AVAILABLE })

        job.cancel(); collector.cancel()
    }
}
