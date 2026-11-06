package ai.chagok.core.domain

import ai.chagok.core.buffer.Capture
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Observation

/** 가족 구성원. 운전자 식별은 프로필 선택(+PIN은 앱)으로 하고, 조건 분석에는 쓰지 않는다. 기록 작성자 표시와 브리핑 대상에만 쓴다. */
data class Member(val id: String, val name: String)

/** 증상 사건의 생명주기. 재현 조건 상태(ProfileState)와는 별개다. 새 증상 → 확인함·미해결 → 정비·수리 후 확인 → 해결 ↩ 재발. */
enum class IssueStatus { NEW, ACKNOWLEDGED, IN_REPAIR_CHECK, RESOLVED, REOPENED }

/** 해결 기록. 처음 기록한 사람에게 다음 탑승 브리핑 첫 줄로 전달된다. deliveredTo: 브리핑으로 전달이 끝난 구성원. */
data class Resolution(val byMemberId: String, val note: String?, val atEpochMs: Long, val deliveredTo: Set<String> = emptySet())

data class Issue(
    val id: String,
    val title: String,
    val status: IssueStatus,
    val createdBy: String,
    val createdAtEpochMs: Long = 0L,     // 벽시계. 표시와 "먼저 기록된 문제" 순서에만 쓴다
    val readBy: Set<String> = emptySet(),   // 읽음(확인함) 표시. 구성원별
    val resolution: Resolution? = null,
    val updatedAtEpochMs: Long = createdAtEpochMs,
) {
    val isOpen: Boolean get() = status != IssueStatus.RESOLVED
    fun isReadBy(memberId: String) = memberId in readBy || memberId == createdBy
}

/** 정비 노트의 출처. 리포트에서 사용자 입력과 차량 수신을 구분해야 한다(v2 유지관리 노트 원칙). */
enum class EntrySource { USER_INPUT, VEHICLE }

/** 유지관리 노트 한 줄. 정비 등록("패드 교체"), 차량이 보낸 값(주행거리) 등. */
data class MaintenanceEntry(
    val id: String,
    val issueId: String?,
    val source: EntrySource,
    val text: String,
    val authorId: String?,            // VEHICLE이면 null
    val atEpochMs: Long,
)

/** 한 건의 순간 기록. Capture에서 뽑은 특징값(features)을 같이 둔다. 조건 비교는 features만 쓴다. */
data class SymptomRecord(
    val id: String,
    val issueId: String?,            // 정차 후 사용자가 연결하기 전엔 null
    val authorId: String,
    val tripId: String,              // 같은 주행인지 구분. 다른 주행 2건이 잠정 패턴의 조건
    val capture: Capture,
    val features: RecordFeatures,
    val description: String? = null, // 정차 후 사용자가 덧붙인 묘사(사용자 입력)
    val recordedAtEpochMs: Long = 0L, // 벽시계. 표시용
)

/** 기록 1건에서 뽑은 비교용 값(차량 수신). 소리 원음 없음. */
data class RecordFeatures(
    val brakeHoldSec: Double?,       // 발화 직전 완료되었거나 진행 중이던 유지 시간
    val steeringDeg: Double?,
    val navi: NaviGuidance,
    val odometerMeters: Double? = null,   // 기록 시점 누적 주행거리. 조건 비교에는 쓰지 않고 리포트 표시용
    val brakeHoldCompleted: Boolean = false, // ON→OFF 완료값인지(true), 진행 중 하한인지(false). 리포트 표시용
)

/** 재현 조건의 상태. Issue 상태와 독립. */
enum class ProfileState { NONE, CANDIDATE, PROVISIONAL, ACTIVE, REDUCED, STOPPED }

data class Range(val min: Double, val max: Double) {
    operator fun contains(v: Double) = v in min..max
    fun widen(tol: Double) = Range(min - tol, max + tol)
}

/** 느낌·못 느낌·미확인 세 카운트. 확률·점수는 계산하지 않는다. */
data class Counts(val felt: Int = 0, val notFelt: Int = 0, val unknown: Int = 0, val consecutiveNotFelt: Int = 0) {
    fun apply(o: Observation): Counts = when (o) {
        Observation.FELT -> copy(felt = felt + 1, consecutiveNotFelt = 0)
        Observation.NOT_FELT -> copy(notFelt = notFelt + 1, consecutiveNotFelt = consecutiveNotFelt + 1)
        Observation.UNKNOWN -> copy(unknown = unknown + 1)
    }
}

/**
 * Issue별 조건 프로파일. 기본 필터를 지난 뒤 이 범위와 비교한다.
 * brakeHold/steering: 같은 Issue 기록값의 최소~최대 ± 허용 오차. navi: 기록된 안내 상황 유형들.
 * armedByUser: 기록 1건 상태에서 "다음에 알려줘"를 켰는지.
 */
data class ConditionProfile(
    val issueId: String,
    val state: ProfileState,
    val brakeHold: Range?,
    val steering: Range?,
    val navi: Set<NaviGuidance>,
    val counts: Counts = Counts(),
    val armedByUser: Boolean = false,
    val recordCount: Int = 0,
    val distinctTrips: Int = 0,
) {
    val alertsEnabled: Boolean get() = when (state) {
        ProfileState.CANDIDATE -> armedByUser
        ProfileState.PROVISIONAL, ProfileState.ACTIVE, ProfileState.REDUCED -> true
        else -> false
    }
    /** 3회 연속 못 느꼈으면 사용자에게 축소·중지를 묻는다. 범위는 넓히지 않는다. */
    val needsUserChoice: Boolean get() = counts.consecutiveNotFelt >= 3 && state != ProfileState.STOPPED
}

/** 허용 오차. 초안값. P0 실측 후 조정. */
data class Tolerance(val brakeHoldSec: Double = 0.5, val steeringDeg: Double = 3.0)

object ProfileBuilder {
    /** 같은 Issue에 묶인 기록들로 프로파일을 만든다. 1건 = 후보, 다른 주행 2건+ = 잠정 패턴. prev가 REDUCED/STOPPED면 그 상태를 유지한다. */
    fun build(issueId: String, records: List<SymptomRecord>, tol: Tolerance = Tolerance(), prev: ConditionProfile? = null): ConditionProfile {
        val rs = records.filter { it.issueId == issueId }
        if (rs.isEmpty()) return ConditionProfile(issueId, ProfileState.NONE, null, null, emptySet())
        val holds = rs.mapNotNull { it.features.brakeHoldSec }
        val steers = rs.mapNotNull { it.features.steeringDeg }
        val navis = rs.map { it.features.navi }.filter { it != NaviGuidance.DATA_UNAVAILABLE }.toSet()
        val trips = rs.map { it.tripId }.toSet().size
        val keepState = prev?.state?.takeIf { it == ProfileState.REDUCED || it == ProfileState.STOPPED }
        val state = keepState ?: if (trips >= 2) ProfileState.PROVISIONAL else ProfileState.CANDIDATE
        return ConditionProfile(
            issueId = issueId, state = state,
            brakeHold = holds.takeIf { it.isNotEmpty() }?.let { Range(it.min(), it.max()).widen(tol.brakeHoldSec) },
            steering = steers.takeIf { it.isNotEmpty() }?.let { Range(it.min(), it.max()).widen(tol.steeringDeg) },
            navi = navis,
            counts = prev?.counts ?: Counts(),
            armedByUser = prev?.armedByUser ?: false,
            recordCount = rs.size, distinctTrips = trips,
        )
    }
}
