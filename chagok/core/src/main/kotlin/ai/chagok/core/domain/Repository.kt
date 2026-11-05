package ai.chagok.core.domain

import ai.chagok.core.alert.AlertLedger
import ai.chagok.core.alert.InMemoryAlertLedger
import java.time.LocalDate

/**
 * 저장 경계. 안드로이드에서는 Room(암호화)로, 테스트에서는 메모리로.
 * 저장하는 것: Capture 30초, Issue/Record/Profile, 구성원, 유지관리 노트, 하루 알림 횟수(AlertLedger). 그 외(링버퍼, 쿨다운)는 메모리.
 * issues()는 생성 시각 순이어야 한다(겹치는 문제는 먼저 기록된 것만 알리는 규칙이 이 순서를 쓴다).
 */
interface Repository : AlertLedger {
    fun members(): List<Member>
    fun member(id: String): Member?
    fun saveMember(m: Member)

    fun issues(): List<Issue>
    fun issue(id: String): Issue?
    fun saveIssue(issue: Issue)

    fun records(): List<SymptomRecord>
    fun record(id: String): SymptomRecord?
    fun saveRecord(r: SymptomRecord)

    fun profile(issueId: String): ConditionProfile?
    fun saveProfile(p: ConditionProfile)
    fun profiles(): List<ConditionProfile>

    fun maintenance(): List<MaintenanceEntry>
    fun saveMaintenance(e: MaintenanceEntry)
}

class InMemoryRepository : Repository {
    private val memberMap = LinkedHashMap<String, Member>()
    private val issueMap = LinkedHashMap<String, Issue>()
    private val recordMap = LinkedHashMap<String, SymptomRecord>()
    private val profileMap = LinkedHashMap<String, ConditionProfile>()
    private val maintenanceMap = LinkedHashMap<String, MaintenanceEntry>()
    private val ledger = InMemoryAlertLedger()

    override fun members(): List<Member> = memberMap.values.toList()
    override fun member(id: String): Member? = memberMap[id]
    override fun saveMember(m: Member) { memberMap[m.id] = m }

    override fun issues(): List<Issue> = issueMap.values.sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
    override fun issue(id: String): Issue? = issueMap[id]
    override fun saveIssue(issue: Issue) { issueMap[issue.id] = issue }

    override fun records(): List<SymptomRecord> = recordMap.values.toList()
    override fun record(id: String): SymptomRecord? = recordMap[id]
    override fun saveRecord(r: SymptomRecord) { recordMap[r.id] = r }

    override fun profile(issueId: String): ConditionProfile? = profileMap[issueId]
    override fun saveProfile(p: ConditionProfile) { profileMap[p.issueId] = p }
    override fun profiles(): List<ConditionProfile> = profileMap.values.toList()

    override fun maintenance(): List<MaintenanceEntry> = maintenanceMap.values.sortedBy { it.atEpochMs }
    override fun saveMaintenance(e: MaintenanceEntry) { maintenanceMap[e.id] = e }

    override fun alertCount(issueId: String, date: LocalDate) = ledger.alertCount(issueId, date)
    override fun recordAlert(issueId: String, date: LocalDate) = ledger.recordAlert(issueId, date)
}
