package ai.chagok.core.buffer

import ai.chagok.core.model.Ns
import ai.chagok.core.model.SignalEvent
import ai.chagok.core.model.Source

/**
 * 시간 기준 링 버퍼. 메모리에만 있고, 저장되는 것은 Capture뿐이다.
 * 창에서 밀려난 이벤트는 소스별 마지막 값만 남긴다(evicted). 변화 시점에만 오는 소스(내비 안내 유형 등)는
 * 마지막 이벤트가 창 밖에 있을 수 있어서, 이게 없으면 창 안에서 그 소스의 '현재 값'을 알 수 없다.
 */
/** maxEvents: 메모리 상한. 시간 창 안이라도 이 수를 넘으면 오래된 것부터 버린다(이벤트 폭주 대비). */
class TimedRing(private val windowNs: Long, private val maxEvents: Int = 100_000) {
    private val buf = ArrayDeque<SignalEvent>()
    private val evicted = HashMap<Source, SignalEvent>()
    val size: Int get() = buf.size

    fun append(e: SignalEvent) {
        buf.addLast(e)
        val cutoff = e.tsMonoNs - windowNs
        while (buf.isNotEmpty() && (buf.first().tsMonoNs < cutoff || buf.size > maxEvents)) {
            val old = buf.removeFirst()
            evicted[old.source] = old
        }
    }
    fun slice(fromNs: Long, toNs: Long): List<SignalEvent> = buf.filter { it.tsMonoNs in fromNs..toNs }

    /** fromNs 직전의 소스별 마지막 이벤트. 창 안에 그 소스가 없어도 상태를 이어 갈 수 있게 한다. */
    fun baselineBefore(fromNs: Long): List<SignalEvent> {
        val latest = HashMap(evicted)
        for (e in buf) {
            if (e.tsMonoNs >= fromNs) continue
            val cur = latest[e.source]
            if (cur == null || e.tsMonoNs >= cur.tsMonoNs) latest[e.source] = e
        }
        return latest.values.sortedWith(compareBy({ it.tsMonoNs }, { it.seq }))
    }
    fun oldestTs(): Long? = buf.firstOrNull()?.tsMonoNs
    fun newestTs(): Long? = buf.maxOfOrNull { it.tsMonoNs }
    fun clear() { buf.clear(); evicted.clear() }
}

/**
 * 정식 기록으로 남는 30초. 소리 원음 없음.
 * baseline: 창 시작 직전의 소스별 마지막 값. 특징값 추출은 이 값으로 상태를 시작한 뒤 events를 흘린다.
 */
data class Capture(
    val triggerTsNs: Long,
    val events: List<SignalEvent>,
    val preNs: Long,
    val postNs: Long,
    val complete: Boolean,
    val baseline: List<SignalEvent> = emptyList(),
) {
    val windowStartNs: Long get() = triggerTsNs - preNs
}

/**
 * 20초 핫 버퍼 + 10분 임시 버퍼. "기록해"가 오면 앞 20초를 고정하고 10초를 더 모은 뒤 Capture를 완성한다.
 * 음성 없는 모드는 정차 후 captureAt(ts)로 임시 버퍼에서 30초를 바로 잘라낸다.
 * 재시작하면 두 버퍼 모두 비어 있다(복구하지 않는다).
 */
class CaptureService(
    private val preNs: Long = Ns.sec(20.0),
    private val postNs: Long = Ns.sec(10.0),
    tempWindowNs: Long = Ns.sec(600.0),
    maxTempEvents: Int = 100_000,
) {
    private val temp = TimedRing(tempWindowNs, maxTempEvents)
    private val pending = ArrayList<Pending>()
    private class Pending(val triggerTs: Long, val frozen: MutableList<SignalEvent>, val baseline: List<SignalEvent>)

    fun onEvent(e: SignalEvent): List<Capture> {
        temp.append(e)
        val done = ArrayList<Capture>()
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (e.tsMonoNs <= p.triggerTs + postNs) p.frozen.add(e)
            else { done.add(Capture(p.triggerTs, p.frozen.toList(), preNs, postNs, complete = true, baseline = p.baseline)); iter.remove() }
        }
        return done
    }

    /**
     * 발화 시점에 호출. 앞 20초를 즉시 고정한다. 명령이 신호보다 늦게 처리될 수 있으므로(음성 인식 지연, 버스 밀림)
     * 이미 버퍼에 들어온 뒤 10초 구간도 함께 넣는다. 뒤 구간이 이미 다 있으면 바로 완성해 돌려주고, 아니면 pending으로 두고
     * onEvent가 post 구간을 넘길 때 완성한다(그때는 null).
     */
    fun capture(triggerTs: Long): Capture? {
        val from = triggerTs - preNs
        val frozen = temp.slice(from, triggerTs + postNs).toMutableList()
        val baseline = temp.baselineBefore(from)
        if ((temp.newestTs() ?: Long.MIN_VALUE) > triggerTs + postNs) return Capture(triggerTs, frozen, preNs, postNs, complete = true, baseline = baseline)
        pending.add(Pending(triggerTs, frozen, baseline)); return null
    }

    /**
     * 정차 후 시점 선택(또는 "느꼈어요"의 알림 시점). 임시 버퍼에 앞 20초가 남아 있으면 잘라낸다. 없으면 null.
     * 뒤 10초가 아직 다 쌓이지 않았으면 있는 만큼만 넣고 complete=false.
     */
    fun captureAt(ts: Long): Capture? {
        val oldest = temp.oldestTs() ?: return null
        val from = ts - preNs
        if (from < oldest) return null
        val complete = (temp.newestTs() ?: ts) >= ts + postNs
        return Capture(ts, temp.slice(from, ts + postNs), preNs, postNs, complete = complete, baseline = temp.baselineBefore(from))
    }

    /** 정차·앱 종료 등으로 post 구간을 못 채울 때 지금까지로 마감. */
    fun flush(nowTs: Long): List<Capture> {
        val out = pending.map { Capture(it.triggerTs, it.frozen.toList(), preNs, postNs, complete = nowTs >= it.triggerTs + postNs, baseline = it.baseline) }
        pending.clear(); return out
    }

    /**
     * 임시 데이터 폐기 (정차 후 미선택, 재시작). 임시 10분 버퍼만 버린다.
     * 이미 "기록해"로 고정된 Capture는 정식 기록이므로 버리지 않고 지금까지로 마감해 돌려준다(complete=false).
     */
    fun discardTemp(): List<Capture> {
        val out = pending.map { Capture(it.triggerTs, it.frozen.toList(), preNs, postNs, complete = false, baseline = it.baseline) }
        temp.clear(); pending.clear(); return out
    }
}
