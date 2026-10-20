package xyz.lychee.dynamicdns.shared

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.block.implementation.Section
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class NgrokConfig(
    val enabled: Boolean = false,
    val token: String = "",
    val region: String = "US"
)

data class DynuConfig(
    val enabled: Boolean = false,
    val domain: String = "",
    val client: String = "",
    val secret: String = ""
)

data class ServerSyncConfig(
    val enabled: Boolean = false,
    val token: String = "",
    val name: String = "",
    val targetAddress: String = "",
    val interval: Int = 30
)

class DynamicDnsConfig(
    val yamlDocument: YamlDocument,
    val isProxy: Boolean = false
) {
    @Volatile
    var debug: Boolean = false
        private set

    @Volatile
    var ngrok: NgrokConfig = NgrokConfig()
        private set

    @Volatile
    var dynu: DynuConfig = DynuConfig()
        private set

    @Volatile
    var serverSync: ServerSyncConfig = ServerSyncConfig()
        private set

    val servers: MutableMap<String, InetSocketAddress> = ConcurrentHashMap()

    init {
        reload()
    }

    @Synchronized
    fun reload() {
        yamlDocument.reload()

        this.debug = yamlDocument.getBoolean("debug", false)

        val ngrokEnabled = yamlDocument.getBoolean("ngrok.enabled", false)
        val ngrokToken = yamlDocument.getString("ngrok.token") ?: ""
        val ngrokRegion = yamlDocument.getString("ngrok.region") ?: "US"
        this.ngrok = NgrokConfig(
            enabled = ngrokEnabled,
            token = ngrokToken,
            region = ngrokRegion
        )

        val dynuEnabled = yamlDocument.getBoolean("dynu.enabled", false)
        val dynuDomain = yamlDocument.getString("dynu.domain") ?: ""
        val dynuClient = yamlDocument.getString("dynu.client") ?: ""
        val dynuSecret = yamlDocument.getString("dynu.secret") ?: ""
        this.dynu = DynuConfig(
            enabled = dynuEnabled,
            domain = dynuDomain,
            client = dynuClient,
            secret = dynuSecret
        )

        val syncEnabled = if (yamlDocument.contains("server_sync.enabled")) {
            yamlDocument.getBoolean("server_sync.enabled", isProxy)
        } else if (yamlDocument.contains("socket_server.enabled")) {
            yamlDocument.getBoolean("socket_server.enabled", true)
        } else if (yamlDocument.contains("socket_client.enabled")) {
            yamlDocument.getBoolean("socket_client.enabled", false)
        } else {
            isProxy
        }

        val syncToken = yamlDocument.getString("server_sync.token")
            ?: yamlDocument.getString("socket_server.token")
            ?: yamlDocument.getString("socket_client.token")
            ?: ""

        val syncName = yamlDocument.getString("server_sync.name")
            ?: yamlDocument.getString("socket_client.name")
            ?: ""

        val syncTargetAddress = yamlDocument.getString("server_sync.target_address")
            ?: yamlDocument.getString("socket_client.target_address")
            ?: ""

        val syncInterval = if (yamlDocument.contains("server_sync.interval")) {
            yamlDocument.getInt("server_sync.interval", 30)
        } else if (yamlDocument.contains("socket_client.interval")) {
            yamlDocument.getInt("socket_client.interval", 30)
        } else {
            30
        }

        this.serverSync = ServerSyncConfig(
            enabled = syncEnabled,
            token = syncToken,
            name = syncName,
            targetAddress = syncTargetAddress,
            interval = syncInterval
        )

        this.servers.clear()
        if (yamlDocument.contains("servers")) {
            if (yamlDocument.isSection("servers")) {
                val section: Section? = yamlDocument.getSection("servers")
                if (section != null) {
                    for (key in section.keys) {
                        val serverName = key.toString()
                        val addressStr = section.getString(serverName)
                        if (!addressStr.isNullOrBlank()) {
                            this.servers[serverName] = AddressUtil.parseAddress(addressStr)
                        }
                    }
                }
            } else if (yamlDocument.isList("servers")) {
                val serversList = yamlDocument.getStringList("servers")
                if (!serversList.isNullOrEmpty()) {
                    for (server in serversList) {
                        val split = server.split("-")
                        if (split.size >= 2) {
                            this.servers[split[0]] = AddressUtil.parseAddress(split[1])
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun ensureProxySyncToken(): String {
        var token = this.serverSync.token
        if (token.isBlank()) {
            token = UUID.randomUUID().toString()
            yamlDocument.set("server_sync.token", token)
            yamlDocument.save()
            this.serverSync = this.serverSync.copy(token = token)
        }
        return token
    }

    @Synchronized
    fun saveServers() {
        this.servers.forEach { (name, address) ->
            yamlDocument.set("servers.$name", "${address.hostString}:${address.port}")
        }
        yamlDocument.save()
    }
}
