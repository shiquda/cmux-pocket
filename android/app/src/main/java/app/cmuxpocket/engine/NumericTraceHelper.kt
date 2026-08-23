package app.cmuxpocket.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object NumericTraceHelper {

    private const val TAG = "CmuxTrace"
    private val surfaceOrdinals = ConcurrentHashMap<String, Int>()
    private val ordinalCounter = AtomicInteger(1)

    // Optional sink for pure unit tests
    var testSink: ((String) -> Unit)? = null

    fun getSurfaceOrdinal(surfaceId: String): Int {
        if (surfaceId.isEmpty()) return 0
        return surfaceOrdinals.computeIfAbsent(surfaceId) {
            ordinalCounter.getAndIncrement()
        }
    }

    fun clearOrdinals() {
        surfaceOrdinals.clear()
        ordinalCounter.set(1)
    }

    fun logReceive(
        traceId: Long,
        surfaceId: String,
        stateSeq: Long,
        full: Boolean,
        receivedNanos: Long
    ) {
        val surfOrd = getSurfaceOrdinal(surfaceId)
        val fullFlag = if (full) 1 else 0
        val msg = "RECV trace_id=$traceId surf_ord=$surfOrd seq=$stateSeq full=$fullFlag t_recv_ns=$receivedNanos"
        logInternal(msg)
    }

    fun logDecode(
        traceId: Long,
        surfaceId: String,
        stateSeq: Long,
        receivedNanos: Long,
        decodedNanos: Long
    ) {
        val surfOrd = getSurfaceOrdinal(surfaceId)
        val deltaUs = (decodedNanos - receivedNanos) / 1_000
        val msg = "DECODE trace_id=$traceId surf_ord=$surfOrd seq=$stateSeq t_recv_ns=$receivedNanos t_decode_ns=$decodedNanos delta_decode_us=$deltaUs"
        logInternal(msg)
    }

    fun logApply(
        traceId: Long,
        surfaceId: String,
        stateSeq: Long,
        result: FrameApplyResult,
        receivedNanos: Long,
        appliedNanos: Long
    ) {
        val surfOrd = getSurfaceOrdinal(surfaceId)
        val deltaUs = if (receivedNanos > 0) (appliedNanos - receivedNanos) / 1_000 else 0
        val msg = "APPLY trace_id=$traceId surf_ord=$surfOrd seq=$stateSeq result=${result.name} t_recv_ns=$receivedNanos t_apply_ns=$appliedNanos delta_apply_us=$deltaUs"
        logInternal(msg)
    }

    fun logDraw(
        traceId: Long,
        surfaceId: String,
        stateSeq: Long,
        columns: Int,
        rows: Int,
        receivedNanos: Long,
        drawNanos: Long
    ) {
        val surfOrd = getSurfaceOrdinal(surfaceId)
        val deltaUs = if (receivedNanos > 0) (drawNanos - receivedNanos) / 1_000 else 0
        val msg = "DRAW trace_id=$traceId surf_ord=$surfOrd seq=$stateSeq cols=$columns rows=$rows t_recv_ns=$receivedNanos t_draw_ns=$drawNanos delta_draw_us=$deltaUs"
        logInternal(msg)
    }

    private fun logInternal(message: String) {
        testSink?.invoke(message)
        try {
            if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                android.util.Log.d(TAG, message)
            }
        } catch (_: Throwable) {
            // JVM test fallback where android.util.Log is unmocked
        }
    }
}
