package ai.chagok.core.model

/** 사용자 명령. 신호와 달리 손실되면 안 되므로 별도 스트림(Channel.UNLIMITED)으로 흐른다. 정차 후에만 받아야 하는 것은 앱이 정차 게이트로 막는다. */
sealed class Command {
    // ── 순간 기록 ──
    /** "방금 그 소리 기록해". 음성·화면 버튼 모두 같은 명령. tsMonoNs는 명령이 들어온 시각. */
    data class Capture(val tsMonoNs: Long, val authorId: String) : Command()
    /** 음성 없는 모드: 정차 후 사용자가 고른 시점으로 임시 버퍼에서 추출. */
    data class CaptureAt(val tsMonoNs: Long, val authorId: String) : Command()
    /** 정차 후 기록을 기존 Issue에 연결하거나(issueId) 새 Issue로 등록(null). 해결된 Issue에 연결하면 재발로 다시 연다. */
    data class LinkRecord(val recordId: String, val issueId: String?, val newIssueTitle: String? = null) : Command()
    /** 정차 후 기록에 묘사를 덧붙인다(사용자 입력). */
    data class Describe(val recordId: String, val text: String) : Command()

    // ── 재현 알림 ──
    /** "다음에 알려줘" — 기록 1건 상태에서 알림을 켠다. */
    data class ArmAlert(val issueId: String, val on: Boolean) : Command()
    /** 정차 후 답변. */
    data class Answer(val issueId: String, val alertId: String, val answer: Observation) : Command()
    /** 3회 연속 못 느꼈을 때 사용자의 선택. */
    data class AdjustAlert(val issueId: String, val choice: AlertAdjustment) : Command()

    // ── 인수인계·해결 ──
    /** 탑승. 이 구성원에게 브리핑을 만들어 낸다(해결 전달 → 미해결 → 확인 중). 전달된 해결은 이 구성원에게 전달 완료로 표시. */
    data class Board(val memberId: String) : Command()
    /** 읽음(확인함). 새 증상을 다른 구성원이 확인하면 Issue는 ACKNOWLEDGED가 된다. */
    data class AcknowledgeIssue(val issueId: String, val memberId: String) : Command()
    /** 정비 등록("패드 교체"). 유지관리 노트(사용자 입력)를 남기고 수리 후 확인 모드로 들어간다. */
    data class StartRepairCheck(val issueId: String, val memberId: String, val note: String) : Command()
    /** 유지관리 노트 추가. 차량 수신 값(주행거리 등)은 source=VEHICLE, authorId=null. */
    data class LogMaintenance(val issueId: String?, val text: String, val authorId: String?, val fromVehicle: Boolean = false) : Command()
    /** 해결로 기록. 알림을 멈추고, 처음 기록한 사람의 다음 탑승 브리핑 첫 줄로 전달된다. */
    data class ResolveIssue(val issueId: String, val memberId: String, val note: String? = null) : Command()
    /** 재발. 해결된 Issue를 다시 열고 조건 프로파일을 되살린다. */
    data class ReopenIssue(val issueId: String) : Command()

    // ── 수명주기(앱이 보냄) ──
    /**
     * 주행 종료(시동 off·앱 종료 직전).
     * post 구간을 못 채운 Capture는 지금까지로 마감해 저장하고, 답을 못 받은 열린 알림은 미확인으로 센다.
     * 임시 10분 버퍼는 건드리지 않는다(정차 화면에서 시점 선택이 아직 가능해야 한다).
     */
    data class EndDrive(val tsMonoNs: Long) : Command()
    /** 임시 데이터 폐기. 정차 후 시점을 고르지 않고 화면을 닫았거나, 더는 선택할 수 없을 때 앱이 보낸다. */
    data object DiscardTemp : Command()
}

enum class Observation { FELT, NOT_FELT, UNKNOWN }
enum class AlertAdjustment { KEEP, REDUCE, STOP }
