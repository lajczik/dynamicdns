package xyz.lychee.dynamicdns.shared

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

data class DuckDnsConfig(
    val enabled: Boolean = false,
    val domain: String = "",
    val token: String = "",
    val updateIpv6: Boolean = false
)

class DuckDnsHook(
    val logger: Logger,
    var config: DuckDnsConfig
) {

    fun load(ipv4: InetAddress, ipv6: InetAddress? = null): Boolean {
        if (!config.enabled) return false
        if (config.domain.isBlank() || config.token.isBlank()) {
            logger.warning("[DynamicDNS] DuckDNS config is missing domain or token!")
            return false
        }
        return update(ipv4, ipv6)
    }

    fun update(ipv4: InetAddress, ipv6: InetAddress? = null): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val cleanDomain = config.domain.removeSuffix(".duckdns.org").trim()
            val urlBuilder = StringBuilder("https://www.duckdns.org/update?domains=$cleanDomain&token=${config.token}&ip=${ipv4.hostAddress}")
            if (config.updateIpv6 && ipv6 != null) {
                urlBuilder.append("&ipv6=${ipv6.hostAddress}")
            }

            val uri = URI.create(urlBuilder.toString())
            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "DynamicDNS/2.0 (contact@lychee.xyz)")

            val code = conn.responseCode
            val response = if (code in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText().trim() }
            } else ""

            if (response.equals("OK", ignoreCase = true)) {
                logger.info("[DynamicDNS] DuckDNS domain $cleanDomain.duckdns.org updated to ${ipv4.hostAddress}!")
                true
            } else {
                logger.warning("[DynamicDNS] DuckDNS update failed with response: '$response' (HTTP $code)")
                false
            }
        } catch (e: Exception) {
            logger.warning("[DynamicDNS] Error updating DuckDNS: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
