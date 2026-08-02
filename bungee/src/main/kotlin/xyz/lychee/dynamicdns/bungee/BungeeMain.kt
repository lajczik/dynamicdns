package xyz.lychee.dynamicdns.bungee

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import net.md_5.bungee.api.config.ServerInfo
import net.md_5.bungee.api.plugin.Plugin
import org.bstats.bungeecord.Metrics
import xyz.lychee.dynamicdns.shared.AddressUtil
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.MessageReceiver
import xyz.lychee.dynamicdns.shared.NgrokHook
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class BungeeMain : Plugin() {
    val servers: MutableMap<String, InetSocketAddress> = ConcurrentHashMap()
    var configDoc: YamlDocument? = null
        private set
    var receiver: MessageReceiver? = null
        private set
    var metrics: Metrics? = null
        private set
    var ngrok: NgrokHook? = null
        private set
    var dynu: DynuHook? = null
        private set

    override fun onEnable() {
        this.metrics = Metrics(this, 27000)
        try {
            this.configDoc = YamlDocument.create(
                File(this.dataFolder, "config.yml"),
                this.getResourceAsStream("config.yml"),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                DumperSettings.DEFAULT
            )

            this.reload()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    override fun onDisable() {
        this.metrics?.shutdown()
        this.ngrok?.unload()
    }

    @Throws(IOException::class)
    fun reload() {
        val cfg = this.configDoc!!
        cfg.reload()

        val bind = this.proxy.config.listeners
            .firstOrNull()
            ?.let { it.socketAddress as? InetSocketAddress }
            ?: InetSocketAddress(InetAddress.getLoopbackAddress(), 25565)

        val ngrokHook = NgrokHook(this.logger, bind, cfg)
        val dynuHook = DynuHook(this.logger, cfg)
        val msgReceiver = BungeeMessageReceiver(this.logger, cfg)

        this.ngrok = ngrokHook
        this.dynu = dynuHook
        this.receiver = msgReceiver

        val address = if (cfg.getBoolean("ngrok.enabled", false)) {
            AddressUtil.parseAddressFromUri(ngrokHook.load())
        } else {
            InetSocketAddress(AddressUtil.checkPublicIp(), bind.port)
        }

        if (cfg.getBoolean("dynu.enabled", false)) {
            dynuHook.load(address)
        }

        this.loadSocketServers()
    }

    @Throws(IOException::class)
    fun loadSocketServers() {
        val cfg = this.configDoc!!
        var token = cfg.getString("socket_server.token", null)

        if (token == null) {
            token = UUID.randomUUID().toString()
            cfg.set("socket_server.token", token)
            cfg.save()
        }

        if (!cfg.getBoolean("socket_server.enabled", false)) {
            return
        }

        val addressStr = cfg.getString("socket_server.address")
        if (addressStr == null) {
            this.logger.warning("Value socket_client.address has not been set!")
            return
        }

        val split = addressStr.split(":")
        if (split.size != 2) {
            this.logger.warning("Invalid socket_server address format. Expected format: host:port")
            return
        }

        val r = this.receiver!!
        r.loadServers(token)
        r.updateServers()
        r.start(InetSocketAddress(split[0], split[1].toInt()))
    }

    inner class BungeeMessageReceiver(logger: Logger, config: YamlDocument) : MessageReceiver(logger, config) {
        override fun updateServers() {
            val current = proxy.servers
            this.servers.forEach { (name, address) ->
                val existing = current[name]
                if (existing != null && existing.socketAddress == address) return@forEach

                val oldInfo = current[name]

                val newInfo = proxy.constructServerInfo(
                    name, address, "DynamicDNS updated server", false
                )

                oldInfo?.players?.forEach { p -> p.connect(newInfo) }

                current[name] = newInfo
                this.logger.info("Loaded server $name with address $address!")
            }
        }

        @Throws(IOException::class)
        override fun saveServers() {
            super.saveServers()
            this.updateBungeeConfig()
        }

        private fun updateBungeeConfig() {
            val bungeeConfigFile = File("config.yml")
            if (!bungeeConfigFile.exists()) {
                return
            }

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
}
