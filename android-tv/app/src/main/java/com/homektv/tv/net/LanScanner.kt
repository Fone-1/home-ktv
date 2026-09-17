package com.homektv.tv.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 局域网服务端自动扫描（替代手输 IP）。
 *
 * 主动 HTTP 子网探测：不依赖组播/mDNS，
 * 根据本机实际子网掩码动态计算探测 IP 范围（支持 /24、/23 等，最大收敛 1024 台），
 * 优先探测历史设备和默认 8080 端口，发现首个可用服务即回调，后台继续并发扫描剩余目标。
 *
 * Advantage: independent of multicast and AP-isolation policies. Limitation:
 * dynamically calculates subnet from prefix length with bounded concurrency.
 */
class LanScanner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 扫描本机所在网段的所有候选端口，并逐台报告命中。 */
    suspend fun scanAll(
        prioritizedHosts: List<String> = emptyList(),
        onProgress: ((scanned: Int, total: Int) -> Unit)? = null,
        onFound: ((hostPort: String) -> Unit)? = null,
    ): List<String> =
        coroutineScope {
            val ips = localSubnetIps()
            val targets = buildList {
                // 优先历史设备
                addAll(prioritizedHosts.filter { it.isNotBlank() })
                // 生成子网探测目标（优先默认 8080 端口）
                addAll(scanTargets(ips))
            }.distinct()

            val total = targets.size
            val counter = java.util.concurrent.atomic.AtomicInteger(0)
            val found = ConcurrentHashMap.newKeySet<String>()
            for (batch in targets.chunked(MAX_CONCURRENT_PROBES)) {
                batch.map { hostPort ->
                    async(Dispatchers.IO) {
                        if (validate(hostPort) && found.add(hostPort)) {
                            onFound?.invoke(hostPort)
                        }
                        onProgress?.invoke(counter.incrementAndGet(), total)
                    }
                }.awaitAll()
            }
            targets.filter(found::contains)
        }

    internal fun scanTargets(ips: List<String>): List<String> = CANDIDATE_PORTS.flatMap { port ->
        ips.map { ip -> "$ip:$port" }
    }

    /** 单地址探测：GET http://host:port/api/health，body 含 "home-ktv" 即命中。 */
    suspend fun validate(hostPort: String, scheme: String = "http"): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS + 300) {
            try {
                val req = Request.Builder()
                    .url("$scheme://$hostPort/api/health")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    resp.isSuccessful && (resp.body?.string()?.contains("home-ktv") == true)
                }
            } catch (_: Exception) {
                if (scheme == "http" && (hostPort.endsWith(":443") || hostPort.endsWith(":8443"))) {
                    try {
                        val req = Request.Builder()
                            .url("https://$hostPort/api/health")
                            .get()
                            .build()
                        client.newCall(req).execute().use { resp ->
                            resp.isSuccessful && (resp.body?.string()?.contains("home-ktv") == true)
                        }
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
            }
        } ?: false
    }

    /**
     * 根据网络接口实际子网掩码计算局域网主机 IP 列表。
     * 为避免超大子网（如 /16）探测耗时失控，将最大扫描规模限制在当前主机周围最多 1024 台（相当于 /22）。
     */
    internal fun localSubnetIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    val addr = ifaceAddr.address
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val prefixLength = ifaceAddr.networkPrefixLength.toInt().coerceIn(16, 30)
                        val ipBytes = addr.address
                        val ipInt = ((ipBytes[0].toInt() and 0xFF) shl 24) or
                                ((ipBytes[1].toInt() and 0xFF) shl 16) or
                                ((ipBytes[2].toInt() and 0xFF) shl 8) or
                                (ipBytes[3].toInt() and 0xFF)

                        // 限制最大扫描范围为 1024 台主机（前缀不低于 22）
                        val effectivePrefix = prefixLength.coerceAtLeast(22)
                        val mask = if (effectivePrefix == 0) 0 else (-1 shl (32 - effectivePrefix))
                        val network = ipInt and mask
                        val broadcast = network or mask.inv()

                        val start = network + 1
                        val end = broadcast - 1
                        for (current in start..end) {
                            val b1 = (current ushr 24) and 0xFF
                            val b2 = (current ushr 16) and 0xFF
                            val b3 = (current ushr 8) and 0xFF
                            val b4 = current and 0xFF
                            ips.add("$b1.$b2.$b3.$b4")
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return ips.distinct()
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 300L
        private const val MAX_CONCURRENT_PROBES = 64
        // 8080 作为 Home KTV 默认端口排在第一位，优先极速命中
        internal val CANDIDATE_PORTS = listOf(8080, 80, 8000, 8081, 8090, 8888, 9000, 9090)
    }
}
