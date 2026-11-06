package ai.chagok.core

import ai.chagok.core.model.Command
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.SignalValue
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.pipeline.CommandBus
import ai.chagok.core.pipeline.SignalBus
import ai.chagok.core.sim.SimulationProducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BusAndSimTest {
    @Test fun signalBusAssignsSequenceNumbers() = runTest {
        val bus = SignalBus()
        val got = ArrayList<SignalEvent>()
        val col = launch { bus.events.collect { got.add(it) } }
        advanceUntilIdle()
        repeat(3) { bus.publish(Fx.steer(it.toDouble(), 0.0)) }
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), got.map { it.seq })
        col.cancel()
    }

    @Test fun signalBusDropsOldestWhenConsumerLags() = runTest {
        val bus = SignalBus(bufferCapacity = 2)
        val got = ArrayList<SignalEvent>()
        val col = launch { bus.events.collect { got.add(it) } }
        advanceUntilIdle()
        repeat(5) { assertTrue(bus.publish(Fx.steer(it.toDouble(), 0.0)), "tryEmit은 DROP_OLDEST라 항상 성공") }
        advanceUntilIdle()
        assertEquals(listOf(4L, 5L), got.map { it.seq }, "밀리면 오래된 신호가 버려진다")
        col.cancel()
    }

    @Test fun signalBusWithoutSubscriberDropsSilently() {
        val bus = SignalBus()
        assertTrue(bus.publish(Fx.brake(0.0, true)))   // 구독자 없음: 버리고 true
    }

    @Test fun commandBusKeepsEveryCommandInOrderEvenBeforeAnyoneListens() = runTest {
        val bus = CommandBus()
        bus.send(Command.ArmAlert("i1", true)); bus.send(Command.DiscardTemp); bus.send(Command.ArmAlert("i1", false))
        val got = bus.commands.take(3).toList()
        assertEquals(listOf(Command.ArmAlert("i1", true), Command.DiscardTemp, Command.ArmAlert("i1", false)), got)
    }

    @Test fun simulationParserSkipsCommentsBlanksAndUnknownSources() {
        val sim = SimulationProducer.fromText(
            """
            # comment

            0,brake,1
            250,brake,true
            500,steer,-3.5
            750,odo,32410.0,1234
            1000,odo,32420.0
            1250,navi,NO_GUIDANCE
            1500,speed,42
            1600,brake
            abc,steer,1.0
            1700,navi,NOT_A_TYPE
            1800,steer,xx
            """.trimIndent(), baseTsNs = Ns.sec(100.0),
        )
        val e = sim.events
        assertEquals(6, e.size, "주석·빈 줄·모르는 소스·깨진 줄은 건너뛴다")
        assertEquals(Ns.sec(100.0), e[0].tsMonoNs, "baseTsNs가 더해진다")
        assertEquals(SignalValue.BrakePedal(true), e[0].value); assertEquals(SignalValue.BrakePedal(true), e[1].value)
        assertEquals(SignalValue.SteeringAngle(-3.5), e[2].value)
        assertEquals(SignalValue.Odometer(32410.0, 1234L), e[3].value)
        assertEquals(SignalValue.Odometer(32420.0, 0L), e[4].value, "트립 시간이 없으면 0")
        assertEquals(SignalValue.Navi(NaviGuidance.NO_GUIDANCE), e[5].value)
        assertEquals(Source.NAVI, e[5].source)
        assertEquals(Ns.sec(100.0) + 1250 * Ns.MS, e[5].tsMonoNs)
    }

    @Test fun simulationRealtimeHonoursOffsets() = runTest {
        val bus = SignalBus()
        val got = ArrayList<SignalEvent>()
        val col = launch { bus.events.collect { got.add(it) } }
        advanceUntilIdle()
        val sim = SimulationProducer.fromText("0,brake,1\n1500,brake,0", realtime = true)
        assertEquals(SourceStatus.NOT_STARTED, sim.status.value[Source.BRAKE])
        sim.start(this, bus)
        assertEquals(SourceStatus.AVAILABLE, sim.status.value[Source.BRAKE], "start 직후 사용 가능으로 보고")
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, got.size, "1.5초 뒤 이벤트는 아직")
        advanceUntilIdle()
        assertEquals(2, got.size)
        sim.stop(); col.cancel()
    }

    @Test fun simulationStopCancelsReplay() = runTest {
        val bus = SignalBus()
        val got = ArrayList<SignalEvent>()
        val col = launch { bus.events.collect { got.add(it) } }
        advanceUntilIdle()
        val sim = SimulationProducer.fromText("0,brake,1\n5000,brake,0", realtime = true)
        sim.start(this, bus)
        advanceTimeBy(1000); runCurrent()
        sim.stop()
        advanceUntilIdle()
        assertEquals(1, got.size, "stop 뒤에는 더 나오지 않는다")
        assertEquals(SourceStatus.NOT_STARTED, sim.status.value[Source.BRAKE], "stop 뒤에는 상태도 내려간다")
        col.cancel()
    }

    @Test fun simulationWaitsUntilSomeoneSubscribes() = runTest {
        val bus = SignalBus()
        val sim = SimulationProducer.fromText("0,brake,1\n500,brake,0")
        sim.start(this, bus)            // 구독자 없음: 여기서 publish하면 통째로 사라진다
        advanceUntilIdle()
        val got = ArrayList<SignalEvent>()
        val col = launch { bus.events.collect { got.add(it) } }
        advanceUntilIdle()
        assertEquals(2, got.size, "리플레이는 구독자가 붙을 때까지 기다린다")
        col.cancel()
    }
}
