package ai.chagok.core.derive

import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.SignalValue
import ai.chagok.core.model.Source
import ai.chagok.core.model.SourceStatus

/**
 * 원시 신호에서 파생한 현재 상태. 소비자(매처·링버퍼·UI 게이트)는 원시 신호가 아니라 이것을 본다.
 * brakeHoldSoFarSec: 지금 밟고 있는 중이면 지금까지의 유지 시간(진행 중). 알림은 이 값으로 판정한다.
 * lastCompletedHoldSec: 마지막으로 OFF가 된 유지 시간. 중간 누락이 있었으면 null(유지로 치지 않음).
 * brakeHoldInvalid: 밟고 있는 중인데 신호 공백으로 유지 시간을 믿을 수 없다. 매처는 이걸 "판정 불가"로 본다.
 */
data class DerivedState(
    val tsMonoNs: Long,
    val brakeOn: Boolean,
    val brakeHoldSoFarSec: Double?,
    val lastCompletedHoldSec: Double?,
    val steeringDeg: Double?,
    val navi: NaviGuidance,
    val stale: Set<Source>,
    val stopped: Boolean,
    /** Producer가 PERMISSION_DENIED·UNSUPPORTED로 보고한 소스. stale과 같이 "판정 불가"로 흐른다. */
    val unavailable: Set<Source> = emptySet(),
    val brakeHoldInvalid: Boolean = false,
) {
    fun usable(src: Source) = src !in stale && src !in unavailable
}

/**
 * 순차 소비자. 이벤트를 받을 때마다 DerivedState를 낸다.
 * staleNs: 소스별로 이 시간 넘게 새 이벤트가 없으면 STALE. 주기적으로 오는 소스에만 둔다.
 *   내비 안내 유형은 변화 시점에만 오는 값으로 보고 시간 기준 stale을 두지 않는다. NaviHelper가 정보를 못 주는 상황은
 *   Producer 상태(UNSUPPORTED 등)로 들어온다. 브레이크·조향도 변화 시점에만 온다면 어댑터가 하트비트(마지막 값 재발행)를
 *   해야 하며, 그 주기는 P0에서 정한다.
 * gapNs: 브레이크 ON 유지 중 이 시간 넘게 아무 신호도 안 오면 그 유지 구간은 무효(판정 불가).
 * stoppedAfterNs: 주행거리가 이 시간 동안 안 늘면 정차로 본다. 주행거리 해상도가 거칠면 저속 주행도 정차로 보일 수 있다 → P0 확인.
 */
class StateDeriver(
    private val staleNs: Map<Source, Long> = mapOf(
        Source.BRAKE to Ns.sec(5.0), Source.STEERING to Ns.sec(5.0), Source.ODOMETER to Ns.sec(10.0),
    ),
    private val gapNs: Long = Ns.sec(2.0),
    private val stoppedAfterNs: Long = Ns.sec(3.0),
) {
    private val lastSeen = HashMap<Source, Long>()
    private var brakeOn = false
    private var brakeOnSince: Long? = null
    private var brakeHoldValid = true
    private var lastEventTs: Long? = null
    private var lastCompletedHold: Double? = null
    private var steering: Double? = null
    private var navi = NaviGuidance.DATA_UNAVAILABLE
    private var lastOdoMeters: Double? = null
    private var odoChangedAt: Long? = null
    /** 소스별 Producer 상태. Engine이 Producer status를 받아 넣는다. 기본은 모두 사용 가능으로 본다. */
    var sourceStatus: Map<Source, SourceStatus> = emptyMap()

    /** 주행 종료. 이전 주행의 브레이크·공백 상태를 다음 주행으로 끌고 가지 않는다. 내비 마지막 값은 유지한다. */
    fun reset() {
        lastSeen.clear(); brakeOn = false; brakeOnSince = null; brakeHoldValid = true; lastEventTs = null
        lastCompletedHold = null; steering = null; lastOdoMeters = null; odoChangedAt = null
    }

    fun onEvent(e: SignalEvent): DerivedState {
        val now = e.tsMonoNs
        // 브레이크 유지 중 신호 공백 검사: 어떤 소스든 이벤트가 gap 이상 끊기면 유지 무효
        lastEventTs?.let { prev -> if (brakeOn && now - prev > gapNs) brakeHoldValid = false }
        lastEventTs = now
        lastSeen[e.source] = now

        when (val v = e.value) {
            is SignalValue.BrakePedal -> {
                if (v.pressed && !brakeOn) { brakeOn = true; brakeOnSince = now; brakeHoldValid = true }
                else if (!v.pressed && brakeOn) {
                    val since = brakeOnSince
                    lastCompletedHold = if (brakeHoldValid && since != null) (now - since).toDouble() / Ns.SEC else null
                    brakeOn = false; brakeOnSince = null
                }
            }
            is SignalValue.SteeringAngle -> steering = v.deg
            is SignalValue.Navi -> navi = v.guidance
            is SignalValue.Odometer -> {
                if (lastOdoMeters == null || v.totalMeters != lastOdoMeters) { lastOdoMeters = v.totalMeters; odoChangedAt = now }
            }
        }

        val stale = staleNs.filter { (src, limit) -> val seen = lastSeen[src]; seen == null || now - seen > limit }.keys
        val unavailable = sourceStatus.filterValues { it == SourceStatus.PERMISSION_DENIED || it == SourceStatus.UNSUPPORTED }.keys
        val holdSoFar = if (brakeOn && brakeHoldValid) brakeOnSince?.let { (now - it).toDouble() / Ns.SEC } else null
        val naviEff = if (Source.NAVI in stale || Source.NAVI in unavailable) NaviGuidance.DATA_UNAVAILABLE else navi
        val stopped = odoChangedAt?.let { now - it >= stoppedAfterNs } ?: false

        return DerivedState(
            tsMonoNs = now, brakeOn = brakeOn, brakeHoldSoFarSec = holdSoFar, lastCompletedHoldSec = lastCompletedHold,
            steeringDeg = if (Source.STEERING in stale || Source.STEERING in unavailable) null else steering,
            navi = naviEff, stale = stale, stopped = stopped, unavailable = unavailable,
            brakeHoldInvalid = brakeOn && !brakeHoldValid,
        )
    }
}
