package ai.chagok.core.sim

import ai.chagok.core.model.*
import ai.chagok.core.pipeline.SignalBus
import ai.chagok.core.pipeline.SignalProducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 녹화된 이벤트를 리플레이한다. P0에서 에뮬레이터로 받은 실측 로그를 그대로 넣어 단위 테스트와 시연에 쓴다.
 * 한 줄 형식: offsetMs,source,value
 *   brake,1 | brake,0 | steer,-3.5 | odo,32410.0,1234 | navi,STRAIGHT
 * realtime=false면 지연 없이 즉시 모두 publish (테스트용).
 */
class SimulationProducer(
    private val lines: List<String>,
    private val realtime: Boolean = false,
    private val baseTsNs: Long = 0L,
    override val name: String = "sim",
) : SignalProducer {
    private val _status = MutableStateFlow(Source.entries.associateWith { SourceStatus.NOT_STARTED })
    override val status: StateFlow<Map<Source, SourceStatus>> = _status
    private var job: Job? = null

    val events: List<SignalEvent> by lazy { lines.mapNotNull { parse(it) } }

    override fun start(scope: CoroutineScope, bus: SignalBus) {
        _status.value = Source.entries.associateWith { SourceStatus.AVAILABLE }
        job = scope.launch {
            var prev = 0L
            for (e in events) {
                if (realtime) { val wait = (e.tsMonoNs - baseTsNs) / Ns.MS - prev; if (wait > 0) delay(wait); prev = (e.tsMonoNs - baseTsNs) / Ns.MS }
                bus.publish(e)
            }
        }
    }

    override fun stop() { job?.cancel() }

    private fun parse(line: String): SignalEvent? {
        val t = line.trim(); if (t.isEmpty() || t.startsWith("#")) return null
        val p = t.split(",").map { it.trim() }
        val ts = baseTsNs + p[0].toLong() * Ns.MS
        return when (p[1]) {
            "brake" -> SignalEvent(Source.BRAKE, SignalValue.BrakePedal(p[2] == "1" || p[2].equals("true", true)), ts)
            "steer" -> SignalEvent(Source.STEERING, SignalValue.SteeringAngle(p[2].toDouble()), ts)
            "odo" -> SignalEvent(Source.ODOMETER, SignalValue.Odometer(p[2].toDouble(), p.getOrNull(3)?.toLong() ?: 0L), ts)
            "navi" -> SignalEvent(Source.NAVI, SignalValue.Navi(NaviGuidance.valueOf(p[2])), ts)
            else -> null
        }
    }

    companion object {
        fun fromText(text: String, realtime: Boolean = false, baseTsNs: Long = 0L) = SimulationProducer(text.lines(), realtime, baseTsNs)
    }
}
