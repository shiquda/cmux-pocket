package app.cmuxpocket.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

object LanGatewayScanner {
    val DEFAULT_PORTS = intArrayOf(8088)

    suspend fun scan(
        extraPorts: Collection<Int> = emptyList(),
        timeoutMs: Int = 180
    ): List<DiscoveredGateway> = withContext(Dispatchers.IO) {
        val ports = (DEFAULT_PORTS.toList() + extraPorts)
            .map { it.coerceIn(1, 65535) }
            .distinct()
            .sorted()

        val localIpv4 = localIpv4Addresses()
        val hosts = linkedSetOf<String>()
        hosts.add("127.0.0.1")
        localIpv4.forEach { address ->
            hosts.add(address)
            hosts.addAll(subnetHosts(address))
        }

        val semaphore = Semaphore(64)
        coroutineScope {
            hosts.flatMap { host ->
                ports.map { port ->
                    async {
                        semaphore.withPermit {
                            probe(host, port, timeoutMs)
                        }
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.latencyMs }
        }
    }

    fun localIpv4Addresses(): List<String> {
        val found = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress ?: continue
                    if (!host.startsWith("169.254.")) {
                        found.add(host)
                    }
                }
            }
        }
        return found.distinct()
    }

    private fun subnetHosts(ipv4: String): List<String> {
        val parts = ipv4.split(".")
        if (parts.size != 4) return emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        return (1..254).map { "$prefix.$it" }
    }

    private fun probe(host: String, port: Int, timeoutMs: Int): DiscoveredGateway? {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val latencyMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            val source = if (host == "127.0.0.1") "USB" else "LAN"
            DiscoveredGateway(host = host, port = port, latencyMs = latencyMs, source = source)
        } catch (_: Exception) {
            null
        }
    }
}
