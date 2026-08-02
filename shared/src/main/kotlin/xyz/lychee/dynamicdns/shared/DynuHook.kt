package xyz.lychee.dynamicdns.shared

import dev.dejvokep.boostedyaml.YamlDocument
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.logging.Logger

class DynuHook(
    val logger: Logger,
    val config: YamlDocument
) {
    var port: Int = 0
    var domain: String? = null

    companion object {
        private const val API_BASE = "https://api.dynu.com/v2"
        private const val JSON_CONTENT = "application/json"
    }

    fun load(address: InetSocketAddress) {
        this.domain = this.config.getString("dynu.domain")
        this.update(address)
    }

    fun update(address: InetSocketAddress): Boolean {
        return try {
            val credentials = "${this.config.getString("dynu.client")}:${this.config.getString("dynu.secret")}"
            val token = getToken(credentials)

            if (token == null) {
                logger.severe("Failed to obtain authentication token")
                return false
            }

            val domainId = getDomainInfo(token)
            if (domainId == null) {
                logger.severe("Failed to retrieve domain information")
                return false
            }

            val dnsRecordId = getDNSRecords(token, domainId)
            if (dnsRecordId == null) {
                logger.severe("Failed to retrieve DNS records")
                return false
            }

            updateDNS(token, domainId, dnsRecordId) && updateIP(token, domainId, address.hostString)
        } catch (e: Exception) {
            logger.severe("Error during DNS update: ${e.message}")
            false
        }
    }

    private fun getToken(credentials: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/oauth2/token").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
            )

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                val response = reader.readLine()
                response.split("\"")[3]
            }
        } catch (e: Exception) {
            logger.severe("Failed to get token: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun getDomainInfo(token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/dns").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty("Authorization", "Bearer $token")

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                val response = reader.readLine()
                val parts = response.split("\"")
                this.domain = parts[9]
                parts[6].replace(":", "").replace(",", "")
            }
        } catch (e: Exception) {
            logger.severe("Failed to get domain info: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun getDNSRecords(token: String, domainId: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/dns/$domainId/record").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty("Authorization", "Bearer $token")

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                val response = reader.readLine()
                val records = response.split("\"")

                for (i in records.indices) {
                    if (records[i] == "recordType" && i + 2 < records.size && records[i + 2] == "SRV" && i - 15 >= 0) {
                        return@use records[i - 15].replace(Regex("[^0-9]"), "")
                    }
                }
                null
            }
        } catch (e: Exception) {
            logger.severe("Failed to get DNS records: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun updateIP(token: String, domainId: String, ip: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/dns/$domainId").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", JSON_CONTENT)
            conn.doOutput = true

            val jsonPayload = """
                {
                  "name": "$domain",
                  "group": "default",
                  "ipv4Address": "$ip",
                  "ttl": 120,
                  "ipv4": true,
                  "ipv6": false,
                  "ipv4WildcardAlias": false,
                  "ipv6WildcardAlias": false,
                  "allowZoneTransfer": false,
                  "dnssec": false
                }
            """.trimIndent()

            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(StandardCharsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                logger.info("Successfully updated IP address")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            logger.severe("Failed to update IP: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun updateDNS(token: String, domainId: String, dnsRecordId: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/dns/$domainId/record/$dnsRecordId").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", JSON_CONTENT)
            conn.doOutput = true

            val jsonPayload = """
                {
                  "nodeName": "_minecraft._tcp",
                  "recordType": "SRV",
                  "ttl": 120,
                  "state": true,
                  "group": "",
                  "host": "$domain",
                  "priority": 10,
                  "weight": 5,
                  "port": $port
                }
            """.trimIndent()

            conn.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(StandardCharsets.UTF_8))
            }

            conn.responseCode == 200
        } catch (e: Exception) {
            logger.severe("Failed to update DNS: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
