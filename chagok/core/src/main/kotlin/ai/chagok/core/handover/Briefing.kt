package ai.chagok.core.handover

import ai.chagok.core.domain.Issue
import ai.chagok.core.domain.IssueStatus
import ai.chagok.core.domain.Repository
import ai.chagok.core.domain.SymptomRecord

/**
 * 탑승 브리핑. 정차 중에 다음 운전자가 듣는다. 순서는 기획서대로:
 *  1. 해결 전달 — 내가 기록했던 문제가 다른 사람 손에서 해결됐으면 첫 줄 (A안: 다음 탑승 브리핑 첫 줄)
 *  2. 미해결 — 열려 있는 문제. 내가 아직 읽지 않은 것이 먼저
 *  3. 확인 중 — 수리 후 확인 중인 문제
 * 습관 배지 한 줄(회전 전 방향지시등)은 TurnSignal 신호 검증 전이라 아직 없다.
 */
data class Briefing(
    val memberId: String,
    val resolved: List<ResolvedLine>,
    val unresolved: List<IssueLine>,
    val inRepairCheck: List<IssueLine>,
) {
    val isEmpty: Boolean get() = resolved.isEmpty() && unresolved.isEmpty() && inRepairCheck.isEmpty()
}

/** "준호가 '앞쪽 끼익 소리'를 해결로 기록했어요 (패드 교체)". */
data class ResolvedLine(val issue: Issue, val resolvedByName: String, val note: String?)

/** 미해결·확인 중 한 줄. 마지막 기록의 작성자와 묘사가 요약이다. 조건 수치는 브리핑에 넣지 않는다(리포트 몫). */
data class IssueLine(
    val issue: Issue,
    val recordCount: Int,
    val lastRecord: SymptomRecord?,
    val lastAuthorName: String?,
    val unread: Boolean,
)

object BriefingBuilder {
    /** 읽기만 한다. 전달 완료·읽음 표시는 Engine이 Board 명령 처리에서 한다. */
    fun build(repo: Repository, memberId: String): Briefing {
        val issues = repo.issues()
        val records = repo.records()
        fun name(id: String?) = id?.let { repo.member(it)?.name ?: it }
        fun line(i: Issue): IssueLine {
            val rs = records.filter { it.issueId == i.id }.sortedBy { it.recordedAtEpochMs }
            val last = rs.lastOrNull()
            return IssueLine(i, rs.size, last, name(last?.authorId), unread = !i.isReadBy(memberId))
        }
        val resolved = issues.filter { i ->
            val r = i.resolution
            i.status == IssueStatus.RESOLVED && r != null && memberId !in r.deliveredTo &&
                r.byMemberId != memberId && involved(i, records, memberId)
        }.map { ResolvedLine(it, name(it.resolution!!.byMemberId)!!, it.resolution.note) }
        val open = issues.filter { it.isOpen }
        val unresolved = open.filter { it.status != IssueStatus.IN_REPAIR_CHECK }.map(::line).sortedByDescending { it.unread }
        val checking = open.filter { it.status == IssueStatus.IN_REPAIR_CHECK }.map(::line)
        return Briefing(memberId, resolved, unresolved, checking)
    }

    /** 내가 만든 문제이거나 내 기록이 붙어 있는 문제. */
    private fun involved(i: Issue, records: List<SymptomRecord>, memberId: String) =
        i.createdBy == memberId || records.any { it.issueId == i.id && it.authorId == memberId }
}
