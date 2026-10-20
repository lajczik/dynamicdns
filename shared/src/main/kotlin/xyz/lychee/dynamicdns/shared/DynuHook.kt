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
    var dynuConfig: DynuConfig
) {
    var port: Int = 0
    var domain: String? = null

    companion object {
        private const val API_BASE = "https://api.dynu.com/v2"
        private const val JSON_CONTENT = "application/json"
    }

    constructor(logger: Logger, config: YamlDocument) : this(
        logger,
        DynuConfig(
            config.getBoolean("dynu.enabled", false),
            config.getString("dynu.domain", ""),
            config.getString("dynu.client", ""),
            config.getString("dynu.secret", "")
        )
    )

    fun load(address: InetSocketAddress) {
        this.domain = this.dynuConfig.domain
        this.update(address)
    }

    fun update(address: InetSocketAddress): Boolean {
        return try {
            val credentials = "${this.dynuConfig.client}:${this.dynuConfig.secret}"
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

    private fun updateDNS(token: String, domainId: String, dnsRecordId: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/dns/$domainId/record/$dnsRecordId").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty("Content-Type", JSON_CONTENT)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val payload = """
                {
                    "nodeName": "",
                    "recordType": "SRV",
                    "ttl": 300,
                    "state": true,
                    "group": "",
                    "host": "$domain",
                    "priority": 0,
                    "weight": 5,
                    "port": $port
                }
            """.trimIndent()

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            logger.severe("Failed to update DNS: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun updateIP(token: String, domainId: String, ipAddress: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create("$API_BASE/dns/$domainId").toURL()
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", JSON_CONTENT)
            conn.setRequestProperty("Content-Type", JSON_CONTENT)
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val payload = """
                {
                    "name": "$domain",
                    "group": "",
                    "ipv4Address": "$ipAddress",
                    "ttl": 300,
                    "ipv4": true,
                    "ipv6": false,
                    "ipv6Address": null
                }
            """.trimIndent()

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            logger.severe("Failed to update IP: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
