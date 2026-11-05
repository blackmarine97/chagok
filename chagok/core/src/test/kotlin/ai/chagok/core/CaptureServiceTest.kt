package ai.chagok.core

import ai.chagok.core.buffer.CaptureService
import ai.chagok.core.domain.FeatureExtractor
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Ns
import ai.chagok.core.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureServiceTest {
    private fun feed(cs: CaptureService, from: Double, to: Double) = (0..((to - from) * 2).toInt()).flatMap { i ->
        cs.onEvent(Fx.steer(from + i * 0.5, 0.0))
    }

    @Test fun captureKeeps20sBeforeAnd10sAfter() {
        val cs = CaptureService()
        feed(cs, 0.0, 60.0)
        cs.capture(Ns.sec(60.0))
        val done = feed(cs, 60.5, 71.0)
        assertEquals(1, done.size)
        val c = done.first()
        assertTrue(c.complete)
        assertEquals(Ns.sec(40.0), c.events.first().tsMonoNs)
        assertTrue(c.events.last().tsMonoNs <= Ns.sec(70.0))
    }

    @Test fun captureAtWorksWithinTempWindowOnly() {
        val cs = CaptureService(tempWindowNs = Ns.sec(600.0))
        feed(cs, 0.0, 700.0)
        assertNotNull(cs.captureAt(Ns.sec(500.0)), "10분 안이면 추출된다")
        assertNull(cs.captureAt(Ns.sec(50.0)), "10분 밖은 밀려나서 없다")
    }

    @Test fun flushMarksIncomplete() {
        val cs = CaptureService()
        feed(cs, 0.0, 30.0)
        cs.capture(Ns.sec(30.0))
        val out = cs.flush(Ns.sec(33.0))
        assertEquals(1, out.size); assertTrue(!out.first().complete)
    }

    @Test fun baselineKeepsLastValueOfQuietSource() {
        // 내비는 변화 시점에만 온다고 가정: t=0에 한 번. 기록 창(40~60초) 안에는 내비 이벤트가 없다.
        val cs = CaptureService()
        cs.onEvent(Fx.navi(0.0, NaviGuidance.STRAIGHT))
        feed(cs, 0.0, 60.0)
        cs.capture(Ns.sec(60.0))
        val c = feed(cs, 60.5, 71.0).single()
        assertTrue(c.events.none { it.source == Source.NAVI }, "창 안에는 내비 이벤트가 없어야 전제가 성립")
        assertEquals(NaviGuidance.STRAIGHT, (c.baseline.single { it.source == Source.NAVI }.value as ai.chagok.core.model.SignalValue.Navi).guidance)
        assertEquals(NaviGuidance.STRAIGHT, FeatureExtractor.extract(c).navi, "창 밖의 마지막 내비 값이 특징값으로 이어진다")
    }

    @Test fun baselineSurvivesEvictionFromTempRing() {
        val cs = CaptureService(tempWindowNs = Ns.sec(600.0))
        cs.onEvent(Fx.navi(0.0, NaviGuidance.NO_GUIDANCE))
        feed(cs, 0.0, 700.0)   // 내비 이벤트는 10분 창에서 밀려남
        val c = cs.captureAt(Ns.sec(650.0))!!
        assertEquals(NaviGuidance.NO_GUIDANCE, FeatureExtractor.extract(c).navi, "밀려난 뒤에도 소스별 마지막 값은 남는다")
    }
}
