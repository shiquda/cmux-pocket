package app.cmuxpocket.protocol

/** Compatibility between this Android client and the connected Pocket Gateway. */
enum class GatewayCompatibility {
    MATCHED,
    GATEWAY_OUTDATED,
    CLIENT_OUTDATED,
    INCOMPATIBLE,
    UNKNOWN;

    val isProblem: Boolean
        get() = this == GATEWAY_OUTDATED || this == CLIENT_OUTDATED || this == INCOMPATIBLE
}

/** Host values reported by `mobile.host.status`, normalized for the UI. */
data class GatewayHostStatus(
    val gatewayVersion: String? = null,
    val protocolVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val cmuxInstalled: Boolean = false,
    /** Older Gateways omit cmux state; assume running so no wake card is shown. */
    val cmuxRunning: Boolean = true,
    val canWake: Boolean = false,
) {
    val supportsWake: Boolean get() = canWake || GatewayVersions.WAKE_CAPABILITY in capabilities
}

object GatewayVersions {
    const val EXPECTED_PROTOCOL_MAJOR = 2
    const val WAKE_CAPABILITY = "mobile.host.wake_cmux.v1"

    /** Package version of the Gateway that introduced remote wake and version reporting. */
    const val MIN_GATEWAY_VERSION = "0.2.0"

    /**
     * Classifies the connection from the client's point of view.
     *
     * Missing fields mean an older Gateway: it cannot wake cmux, so it is outdated.
     * A protocol major mismatch blocks workspace sync entirely.
     */
    fun evaluate(clientVersion: String, host: GatewayHostStatus?): GatewayCompatibility {
        if (host == null) return GatewayCompatibility.UNKNOWN

        // A Gateway that reports no version at all (including legacy or malformed
        // responses) cannot be classified, so do not claim it is outdated.
        if (host.gatewayVersion == null && host.protocolVersion == null) {
            return GatewayCompatibility.UNKNOWN
        }

        val protocolMajor = majorOf(host.protocolVersion)
        if (protocolMajor != null && protocolMajor != EXPECTED_PROTOCOL_MAJOR) {
            return GatewayCompatibility.INCOMPATIBLE
        }

        if (!host.supportsWake) return GatewayCompatibility.GATEWAY_OUTDATED

        val gatewayVersion = host.gatewayVersion
            ?: host.protocolVersion.takeIf { majorOf(it) == EXPECTED_PROTOCOL_MAJOR }
            ?: return GatewayCompatibility.UNKNOWN

        return when (compareVersions(gatewayVersion, clientVersion)) {
            1 -> GatewayCompatibility.CLIENT_OUTDATED
            -1 -> GatewayCompatibility.GATEWAY_OUTDATED
            else -> GatewayCompatibility.MATCHED
        }
    }

    /** Integer major component of a dotted version, or null when unparseable. */
    fun majorOf(version: String?): Int? =
        version?.trim()?.substringBefore('.')?.toIntOrNull()

    /**
     * Numeric component-wise semver comparison; missing components count as zero and
     * pre-release suffixes are ignored (compatibility, not release ordering).
     */
    fun compareVersions(left: String, right: String): Int {
        val leftParts = numericParts(left)
        val rightParts = numericParts(right)
        val size = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until size) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return if (l > r) 1 else -1
        }
        return 0
    }

    private fun numericParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
