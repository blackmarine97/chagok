package ai.chagok.core.pipeline

import ai.chagok.core.model.Command
import ai.chagok.core.model.SignalEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 신호 버스: 여러 Producer → 여러 Consumer (fan-out).
 * 밀리면 오래된 것부터 버린다(DROP_OLDEST). 신호는 밀려도 되고 명령은 밀리면 안 되므로 버스를 나눈다.
 * SDK 콜백 스레드를 모르므로 tryEmit만 쓴다(suspend 없음, 스레드 안전).
 */
class SignalBus(bufferCapacity: Int = 4096) {
    private val seq = AtomicLong(0)
    private val _events = MutableSharedFlow<SignalEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SignalEvent> = _events.asSharedFlow()
    /** 현재 구독자 수. replay=0이라 구독자가 붙기 전에 publish된 신호는 버려진다. */
    val subscriptionCount: StateFlow<Int> get() = _events.subscriptionCount
    /** 구독자(보통 Engine)가 붙을 때까지 기다린다. 리플레이·테스트처럼 시작 순서를 보장해야 하는 Producer가 쓴다. SDK 어댑터는 콜백이 계속 오므로 필요 없다. */
    suspend fun awaitSubscriber() { subscriptionCount.first { it > 0 } }

    /** seq를 버스가 부여한다. Producer는 seq를 신경 쓰지 않는다. */
    fun publish(event: SignalEvent): Boolean =
        _events.tryEmit(event.copy(seq = seq.incrementAndGet()))
}

/** 명령 버스: 손실 없음, 순서 보장. */
class CommandBus {
    private val channel = Channel<Command>(Channel.UNLIMITED)
    val commands = channel.receiveAsFlow()
    fun send(cmd: Command) { channel.trySend(cmd) }
}
