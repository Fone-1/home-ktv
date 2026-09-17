package com.homektv.tv.net

import kotlinx.serialization.Serializable

/** A remembered server connection. / 已保存的服务端连接。 */
@Serializable
data class SavedServer(
    val hostPort: String,
    val name: String,
    val scheme: String = "http",
) {
    /** 完整的服务基址，例如 http://192.168.1.10:8080 或 https://nas.home:8443 */
    val baseUrl: String get() = "$scheme://$hostPort"
}
