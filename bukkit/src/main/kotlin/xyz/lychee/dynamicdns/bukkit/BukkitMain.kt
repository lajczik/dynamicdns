package xyz.lychee.dynamicdns.bukkit

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import org.bstats.bukkit.Metrics
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import xyz.lychee.dynamicdns.shared.AddressUtil
import xyz.lychee.dynamicdns.shared.DynamicDnsConfig
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.MinecraftPacketUtil
import xyz.lychee.dynamicdns.shared.ModrinthUpdater
import xyz.lychee.dynamicdns.shared.NgrokHook
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

class BukkitMain : JavaPlugin() {
    lateinit var config: DynamicDnsConfig
        private set

    val configDoc: YamlDocument
        get() = config.yamlDocument

    var task: BukkitTask? = null
    var metrics: Metrics? = null
    var ngrok: NgrokHook? = null
    var dynu: DynuHook? = null
    var updater: ModrinthUpdater? = null

    companion object {
        @JvmStatic
        var instance: BukkitMain? = null
            private set
    }

    override fun onEnable() {
        instance = this
        this.metrics = Metrics(this, 27417)

        try {
            val yamlDoc = YamlDocument.create(
                File(this.dataFolder, "config.yml"),
                this.getResource("config.yml"),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                DumperSettings.DEFAULT
            )
            this.config = DynamicDnsConfig(yamlDoc, isProxy = false)
            this.reload()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

        val cmd = this.getCommand("dynamicdns")
        if (cmd != null) {
            val executor = DynamicDnsCommand(this)
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        }
    }

    @Throws(Exception::class)
    fun reload() {
        this.config.reload()

        val bind = InetSocketAddress(Bukkit.getIp(), Bukkit.getPort())
        val ngrokHook = NgrokHook(this.logger, bind, this.config.ngrok)
        val dynuHook = DynuHook(this.logger, this.config.dynu)
        this.ngrok = ngrokHook
        this.dynu = dynuHook

        val address = if (this.config.ngrok.enabled) {
            AddressUtil.parseAddressFromUri(ngrokHook.load())
        } else {
            InetSocketAddress(AddressUtil.checkPublicIp(), Bukkit.getPort())
        }

        if (this.config.dynu.enabled) {
            dynuHook.load(address)
        }

        if (this.config.checkUpdates) {
            val updater = ModrinthUpdater(this.logger, this.description.version)
            this.updater = updater
            updater.checkForUpdatesAsync(this.config.debug)
        }

        this.loadServerSync()
    }

    @Throws(Exception::class)
    fun loadServerSync() {
        this.task?.cancel()

        val syncConfig = this.config.serverSync
        if (!syncConfig.enabled) {
            return
        }

        val token = syncConfig.token
        if (token.isBlank()) {
            this.logger.warning("Value server_sync.token has not been set!")
            return
        }

        val name = syncConfig.name
        if (name.isBlank()) {
            this.logger.warning("Value server_sync.name has not been set!")
            return
        }

        val addressStr = syncConfig.targetAddress
        if (addressStr.isBlank()) {
            this.logger.warning("Value server_sync.target_address has not been set!")
            return
        }

        val split = addressStr.split(":")
        if (split.size != 2) {
            this.logger.warning("Invalid server_sync target address format. Expected format: host:port")
            return
        }

        val targetHost = split[0]
        val targetPort = split[1].toInt()
        val interval = 20L * syncConfig.interval

        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this, {
            try {
                val targetAddress = InetSocketAddress(targetHost, targetPort)
                val portOrIp = if (this.config.ngrok.enabled && this.ngrok != null) {
                    try {
                        val ngrokAddr = AddressUtil.parseAddressFromUri(this.ngrok!!.load())
                        "${ngrokAddr.hostString}:${ngrokAddr.port}"
                    } catch (e: Exception) {
                        "${server.port}"
                    }
                } else {
                    "${server.port}"
                }

                val payload = "sync.$token.$name.$portOrIp"
                MinecraftPacketUtil.sendSyncHandshake(targetAddress, payload, 5000)

                if (this.config.debug) {
                    this.logger.info("Sent sync ping to $targetAddress with payload: $payload")
                }
            } catch (e: IOException) {
                this.logger.warning("Failed to send update: ${e.message}")
            } catch (t: Throwable) {
                this.logger.warning("Unexpected error in sync task: ${t.message}")
            }
        }, 0L, interval)
    }

    override fun onDisable() {
        this.task?.cancel()
        this.metrics?.shutdown()
        this.ngrok?.unload()
    }
}
