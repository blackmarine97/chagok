package ai.chagok.core.model

/** 차량 신호 출처. 속도는 SDK에서 제거되어 없음 (Vehicle SDK v2.3.2 릴리스 노트). */
enum class Source { BRAKE, STEERING, ODOMETER, NAVI }

/** 내비 안내 상황 유형. 좌표·구간은 저장하지 않는다. NO_GUIDANCE는 정상 상태값, DATA_UNAVAILABLE은 누락. */
enum class NaviGuidance { STRAIGHT, TURN_AHEAD, NO_GUIDANCE, DATA_UNAVAILABLE }

sealed class SignalValue {
    data class BrakePedal(val pressed: Boolean) : SignalValue()
    /** 앞바퀴 조향각(도). 왼쪽 음수. */
    data class SteeringAngle(val deg: Double) : SignalValue()
    /** 누적 주행거리(m)와 트립 시간(s). 정차 판단·기록 시점용. */
    data class Odometer(val totalMeters: Double, val tripSeconds: Long) : SignalValue()
    data class Navi(val guidance: NaviGuidance) : SignalValue()
}

/**
 * 모든 SDK 콜백은 이 한 가지 타입으로 정규화된다.
 * tsMonoNs: 콜백을 받은 순간의 단조 시계(SystemClock.elapsedRealtimeNanos 등). 벽시계는 쓰지 않는다.
 * seq: 버스에 들어온 순서. 같은 ts의 정렬 안정성용.
 */
data class SignalEvent(
    val source: Source,
    val value: SignalValue,
    val tsMonoNs: Long,
    val seq: Long = 0L,
)

/** 소스별 상태. PERMISSION_DENIED/UNSUPPORTED는 SDK onFailed(VehicleException)에서, STALE은 lastSeen 규칙에서 온다. */
enum class SourceStatus { AVAILABLE, STALE, PERMISSION_DENIED, UNSUPPORTED, NOT_STARTED }

object Ns {
    const val MS = 1_000_000L
    const val SEC = 1_000_000_000L
    fun sec(s: Double): Long = (s * SEC).toLong()
}
