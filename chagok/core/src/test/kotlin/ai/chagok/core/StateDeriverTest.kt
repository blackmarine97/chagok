package ai.chagok.core

import ai.chagok.core.derive.StateDeriver
import ai.chagok.core.model.NaviGuidance
import ai.chagok.core.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateDeriverTest {
    @Test fun brakeHoldIsMeasuredFromOnToOff() {
        val d = StateDeriver()
        d.onEvent(Fx.steer(0.0, 0.0)); d.onEvent(Fx.brake(0.0, true))
        d.onEvent(Fx.steer(1.0, 0.0)); val mid = d.onEvent(Fx.brake(1.0, true))
        assertEquals(1.0, mid.brakeHoldSoFarSec!!, 1e-6)
        val off = d.onEvent(Fx.brake(2.4, false))
        assertEquals(2.4, off.lastCompletedHoldSec!!, 1e-6)
        assertNull(off.brakeHoldSoFarSec)
    }

    @Test fun gapDuringHoldInvalidatesIt() {
        val d = StateDeriver()
        d.onEvent(Fx.brake(0.0, true))
        // 3초 공백 (gap 2초 초과)
        val s = d.onEvent(Fx.brake(3.0, false))
        assertNull(s.lastCompletedHoldSec, "누락이 있으면 유지 시간으로 치지 않는다")
    }

    @Test fun staleSourcesAreFlagged() {
        val d = StateDeriver()
        d.onEvent(Fx.steer(0.0, 2.0)); d.onEvent(Fx.navi(0.0, NaviGuidance.STRAIGHT))
        val s = d.onEvent(Fx.brake(40.0, true))
        assertTrue(Source.STEERING in s.stale); assertNull(s.steeringDeg)
        assertEquals(NaviGuidance.DATA_UNAVAILABLE, s.navi, "내비 stale이면 누락으로 본다")
    }

    @Test fun stoppedWhenOdometerUnchanged() {
        val d = StateDeriver()
        d.onEvent(Fx.odo(0.0, 100.0)); d.onEvent(Fx.odo(1.0, 100.0)); d.onEvent(Fx.odo(2.0, 100.0))
        assertTrue(d.onEvent(Fx.odo(3.5, 100.0)).stopped)
        assertTrue(!d.onEvent(Fx.odo(4.0, 110.0)).stopped)
    }
}
