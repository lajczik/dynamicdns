package xyz.lychee.dynamicdns.shared

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

data class CloudflareConfig(
    val enabled: Boolean = false,
    val apiToken: String = "",
    val zoneId: String = "",
    val domain: String = "",
    val updateIpv4: Boolean = true,
    val updateIpv6: Boolean = false,
    val proxied: Boolean = false
)

class CloudflareHook(
    val logger: Logger,
    var config: CloudflareConfig
) {
    companion object {
        private const val API_BASE = "https://api.cloudflare.com/client/v4"
    }

    fun load(address: InetAddress, ipv6: InetAddress? = null): Boolean {
        if (!config.enabled) return false
        if (config.apiToken.isBlank() || config.zoneId.isBlank() || config.domain.isBlank()) {
            logger.warning("[DynamicDNS] Cloudflare config is missing api_token, zone_id, or domain!")
            return false
        }
        return update(address, ipv6)
    }

    fun update(ipv4: InetAddress, ipv6: InetAddress? = null): Boolean {
        var success = true
        if (config.updateIpv4) {
            val ok = updateRecord("A", ipv4.hostAddress)
            if (!ok) success = false
        }
        if (config.updateIpv6 && ipv6 != null) {
            val ok = updateRecord("AAAA", ipv6.hostAddress)
            if (!ok) success = false
        }
        return success
    }

    private fun updateRecord(type: String, ipContent: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val existingId = findRecordId(type)
            val uri = if (existingId != null) {
                URI.create("$API_BASE/zones/${config.zoneId}/dns_records/$existingId")
            } else {
                URI.create("$API_BASE/zones/${config.zoneId}/dns_records")
            }

            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = if (existingId != null) "PATCH" else "POST"
            conn.setRequestProperty("Authorization", "Bearer ${config.apiToken}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "DynamicDNS/2.0 (contact@lychee.xyz)")
            conn.doOutput = true

            val payload = """{"type":"$type","name":"${config.domain}","content":"$ipContent","ttl":1,"proxied":${config.proxied}}"""
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                it.write(payload)
                it.flush()
            }

            val code = conn.responseCode
            val isSuccess = code in 200..299
            if (isSuccess) {
                logger.info("[DynamicDNS] Cloudflare $type record for ${config.domain} updated to $ipContent!")
            } else {
                val errorMsg = BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
                logger.warning("[DynamicDNS] Failed to update Cloudflare $type record (HTTP $code): $errorMsg")
            }
            isSuccess
        } catch (e: Exception) {
            logger.warning("[DynamicDNS] Error updating Cloudflare record: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun findRecordId(type: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val uri = URI.create("$API_BASE/zones/${config.zoneId}/dns_records?name=${config.domain}&type=$type")
            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${config.apiToken}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "DynamicDNS/2.0 (contact@lychee.xyz)")

            if (conn.responseCode !in 200..299) return null

            val response = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            val match = Regex(""""id"\s*:\s*"([a-f0-9]{32})"""").find(response)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
