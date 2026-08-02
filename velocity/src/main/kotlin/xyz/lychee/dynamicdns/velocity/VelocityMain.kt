package xyz.lychee.dynamicdns.velocity

import com.electronwill.nightconfig.core.Config
import com.electronwill.nightconfig.core.file.CommentedFileConfig
import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
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
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.MessageReceiver
import xyz.lychee.dynamicdns.shared.NgrokHook
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class VelocityMain @Inject constructor(
    val proxy: ProxyServer,
    val logger: Logger,
    @DataDirectory dataDirectory: Path,
    val metricsFactory: Metrics.Factory
) {
    val servers: MutableMap<String, InetSocketAddress> = ConcurrentHashMap()
    val configDoc: YamlDocument = YamlDocument.create(
        dataDirectory.resolve("config.yml").toFile(),
        this.javaClass.classLoader.getResourceAsStream("config.yml"),
        GeneralSettings.DEFAULT,
        LoaderSettings.builder().setCreateFileIfAbsent(true).build(),
        DumperSettings.DEFAULT
    )
    val receiver: MessageReceiver = VelocityMessageReceiver(logger, configDoc)
    val ngrok: NgrokHook = NgrokHook(logger, proxy.boundAddress, configDoc)
    val dynu: DynuHook = DynuHook(logger, configDoc)
    var metrics: Metrics? = null

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        this.metrics = this.metricsFactory.make(this, 27000)
        this.reload()
    }

    @Throws(IOException::class)
    fun reload() {
        this.configDoc.reload()

        val address = if (this.configDoc.getBoolean("ngrok.enabled", false)) {
            AddressUtil.parseAddressFromUri(this.ngrok.load())
        } else {
            InetSocketAddress(AddressUtil.checkPublicIp(), proxy.boundAddress.port)
        }

        if (this.configDoc.getBoolean("dynu.enabled", false)) {
            this.dynu.load(address)
        }

        this.loadSocketServers()
    }

    @Throws(IOException::class)
    fun loadSocketServers() {
        var token = this.configDoc.getString("socket_server.token")

        if (token == null) {
            token = UUID.randomUUID().toString()
            this.configDoc.set("socket_server.token", token)
            this.configDoc.save()
        }

        if (!this.configDoc.getBoolean("socket_server.enabled", false)) {
            return
        }

        val addressStr = this.configDoc.getString("socket_server.bind")
        if (addressStr == null) {
            this.logger.warning("Value socket_server.bind has not been set!")
            return
        }

        val split = addressStr.split(":")
        if (split.size != 2) {
            this.logger.warning("Invalid socket_server bind format. Expected format: host:port")
            return
        }

        this.receiver.loadServers(token)
        this.receiver.updateServers()
        this.receiver.start(InetSocketAddress(split[0], split[1].toInt()))
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        this.metrics?.shutdown()
        this.ngrok.unload()
    }

    inner class VelocityMessageReceiver(logger: Logger, config: YamlDocument) : MessageReceiver(logger, config) {
        override fun updateServers() {
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

        @Throws(IOException::class)
        override fun saveServers() {
            super.saveServers()
            this.updateVelocityToml()
        }

        private fun updateVelocityToml() {
            val tomlFile = File("velocity.toml")
            if (!tomlFile.exists()) return

            try {
                // Załaduj plik z zachowaniem komentarzy i kolejności
                val config = CommentedFileConfig.builder(tomlFile)
                    .preserveInsertionOrder()
                    .sync()
                    .build()
                config.load()

                // Upewnij się, że sekcja [servers] istnieje
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
    }
}
