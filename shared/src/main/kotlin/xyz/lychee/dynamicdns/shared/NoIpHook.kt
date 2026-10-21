package xyz.lychee.dynamicdns.shared

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.logging.Logger

data class NoIpConfig(
    val enabled: Boolean = false,
    val domain: String = "",
    val username: String = "",
    val password: String = ""
)

class NoIpHook(
    val logger: Logger,
    var config: NoIpConfig
) {

    fun load(ip: InetAddress): Boolean {
        if (!config.enabled) return false
        if (config.domain.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            logger.warning("[DynamicDNS] No-IP config is missing domain, username, or password!")
            return false
        }
        return update(ip)
    }

    fun update(ip: InetAddress): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val uri = URI.create("https://dynupdate.no-ip.com/nic/update?hostname=${config.domain}&myip=${ip.hostAddress}")
            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            val auth = "${config.username}:${config.password}"
            val encodedAuth = Base64.getEncoder().encodeToString(auth.toByteArray(StandardCharsets.UTF_8))
            conn.setRequestProperty("Authorization", "Basic $encodedAuth")
            conn.setRequestProperty("User-Agent", "DynamicDNS/2.0 (contact@lychee.xyz)")

            val code = conn.responseCode
            val response = if (code in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText().trim() }
            } else ""

            if (response.startsWith("good", ignoreCase = true) || response.startsWith("nochg", ignoreCase = true)) {
                logger.info("[DynamicDNS] No-IP domain ${config.domain} updated to ${ip.hostAddress} ($response)!")
                true
            } else {
                logger.warning("[DynamicDNS] No-IP update failed with response: '$response' (HTTP $code)")
                false
            }
        } catch (e: Exception) {
            logger.warning("[DynamicDNS] Error updating No-IP: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
