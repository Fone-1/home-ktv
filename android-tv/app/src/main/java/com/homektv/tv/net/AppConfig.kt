package com.homektv.tv.net

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * NAS 服务端地址与历史连接持久化。
 *
 * Persists the NAS server address and the history of successful connections.
 */
class AppConfig(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ktv_tv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateLegacyServer()
    }

    /** 形如 192.168.1.10:8080 的服务端 host:port（已归一化）。未配置时为 null。 */
    var serverHost: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    /** 服务端协议：http 或 https（默认 http，严禁把用户配置的 https 降级为 http）。 */
    var serverScheme: String
        get() = prefs.getString(KEY_SCHEME, "http") ?: "http"
        set(value) = prefs.edit { putString(KEY_SCHEME, value.lowercase()) }

    val isConfigured: Boolean get() = !serverHost.isNullOrBlank()

    val savedServers: List<SavedServer>
        get() = readSavedServers()

    /**
     * 连接成功后去重置顶，最多保留 10 台设备。
     *
     * Deduplicates and promotes a successful connection, keeping at most 10 devices.
     */
    @Synchronized
    fun rememberServer(server: SavedServer) {
        val parsed = parseServer(server.hostPort, server.scheme) ?: return
        val hostPort = parsed.hostPort
        val scheme = parsed.scheme
        val existing = readSavedServers().firstOrNull { it.hostPort == hostPort }
        val incomingName = server.name.trim()
        val name = when {
            incomingName.isNotEmpty() && incomingName != hostPort -> incomingName
            existing != null -> existing.name
            else -> hostPort
        }
        val updated = buildList {
            add(SavedServer(hostPort, name, scheme))
            addAll(readSavedServers().filterNot { it.hostPort == hostPort })
        }.take(MAX_SAVED_SERVERS)
        writeSavedServers(updated)
        serverHost = hostPort
        serverScheme = scheme
    }

    @Synchronized
    fun removeSavedServer(hostPort: String) {
        writeSavedServers(readSavedServers().filterNot { it.hostPort == hostPort })
    }

    var microphoneMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICROPHONE_MONITOR, true)
        set(value) = prefs.edit { putBoolean(KEY_MICROPHONE_MONITOR, value) }

    /** WebSocket 地址：http 对应 ws，https 对应 wss */
    fun wsUrl(clientToken: String): String {
        val wsScheme = if ("https".equals(serverScheme, ignoreCase = true)) "wss" else "ws"
        return "$wsScheme://${serverHost}/ws?client_type=tv&client_token=$clientToken"
    }

    /** REST/资源基址：保留真实 scheme */
    fun apiBase(): String = "$serverScheme://${serverHost}/api"

    /** H5 点歌地址（用于待机页二维码/明文兜底）：保留真实 scheme */
    fun h5Url(): String = "$serverScheme://${serverHost}/m"

    /** 稳定的设备 token（首次生成后固定），用于 WS client_token。 */
    val clientToken: String
        get() = prefs.getString(KEY_TOKEN, null) ?: run {
            val t = "tv-" + java.util.UUID.randomUUID().toString().take(8)
            prefs.edit { putString(KEY_TOKEN, t) }
            t
        }

    private fun migrateLegacyServer() {
        if (prefs.contains(KEY_SAVED_SERVERS)) return
        val legacyHost = prefs.getString(KEY_HOST, null)
        val initial = legacyHost?.takeIf { it.isNotBlank() }
            ?.let { listOf(SavedServer(it, it)) }
            .orEmpty()
        writeSavedServers(initial)
    }

    private fun readSavedServers(): List<SavedServer> {
        val raw = prefs.getString(KEY_SAVED_SERVERS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedServer.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeSavedServers(servers: List<SavedServer>) {
        val raw = json.encodeToString(ListSerializer(SavedServer.serializer()), servers)
        prefs.edit { putString(KEY_SAVED_SERVERS, raw) }
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_SCHEME = "server_scheme"
        private const val KEY_TOKEN = "client_token"
        private const val KEY_MICROPHONE_MONITOR = "microphone_monitor_enabled"
        private const val KEY_SAVED_SERVERS = "saved_servers"
        private const val MAX_SAVED_SERVERS = 10

        /**
         * 归一化用户输入：去空格、剥离 http(s):// 前缀与尾部斜杠；
         * 未带端口时补默认 8080；手动输入支持任意有效服务端端口。
         */
        fun normalizeHost(raw: String): String? {
            return parseServer(raw)?.hostPort
        }

        /**
         * 解析完整的服务端地址：
         * 1. 自动提取 http / https 协议；禁止将 https 降级为 http；
         * 2. IPv6 字面量规范化包裹方括号；
         * 3. 未带端口时根据协议自动补全（https:443, http:8080）。
         */
        fun parseServer(raw: String, fallbackScheme: String = "http"): SavedServer? {
            var s = raw.trim()
            if (s.isEmpty()) return null

            var scheme = fallbackScheme.lowercase()
            if (s.startsWith("https://", ignoreCase = true)) {
                scheme = "https"
                s = s.substring(8)
            } else if (s.startsWith("http://", ignoreCase = true)) {
                scheme = "http"
                s = s.substring(7)
            }

            s = s.substringBefore("/") // 去掉路径部分
            if (s.isEmpty()) return null

            val hostPort: String
            if (s.startsWith("[")) {
                // 已带方括号的 IPv6 地址，如 [2001:db8::1] 或 [2001:db8::1]:8080
                val closing = s.indexOf(']')
                if (closing < 0) return null
                val hostPart = s.substring(0, closing + 1)
                val portPart = s.substring(closing + 1)
                hostPort = if (portPart.startsWith(":")) {
                    "$hostPart$portPart"
                } else {
                    val defaultPort = if (scheme == "https") 443 else 8080
                    "$hostPart:$defaultPort"
                }
            } else if (s.count { it == ':' } > 1) {
                // 未带方括号的纯 IPv6 字面量，如 2001:db8::1
                val defaultPort = if (scheme == "https") 443 else 8080
                hostPort = "[$s]:$defaultPort"
            } else {
                // IPv4 或 域名
                hostPort = if (!s.contains(":")) {
                    val defaultPort = if (scheme == "https") 443 else 8080
                    "$s:$defaultPort"
                } else {
                    s
                }
            }

            return SavedServer(hostPort = hostPort, name = hostPort, scheme = scheme)
        }
    }
}
