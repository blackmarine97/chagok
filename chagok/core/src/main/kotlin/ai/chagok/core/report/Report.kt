package ai.chagok.core.report

import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.Counts
import ai.chagok.core.domain.EntrySource
import ai.chagok.core.domain.Issue
import ai.chagok.core.domain.MaintenanceEntry
import ai.chagok.core.domain.Repository
import ai.chagok.core.model.NaviGuidance

/**
 * 정비 리포트. 정비사에게 "어떤 조건에서 났다고 기록됐는가"를 넘긴다.
 * 모든 줄은 출처가 사용자 입력인지 차량 수신인지 구분된다(기획서 첫 시연 완료 기준).
 * 공유(QR·링크·만료·회수)는 앱·서버 몫이고, 여기는 공유할 내용을 만든다. 위치·이동 이력은 애초에 모델에 없다.
 */
data class Report(
    val issue: Issue,
    val observations: List<ObservationRow>,
    val profile: ConditionProfile?,
    val counts: Counts,
    val maintenance: List<MaintenanceEntry>,
) {
    /** "회전 무관"의 근거: 기록된 조향 범위가 기본 필터(|조향| ≤ 10°) 안이면 완만한 구간에서만 났다는 뜻. */
    val steeringSummary: String get() = profile?.steering?.let { "조향 ${fmt(it.min)}°~${fmt(it.max)}°" } ?: "조향 기록 없음"
}

/** 기록 1건 = 표 한 줄. 차량 수신 값과 사용자 입력(묘사)을 한 줄에 두되 출처를 열마다 표시한다. */
data class ObservationRow(
    val recordId: String,
    val recordedAtEpochMs: Long,
    val authorName: String,
    val brakeHoldSec: Double?,       // VEHICLE
    val steeringDeg: Double?,        // VEHICLE
    val navi: NaviGuidance,          // VEHICLE
    val odometerKm: Double?,         // VEHICLE
    val description: String?,        // USER_INPUT
) {
    fun sourceOf(column: String): EntrySource = if (column == "description") EntrySource.USER_INPUT else EntrySource.VEHICLE
}

object ReportBuilder {
    fun build(repo: Repository, issueId: String): Report? {
        val issue = repo.issue(issueId) ?: return null
        fun name(id: String) = repo.member(id)?.name ?: id
        val rows = repo.records().filter { it.issueId == issueId }.sortedBy { it.recordedAtEpochMs }.map { r ->
            ObservationRow(
                recordId = r.id, recordedAtEpochMs = r.recordedAtEpochMs, authorName = name(r.authorId),
                brakeHoldSec = r.features.brakeHoldSec, steeringDeg = r.features.steeringDeg, navi = r.features.navi,
                odometerKm = r.features.odometerMeters?.let { it / 1000.0 }, description = r.description,
            )
        }
        val p = repo.profile(issueId)
        return Report(issue, rows, p, p?.counts ?: Counts(), repo.maintenance().filter { it.issueId == issueId })
    }

    /** 시연·테스트용 평문. 앱은 화면과 공유 포맷을 따로 만든다. */
    fun render(r: Report): String = buildString {
        appendLine("문제: ${r.issue.title} [${r.issue.status}]")
        appendLine("관측 ${r.observations.size}건 · 느낌 ${r.counts.felt} / 못 느낌 ${r.counts.notFelt} / 미확인 ${r.counts.unknown}")
        r.profile?.let { p ->
            appendLine("조건: 브레이크 ${p.brakeHold?.let { "${fmt(it.min)}~${fmt(it.max)}초" } ?: "-"} · ${r.steeringSummary} · 내비 ${p.navi.joinToString("/").ifEmpty { "-" }}")
        }
        appendLine("기록 (차량 수신: 브레이크·조향·내비·주행거리 / 사용자 입력: 묘사)")
        for (o in r.observations) {
            appendLine("  - ${o.authorName}: 브레이크 ${o.brakeHoldSec?.let { "${fmt(it)}초" } ?: "-"}, 조향 ${o.steeringDeg?.let { "${fmt(it)}°" } ?: "-"}, " +
                "내비 ${o.navi}, 주행거리 ${o.odometerKm?.let { "${fmt(it)}km" } ?: "-"}" + (o.description?.let { " · \"$it\"" } ?: ""))
        }
        if (r.maintenance.isNotEmpty()) {
            appendLine("정비 노트")
            for (m in r.maintenance) appendLine("  - [${if (m.source == EntrySource.USER_INPUT) "사용자 입력" else "차량 수신"}] ${m.text}")
        }
    }
}

internal fun fmt(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format("%.1f", v)
