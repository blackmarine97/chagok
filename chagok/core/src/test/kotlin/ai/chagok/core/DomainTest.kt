package ai.chagok.core

import ai.chagok.core.buffer.Capture
import ai.chagok.core.domain.ConditionProfile
import ai.chagok.core.domain.Counts
import ai.chagok.core.domain.InMemoryRepository
import ai.chagok.core.domain.Issue
import ai.chagok.core.domain.IssueStatus
import ai.chagok.core.domain.MaintenanceEntry
import ai.chagok.core.domain.EntrySource
import ai.chagok.core.domain.ProfileBuilder
import ai.chagok.core.domain.ProfileState
import ai.chagok.core.domain.Range
import ai.chagok.core.domain.RecordFeatures
import ai.chagok.core.domain.SymptomRecord
import ai.chagok.core.domain.Tolerance
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Observation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainTest {
    private val emptyCapture = Capture(0L, emptyList(), 0L, 0L, complete = true)
    private fun rec(id: String, issue: String?, trip: String, hold: Double?, steer: Double?, navi: NaviGuidance) =
        SymptomRecord(id, issue, "a", trip, emptyCapture, RecordFeatures(hold, steer, navi))

    @Test fun countsFollowThePlan() {
        var c = Counts()
        c = c.apply(Observation.NOT_FELT).apply(Observation.NOT_FELT)
        assertEquals(2, c.consecutiveNotFelt)
        c = c.apply(Observation.UNKNOWN)
        assertEquals(2, c.consecutiveNotFelt, "미확인은 연속 카운트를 깨지 않는다"); assertEquals(1, c.unknown)
        c = c.apply(Observation.FELT)
        assertEquals(0, c.consecutiveNotFelt, "느꼈으면 연속 카운트 초기화"); assertEquals(1, c.felt); assertEquals(2, c.notFelt)
    }

    @Test fun alertsEnabledPerProfileState() {
        fun p(s: ProfileState, armed: Boolean = false) = ConditionProfile("i", s, null, null, emptySet(), armedByUser = armed)
        assertFalse(p(ProfileState.NONE).alertsEnabled)
        assertFalse(p(ProfileState.CANDIDATE).alertsEnabled)
        assertTrue(p(ProfileState.CANDIDATE, armed = true).alertsEnabled, "후보는 '다음에 알려줘'를 켰을 때만")
        assertTrue(p(ProfileState.PROVISIONAL).alertsEnabled); assertTrue(p(ProfileState.ACTIVE).alertsEnabled); assertTrue(p(ProfileState.REDUCED).alertsEnabled)
        assertFalse(p(ProfileState.STOPPED).alertsEnabled)
        assertFalse(p(ProfileState.STOPPED, armed = true).alertsEnabled)
    }

    @Test fun needsUserChoiceAfterThreeConsecutiveNotFeltUnlessStopped() {
        val three = Counts(notFelt = 3, consecutiveNotFelt = 3)
        assertTrue(ConditionProfile("i", ProfileState.PROVISIONAL, null, null, emptySet(), counts = three).needsUserChoice)
        assertFalse(ConditionProfile("i", ProfileState.PROVISIONAL, null, null, emptySet(), counts = Counts(notFelt = 3, consecutiveNotFelt = 2)).needsUserChoice)
        assertFalse(ConditionProfile("i", ProfileState.STOPPED, null, null, emptySet(), counts = three).needsUserChoice, "이미 중지했으면 묻지 않는다")
    }

    @Test fun rangeWidenAndContains() {
        val r = Range(1.8, 2.4).widen(0.5)
        assertEquals(Range(1.3, 2.9), r)
        assertTrue(1.3 in r && 2.9 in r); assertFalse(1.29 in r); assertFalse(2.91 in r)
    }

    @Test fun profileBuilderStates() {
        assertEquals(ProfileState.NONE, ProfileBuilder.build("i", emptyList()).state)
        val one = ProfileBuilder.build("i", listOf(rec("r1", "i", "t1", 2.0, -3.0, NaviGuidance.STRAIGHT)))
        assertEquals(ProfileState.CANDIDATE, one.state); assertEquals(1, one.recordCount); assertEquals(1, one.distinctTrips)
        val sameTrip = ProfileBuilder.build("i", listOf(rec("r1", "i", "t1", 2.0, -3.0, NaviGuidance.STRAIGHT), rec("r2", "i", "t1", 1.8, 2.0, NaviGuidance.STRAIGHT)))
        assertEquals(ProfileState.CANDIDATE, sameTrip.state, "같은 주행 2건은 아직 후보"); assertEquals(2, sameTrip.recordCount); assertEquals(1, sameTrip.distinctTrips)
        val twoTrips = ProfileBuilder.build("i", listOf(rec("r1", "i", "t1", 2.0, -3.0, NaviGuidance.STRAIGHT), rec("r2", "i", "t2", 1.8, 2.0, NaviGuidance.STRAIGHT)))
        assertEquals(ProfileState.PROVISIONAL, twoTrips.state); assertEquals(2, twoTrips.distinctTrips)
    }

    @Test fun profileBuilderRangesAndNavi() {
        val p = ProfileBuilder.build("i", listOf(
            rec("r1", "i", "t1", 1.8, -3.0, NaviGuidance.STRAIGHT),
            rec("r2", "i", "t2", 2.4, 8.0, NaviGuidance.DATA_UNAVAILABLE),
            rec("r3", "i", "t3", null, null, NaviGuidance.NO_GUIDANCE),
            rec("x", "other", "t1", 9.0, 90.0, NaviGuidance.TURN_AHEAD),
        ), Tolerance(brakeHoldSec = 0.5, steeringDeg = 3.0))
        assertEquals(Range(1.3, 2.9), p.brakeHold, "기록값 최소~최대 ± 오차, null은 무시")
        assertEquals(Range(-6.0, 11.0), p.steering)
        assertEquals(setOf(NaviGuidance.STRAIGHT, NaviGuidance.NO_GUIDANCE), p.navi, "DATA_UNAVAILABLE은 유형이 아니라 누락이라 제외, NO_GUIDANCE는 정상 값")
        assertEquals(3, p.recordCount, "다른 Issue 기록은 섞이지 않는다")
        val noValues = ProfileBuilder.build("i", listOf(rec("r3", "i", "t3", null, null, NaviGuidance.DATA_UNAVAILABLE)))
        assertNull(noValues.brakeHold); assertNull(noValues.steering); assertTrue(noValues.navi.isEmpty())
    }

    @Test fun profileBuilderKeepsReducedOrStoppedAndCarriesCountsAndArming() {
        val rs = listOf(rec("r1", "i", "t1", 2.0, 0.0, NaviGuidance.STRAIGHT), rec("r2", "i", "t2", 2.0, 0.0, NaviGuidance.STRAIGHT))
        val prev = ConditionProfile("i", ProfileState.REDUCED, null, null, emptySet(), counts = Counts(felt = 2), armedByUser = true)
        val p = ProfileBuilder.build("i", rs, prev = prev)
        assertEquals(ProfileState.REDUCED, p.state, "사용자가 축소했으면 기록이 늘어도 되돌리지 않는다")
        assertEquals(Counts(felt = 2), p.counts); assertTrue(p.armedByUser)
        assertEquals(ProfileState.STOPPED, ProfileBuilder.build("i", rs, prev = prev.copy(state = ProfileState.STOPPED)).state)
        assertEquals(ProfileState.PROVISIONAL, ProfileBuilder.build("i", rs, prev = prev.copy(state = ProfileState.CANDIDATE)).state, "후보였으면 기록 수로 다시 계산")
    }

    @Test fun issueHelpers() {
        val i = Issue("i", "t", IssueStatus.NEW, "minji", readBy = setOf("junho"))
        assertTrue(i.isReadBy("minji"), "기록자 본인은 항상 읽음"); assertTrue(i.isReadBy("junho")); assertFalse(i.isReadBy("sora"))
        assertTrue(i.isOpen); assertFalse(i.copy(status = IssueStatus.RESOLVED).isOpen); assertTrue(i.copy(status = IssueStatus.REOPENED).isOpen)
    }

    @Test fun inMemoryRepositoryOrdersIssuesByCreationAndMaintenanceByTime() {
        val repo = InMemoryRepository()
        repo.saveIssue(Issue("b", "B", IssueStatus.NEW, "m", createdAtEpochMs = 20))
        repo.saveIssue(Issue("a", "A", IssueStatus.NEW, "m", createdAtEpochMs = 10))
        repo.saveIssue(Issue("c", "C", IssueStatus.NEW, "m", createdAtEpochMs = 10))
        assertEquals(listOf("a", "c", "b"), repo.issues().map { it.id }, "생성 시각, 같으면 id")
        repo.saveMaintenance(MaintenanceEntry("m2", "a", EntrySource.VEHICLE, "odo", null, 200))
        repo.saveMaintenance(MaintenanceEntry("m1", "a", EntrySource.USER_INPUT, "pad", "m", 100))
        assertEquals(listOf("m1", "m2"), repo.maintenance().map { it.id })
        repo.saveIssue(repo.issue("a")!!.copy(title = "A2"))
        assertEquals(3, repo.issues().size, "같은 id 저장은 덮어쓴다"); assertEquals("A2", repo.issue("a")!!.title)
    }
}
