package app.cmuxpocket.ui

/** Bounded retry schedule for transient Gateway/network disconnects. */
object ReconnectPolicy {
    const val maxAttempts = 6

    fun delayMillis(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be non-negative" }
        return when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            4 -> 15_000L
            else -> 30_000L
        }
    }
}
