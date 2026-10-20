package xyz.lychee.dynamicdns.bungee

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.event.PlayerHandshakeEvent
import net.md_5.bungee.api.event.ProxyPingEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler
import org.bstats.bungeecord.Metrics
import xyz.lychee.dynamicdns.shared.AddressUtil
import xyz.lychee.dynamicdns.shared.DynamicDnsConfig
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.NgrokHook
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

class BungeeMain : Plugin(), Listener {
    var config: DynamicDnsConfig? = null
        private set

    val configDoc: YamlDocument?
        get() = config?.yamlDocument

    val servers: MutableMap<String, InetSocketAddress>
        get() = config?.servers ?: mutableMapOf()

    var metrics: Metrics? = null
        private set
    var ngrok: NgrokHook? = null
        private set
    var dynu: DynuHook? = null
        private set

    override fun onEnable() {
        this.metrics = Metrics(this, 27000)
        try {
            val yamlDoc = YamlDocument.create(
                File(this.dataFolder, "config.yml"),
                this.getResourceAsStream("config.yml"),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                DumperSettings.DEFAULT
            )
            this.config = DynamicDnsConfig(yamlDoc, isProxy = true)

            this.proxy.pluginManager.registerListener(this, this)
            this.proxy.pluginManager.registerCommand(this, DynamicDnsCommand(this))
            this.reload()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    override fun onDisable() {
        this.proxy.pluginManager.unregisterListener(this)
        this.metrics?.shutdown()
        this.ngrok?.unload()
    }

    @Throws(IOException::class)
    fun reload() {
        val cfg = this.config ?: return
        cfg.reload()

        val bind = this.proxy.config.listeners
            .firstOrNull()
            ?.let { it.socketAddress as? InetSocketAddress }
            ?: InetSocketAddress(InetAddress.getLoopbackAddress(), 25565)

        val ngrokHook = NgrokHook(this.logger, bind, cfg.ngrok)
        val dynuHook = DynuHook(this.logger, cfg.dynu)

        this.ngrok = ngrokHook
        this.dynu = dynuHook

        val address = if (cfg.ngrok.enabled) {
            AddressUtil.parseAddressFromUri(ngrokHook.load())
        } else {
            InetSocketAddress(AddressUtil.checkPublicIp(), bind.port)
        }

        if (cfg.dynu.enabled) {
            dynuHook.load(address)
        }

        this.loadServers()
    }

    fun loadServers() {
        val cfg = this.config ?: return
        cfg.ensureProxySyncToken()

        val current = proxy.servers
        this.servers.forEach { (name, address) ->
            val existing = current[name]
            if (existing == null || existing.socketAddress != address) {
                val newInfo = proxy.constructServerInfo(
                    name, address, "DynamicDNS loaded server", false
                )
                existing?.players?.forEach { p -> p.connect(newInfo) }
                current[name] = newInfo
                this.logger.info("Loaded server $name with address $address!")
            }
        }
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    fun onProxyPing(event: ProxyPingEvent) {
        val virtualHost = event.connection.virtualHost?.hostString ?: return
        if (!virtualHost.startsWith("sync.")) return

        // Drop the ping response and close the connection
        event.response = null
        try {
            event.connection.disconnect(TextComponent(""))
        } catch (ignored: Exception) {}

        handleSyncPayload(virtualHost, event.connection.socketAddress)
    }

    @EventHandler(priority = Byte.MIN_VALUE)
    fun onHandshake(event: PlayerHandshakeEvent) {
        val virtualHost = event.handshake?.host ?: event.connection.virtualHost?.hostString ?: return
        if (!virtualHost.startsWith("sync.")) return

        try {
            event.connection.disconnect(TextComponent(""))
        } catch (ignored: Exception) {}

        handleSyncPayload(virtualHost, event.connection.socketAddress)
    }

    private fun handleSyncPayload(virtualHost: String, remoteAddress: SocketAddress) {
        val cfg = this.config ?: return
        if (!cfg.serverSync.enabled) {
            return
        }

        val cleanHost = virtualHost.trimEnd('.')
        val parts = cleanHost.split(".", limit = 4)
        if (parts.size < 4 || parts[0] != "sync") {
            this.logger.warning("Invalid sync payload structure from $remoteAddress: $virtualHost")
            return
        }

        val receivedToken = parts[1]
        val serverName = parts[2]
        val targetPortOrIp = parts[3]

        val expectedToken = cfg.serverSync.token
        if (expectedToken.isBlank() || receivedToken != expectedToken) {
            this.logger.warning("Rejected sync update from $remoteAddress: invalid token!")
            return
        }

        val targetAddress: InetSocketAddress = if (targetPortOrIp.contains(":")) {
            val sub = targetPortOrIp.split(":", limit = 2)
            val port = sub[1].toIntOrNull()
            if (port == null) {
                this.logger.warning("Invalid port in sync address: $targetPortOrIp")
                return
            }
            InetSocketAddress(sub[0], port)
        } else {
            val port = targetPortOrIp.toIntOrNull()
            if (port != null) {
                val remoteIp = (remoteAddress as? InetSocketAddress)?.address?.hostAddress
                    ?: (remoteAddress as? InetSocketAddress)?.hostString
                    ?: "127.0.0.1"
                InetSocketAddress(remoteIp, port)
            } else {
                InetSocketAddress(targetPortOrIp, 25565)
            }
        }

        val current = proxy.servers
        val existing = current[serverName]
        if (existing != null && existing.socketAddress == targetAddress && servers[serverName] == targetAddress) {
            if (cfg.debug) {
                this.logger.info("Sync heartbeat received for $serverName ($targetAddress) - address unchanged")
            }
            return
        }

        val newInfo = proxy.constructServerInfo(
            serverName, targetAddress, "DynamicDNS updated server", false
        )
        existing?.players?.forEach { p -> p.connect(newInfo) }

        current[serverName] = newInfo
        servers[serverName] = targetAddress
        this.logger.info("Synchronized server $serverName with address $targetAddress (from $remoteAddress)")

        saveServers()
    }

    @Synchronized
    fun saveServers() {
        val cfg = this.config ?: return
        try {
            cfg.saveServers()
        } catch (e: Exception) {
            this.logger.warning("Failed to save servers to config: ${e.message}")
        }
        this.updateBungeeConfig()
    }

    private fun updateBungeeConfig() {
        val bungeeConfigFile = File("config.yml")
        if (!bungeeConfigFile.exists()) return

        try {
            val bungeeConfig = YamlDocument.create(
                bungeeConfigFile,
                GeneralSettings.DEFAULT,
                LoaderSettings.DEFAULT,
                DumperSettings.DEFAULT
            )

            this.servers.forEach { (name, address) ->
                val ipPort = "${address.hostString}:${address.port}"
                bungeeConfig.set("servers.$name.address", ipPort)
            }

            bungeeConfig.save()
        } catch (e: Exception) {
            this.logger.warning("Failed to update BungeeCord config.yml: ${e.message}")
        }
    }
}
