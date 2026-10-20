package xyz.lychee.dynamicdns.shared

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import kotlin.concurrent.thread

data class ModrinthVersion(
    val versionNumber: String,
    val name: String,
    val downloadUrl: String?
)

data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String?,
    val downloadUrl: String?,
    val projectUrl: String = ModrinthUpdater.PROJECT_URL
)

class ModrinthUpdater(
    private val logger: Logger,
    val currentVersion: String = CURRENT_VERSION,
    val projectSlug: String = PROJECT_SLUG
) {

    companion object {
        const val CURRENT_VERSION: String = "2.0"
        const val PROJECT_SLUG: String = "dynamicdns"
        const val PROJECT_URL: String = "https://modrinth.com/plugin/dynamicdns"
        const val API_URL: String = "https://api.modrinth.com/v2/project/dynamicdns/version"

        fun isNewer(current: String, remote: String): Boolean {
            val cleanCurrent = current.trim().removePrefix("v").removePrefix("V")
            val cleanRemote = remote.trim().removePrefix("v").removePrefix("V")
            if (cleanCurrent.equals(cleanRemote, ignoreCase = true)) return false

            val currentParts = cleanCurrent.split(Regex("[^0-9]+"))
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }
            val remoteParts = cleanRemote.split(Regex("[^0-9]+"))
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(currentParts.size, remoteParts.size)
            for (i in 0 until maxLen) {
                val cur = currentParts.getOrElse(i) { 0 }
                val rem = remoteParts.getOrElse(i) { 0 }
                if (rem > cur) return true
                if (rem < cur) return false
            }
            return false
        }

        fun parseFirstVersion(json: String): ModrinthVersion? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return null

            var depth = 0
            var startIdx = -1
            var endIdx = -1
            for (i in trimmed.indices) {
                val c = trimmed[i]
                if (c == '{') {
                    if (depth == 0) startIdx = i
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        endIdx = i
                        break
                    }
                }
            }
            if (startIdx == -1 || endIdx == -1) return null
            val firstObj = trimmed.substring(startIdx, endIdx + 1)

            val versionNumberMatch = Regex(""""version_number"\s*:\s*"([^"]+)"""").find(firstObj)
            val versionNumber = versionNumberMatch?.groupValues?.get(1) ?: return null

            val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(firstObj)
            val name = nameMatch?.groupValues?.get(1) ?: versionNumber

            val urlMatch = Regex(""""url"\s*:\s*"(https://[^"]+)"""").find(firstObj)
            val downloadUrl = urlMatch?.groupValues?.get(1)

            return ModrinthVersion(versionNumber, name, downloadUrl)
        }
    }

    @Volatile
    var lastResult: UpdateCheckResult? = null
        private set

    @Volatile
    private var lastCheckTime: Long = 0L

    fun checkForUpdates(debug: Boolean = false, force: Boolean = false): UpdateCheckResult? {
        val now = System.currentTimeMillis()
        if (!force && lastResult != null && (now - lastCheckTime) < 60_000L) {
            return lastResult
        }

        var conn: HttpURLConnection? = null
        return try {
            val uri = URI.create("https://api.modrinth.com/v2/project/$projectSlug/version")
            conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "lychee/dynamicdns/$currentVersion (contact@lychee.xyz)")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                if (debug) {
                    logger.warning("[DynamicDNS] Modrinth API returned response code $responseCode")
                }
                return null
            }

            val json = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use {
                it.readText()
            }

            val version = parseFirstVersion(json)
            if (version == null) {
                if (debug) {
                    logger.warning("[DynamicDNS] Unable to parse version info from Modrinth response.")
                }
                return null
            }

            val updateAvailable = isNewer(currentVersion, version.versionNumber)
            val result = UpdateCheckResult(
                isUpdateAvailable = updateAvailable,
                currentVersion = currentVersion,
                latestVersion = version.versionNumber,
                downloadUrl = version.downloadUrl,
                projectUrl = "https://modrinth.com/plugin/$projectSlug"
            )

            this.lastResult = result
            this.lastCheckTime = now
            result
        } catch (e: Exception) {
            if (debug) {
                logger.warning("[DynamicDNS] Could not check for updates: ${e.message}")
            }
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun checkForUpdatesAsync(
        debug: Boolean = false,
        force: Boolean = false,
        onResult: ((UpdateCheckResult) -> Unit)? = null
    ) {
        thread(isDaemon = true, name = "DynamicDNS-UpdateChecker") {
            try {
                val result = checkForUpdates(debug, force)
                if (result != null) {
                    if (result.isUpdateAvailable) {
                        logger.info("==================================================")
                        logger.info("[DynamicDNS] A new version of DynamicDNS is available: v${result.latestVersion} (Current: v$currentVersion)")
                        logger.info("[DynamicDNS] Download it at: ${result.projectUrl}")
                        logger.info("==================================================")
                    } else if (debug) {
                        logger.info("[DynamicDNS] You are running the latest version (v$currentVersion).")
                    }
                    onResult?.invoke(result)
                }
            } catch (t: Throwable) {
                if (debug) {
                    logger.warning("[DynamicDNS] Error in update checker thread: ${t.message}")
                }
            }
        }
    }
}
