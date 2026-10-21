package xyz.lychee.dynamicdns.velocity

import com.electronwill.nightconfig.core.Config
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.electronwill.nightconfig.toml.TomlFormat
import com.google.inject.Inject
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.ServerInfo
import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import org.bstats.velocity.Metrics
import xyz.lychee.dynamicdns.shared.AddressUtil
import xyz.lychee.dynamicdns.shared.DynamicDnsConfig
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.MinecraftPacketUtil
import xyz.lychee.dynamicdns.shared.ModrinthUpdater
import xyz.lychee.dynamicdns.shared.NgrokHook
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class VelocityMain @Inject constructor(
    val proxy: ProxyServer,
    val logger: Logger,
    @DataDirectory dataDirectory: Path,
    val metricsFactory: Metrics.Factory
) {
    val configDoc: YamlDocument = YamlDocument.create(
        dataDirectory.resolve("config.yml").toFile(),
        this.javaClass.classLoader.getResourceAsStream("config.yml"),
        GeneralSettings.DEFAULT,
        LoaderSettings.builder().setCreateFileIfAbsent(true).build(),
        DumperSettings.DEFAULT
    )
    val config: DynamicDnsConfig = DynamicDnsConfig(configDoc, isProxy = true)
    val servers: MutableMap<String, InetSocketAddress>
        get() = config.servers
    val serverLastUpdate: MutableMap<String, Long> = ConcurrentHashMap()

    val ngrok: NgrokHook = NgrokHook(logger, proxy.boundAddress, config.ngrok)
    val dynu: DynuHook = DynuHook(logger, config.dynu)
    var metrics: Metrics? = null
    var updater: ModrinthUpdater? = null

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        this.metrics = this.metricsFactory.make(this, 27419)
        val commandManager = this.proxy.commandManager
        val meta = commandManager.metaBuilder("dynamicdns")
            .aliases("ddns")
            .plugin(this)
            .build()
        commandManager.register(meta, DynamicDnsCommand(this))
        this.reload()
    }

    @Throws(IOException::class)
    fun reload() {
        this.config.reload()
        this.ngrok.ngrokConfig = this.config.ngrok
        this.dynu.dynuConfig = this.config.dynu

        val address = if (this.config.ngrok.enabled) {
            AddressUtil.parseAddressFromUri(this.ngrok.load())
        } else {
            InetSocketAddress(AddressUtil.checkPublicIp(), proxy.boundAddress.port)
        }

        if (this.config.dynu.enabled) {
            this.dynu.load(address)
        }

        if (this.config.checkUpdates) {
            val version = proxy.pluginManager.fromInstance(this).flatMap { it.description.version }.orElse(ModrinthUpdater.CURRENT_VERSION)
            val updater = ModrinthUpdater(this.logger, version)
            this.updater = updater
            updater.checkForUpdatesAsync(this.config.debug)
        }

        this.loadServers()
    }

    fun loadServers() {
        this.config.ensureProxySyncToken()

        this.servers.forEach { (name, address) ->
            val info = proxy.getServer(name).map { it.serverInfo }.orElse(null)
            if (info == null || info.address != address) {
                if (info != null) {
                    proxy.unregisterServer(info)
                }
                proxy.registerServer(ServerInfo(name, address))
                this.logger.info("Loaded server $name with address $address!")
            }
        }
    }

    @Subscribe(order = PostOrder.FIRST, priority = Short.MIN_VALUE)
    fun onProxyPing(event: ProxyPingEvent) {
        val virtualHost = event.connection.virtualHost.map { it.hostString }.orElseGet {
            try {
                event.connection.rawVirtualHost.orElse(null)
            } catch (e: Throwable) {
                null
            }
        } ?: return

        if (!virtualHost.startsWith("sync.")) return

        // Immediately cancel the ping event and close the connection without Netty reflection
        event.result = ResultedEvent.GenericResult.denied()
        event.ping = null

        handleSyncPayload(virtualHost, event.connection.remoteAddress)
    }

    @Subscribe(order = PostOrder.FIRST, priority = Short.MIN_VALUE)
    fun onHandshake(event: ConnectionHandshakeEvent) {
        val virtualHost = event.connection.virtualHost.map { it.hostString }.orElseGet {
            try {
                event.connection.rawVirtualHost.orElse(null)
            } catch (e: Throwable) {
                null
            }
        } ?: return

        if (!virtualHost.startsWith("sync.")) return

        handleSyncPayload(virtualHost, event.connection.remoteAddress)
    }

    private fun handleSyncPayload(virtualHost: String, remoteAddress: SocketAddress) {
        if (!this.config.serverSync.enabled) {
            return
        }

        val expectedToken = this.config.serverSync.token
        val parsed = MinecraftPacketUtil.parseAndVerifySyncPayload(virtualHost, expectedToken)
        if (parsed == null) {
            this.logger.warning("Rejected sync update from $remoteAddress: invalid or expired payload structure ($virtualHost)")
            return
        }

        val serverName = parsed.serverName
        val targetPortOrIp = parsed.targetPortOrIp

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

        serverLastUpdate[serverName] = System.currentTimeMillis()

        val existingInfo = proxy.getServer(serverName).map { it.serverInfo }.orElse(null)
        if (existingInfo != null && existingInfo.address == targetAddress && servers[serverName] == targetAddress) {
            if (this.config.debug) {
                this.logger.info("Sync heartbeat received for $serverName ($targetAddress) - address unchanged")
            }
            return
        }

        if (existingInfo != null) {
            proxy.unregisterServer(existingInfo)
        }

        val newInfo = ServerInfo(serverName, targetAddress)
        proxy.registerServer(newInfo)
        servers[serverName] = targetAddress
        val secLabel = if (parsed.isSecure) " (HMAC verified)" else ""
        this.logger.info("Synchronized server $serverName with address $targetAddress$secLabel (from $remoteAddress)")

        saveServers()
    }

    @Synchronized
    fun saveServers() {
        try {
            this.config.saveServers()
        } catch (e: Exception) {
            this.logger.warning("Failed to save servers to config: ${e.message}")
        }
        this.updateVelocityToml()
    }

    private fun updateVelocityToml() {
        val tomlFile = File("velocity.toml")
        if (!tomlFile.exists()) return

        try {
            val config = CommentedFileConfig.builder(tomlFile, TomlFormat.instance())
                .preserveInsertionOrder()
                .sync()
                .build()
            config.load()

            var serversSection = config.get<Config>("servers")
            if (serversSection == null) {
                serversSection = config.createSubConfig()
                config.set<Config>("servers", serversSection)
            }

            var hasChanges = false

            this.servers.forEach { (name, addr) ->
                val addressStr = "${addr.hostString}:${addr.port}"
                val existing = serversSection.get<Any>(name)

                if (existing is Config) {
                    val currentAddress = existing.get<String>("address")
                    if (currentAddress != addressStr) {
                        serversSection.set<Config>("$name.address", addressStr)
                        hasChanges = true
                    }
                } else {
                    val currentValue = existing as? String
                    if (currentValue != addressStr) {
                        serversSection.set<String>(name, addressStr)
                        hasChanges = true
                    }
                }
            }

            if (hasChanges) {
                config.save()
                this.logger.info("Updated velocity.toml with new server addresses")
            }

            config.close()
        } catch (e: Exception) {
            this.logger.warning("Failed to update velocity.toml: ${e.message}")
        }
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        this.metrics?.shutdown()
        this.ngrok.unload()
    }
}
