package ai.chagok.core

import ai.chagok.core.model.*

/** 테스트용 이벤트 빌더. t는 초. */
object Fx {
    fun brake(t: Double, on: Boolean) = SignalEvent(Source.BRAKE, SignalValue.BrakePedal(on), Ns.sec(t))
    fun steer(t: Double, deg: Double) = SignalEvent(Source.STEERING, SignalValue.SteeringAngle(deg), Ns.sec(t))
    fun odo(t: Double, m: Double) = SignalEvent(Source.ODOMETER, SignalValue.Odometer(m, t.toLong()), Ns.sec(t))
    fun navi(t: Double, g: NaviGuidance) = SignalEvent(Source.NAVI, SignalValue.Navi(g), Ns.sec(t))

    /**
     * 주행 한 토막: t0부터 steady초 동안 1초 간격 신호, 그 뒤 brakeAt에서 brakeFor초 동안 브레이크.
     * 조향 steer, 내비 g. 이벤트는 0.5초 간격으로 촘촘히(공백 규칙 2초 안 걸리게).
     */
    fun drive(t0: Double, dur: Double, brakeAt: Double, brakeFor: Double, steer: Double, g: NaviGuidance): List<SignalEvent> {
        val out = ArrayList<SignalEvent>()
        var t = t0; var m = 1000.0 * t0
        out += navi(t0, g)
        while (t <= t0 + dur) {
            val braking = t >= brakeAt && t < brakeAt + brakeFor
            out += steer(t, steer)
            out += brake(t, braking)
            m += 10.0; out += odo(t, m)
            t += 0.5
        }
        return out
    }
}
