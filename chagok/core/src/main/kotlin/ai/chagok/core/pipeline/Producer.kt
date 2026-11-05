package ai.chagok.core.pipeline

import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Producer 경계. Pleos Vehicle/NaviHelper 어댑터와 시뮬레이션 리플레이가 같은 인터페이스를 구현한다.
 * 어댑터는 SDK 콜백에서 즉시 tsMonoNs를 찍고 bus.publish만 호출한다. 그 외 로직은 넣지 않는다.
 */
interface SignalProducer {
    val name: String
    val status: StateFlow<Map<Source, SourceStatus>>
    fun start(scope: CoroutineScope, bus: SignalBus)
    fun stop()
}
