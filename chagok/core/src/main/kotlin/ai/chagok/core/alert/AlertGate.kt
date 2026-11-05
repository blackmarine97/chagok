package ai.chagok.core.alert

import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.model.Ns
import java.time.LocalDate

/** 하루 상한·쿨다운. 하루는 차량 현지 날짜 기준. 초안값. */
data class AlertPolicy(val dailyCap: Int = 2, val cooldownNs: Long = Ns.sec(600.0), val reducedDailyCap: Int = 1)

/** 주행 규제 게이트. CarUxRestrictions 등 플랫폼 상태를 보는 쪽이 구현한다. 알림 자체는 짧은 음성·아이콘이라 허용되는 범위를 P0에서 확인. */
fun interface DrivingGate { fun alertsAllowed(): Boolean }

/**
 * 하루 알림 횟수 장부. 앱이 재시작해도 "같은 문제 하루 2회"가 지켜지려면 저장소에 있어야 한다.
 * Repository가 이 인터페이스를 구현한다. 쿨다운은 단조 시계라 메모리에만 두고, 재시작 직후 한 번 더 울릴 수 있는 것은 감수한다.
 */
interface AlertLedger {
    fun alertCount(issueId: String, date: LocalDate): Int
    fun recordAlert(issueId: String, date: LocalDate)
}

class InMemoryAlertLedger : AlertLedger {
    private val counts = HashMap<Pair<String, LocalDate>, Int>()
    override fun alertCount(issueId: String, date: LocalDate) = counts[issueId to date] ?: 0
    override fun recordAlert(issueId: String, date: LocalDate) { counts[issueId to date] = alertCount(issueId, date) + 1 }
}

sealed class Decision {
    data class Allow(val alertId: String) : Decision()
    data class Deny(val reason: String) : Decision()
}

data class AlertCandidate(val issueId: String, val tsMonoNs: Long, val naviChecked: Boolean, val repairCheck: Boolean)

/**
 * 매처는 후보만 내고, 실제로 낼지는 여기서 정한다. 쿨다운·상한·주행 게이트를 매처에 넣지 않는 이유:
 * 나중에 휴식 알림 등 다른 알림과 같은 채널을 쓰기 위해서.
 */
class AlertGate(
    private val policy: AlertPolicy = AlertPolicy(),
    private val drivingGate: DrivingGate = DrivingGate { true },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val ledger: AlertLedger = InMemoryAlertLedger(),
) {
    private var seq = 0L
    private val lastAlertNs = HashMap<String, Long>()

    fun decide(c: AlertCandidate, p: ConditionProfile): Decision {
        if (!p.alertsEnabled) return Decision.Deny("profile ${p.state}")
        if (p.needsUserChoice) return Decision.Deny("needs user choice after 3 not-felt")
        if (!drivingGate.alertsAllowed()) return Decision.Deny("driving restriction")
        // 내비 없이 판정한 후보는 문턱을 올린다: 쿨다운 2배
        val cooldown = if (c.naviChecked) policy.cooldownNs else policy.cooldownNs * 2
        lastAlertNs[c.issueId]?.let { if (c.tsMonoNs - it < cooldown) return Decision.Deny("cooldown") }
        val d = today()
        val cap = if (p.state == ProfileState.REDUCED) policy.reducedDailyCap else policy.dailyCap
        if (ledger.alertCount(c.issueId, d) >= cap) return Decision.Deny("daily cap")
        ledger.recordAlert(c.issueId, d); lastAlertNs[c.issueId] = c.tsMonoNs
        return Decision.Allow("a${++seq}")   // 알림 id는 세션 안에서만 쓰인다(열린 알림 ↔ 답변 연결)
    }
}
