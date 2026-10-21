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
import xyz.lychee.dynamicdns.shared.CloudflareHook
import xyz.lychee.dynamicdns.shared.DuckDnsHook
import xyz.lychee.dynamicdns.shared.DynamicDnsConfig
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.MinecraftPacketUtil
import xyz.lychee.dynamicdns.shared.ModrinthUpdater
import xyz.lychee.dynamicdns.shared.NgrokHook
import xyz.lychee.dynamicdns.shared.NoIpHook
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
    var cloudflare: CloudflareHook? = null
    var duckdns: DuckDnsHook? = null
    var noip: NoIpHook? = null
    var updater: ModrinthUpdater? = null

    @Volatile
    var lastSyncSentTime: Long = 0L
    @Volatile
    var lastResolvedAddress: InetSocketAddress? = null

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
        val cfHook = CloudflareHook(this.logger, this.config.cloudflare)
        val duckHook = DuckDnsHook(this.logger, this.config.duckdns)
        val noipHook = NoIpHook(this.logger, this.config.noip)

        this.ngrok = ngrokHook
        this.dynu = dynuHook
        this.cloudflare = cfHook
        this.duckdns = duckHook
        this.noip = noipHook

        val publicIp = if (this.config.ngrok.enabled) {
            AddressUtil.parseAddressFromUri(ngrokHook.load()).address
        } else {
            AddressUtil.checkPublicIp()
        }
        val address = InetSocketAddress(publicIp, Bukkit.getPort())
        this.lastResolvedAddress = address

        if (this.config.dynu.enabled) {
            dynuHook.load(address)
        }

        val ipv6 = if (this.config.cloudflare.updateIpv6 || this.config.duckdns.updateIpv6) {
            AddressUtil.checkPublicIpv6()
        } else null

        if (this.config.cloudflare.enabled) {
            cfHook.load(publicIp, ipv6)
        }

        if (this.config.duckdns.enabled) {
            duckHook.load(publicIp, ipv6)
        }

        if (this.config.noip.enabled) {
            noipHook.load(publicIp)
        }

        if (this.config.checkUpdates) {
            val updater = ModrinthUpdater(this.logger, this.description.version)
            this.updater = updater
            updater.checkForUpdatesAsync(this.config.debug)
        }

        this.loadServerSync()
    }

    fun sendSyncPing(): Boolean {
        val syncConfig = this.config.serverSync
        if (!syncConfig.enabled) return false

        val token = syncConfig.token
        val name = syncConfig.name
        val addressStr = syncConfig.targetAddress
        if (token.isBlank() || name.isBlank() || addressStr.isBlank()) return false

        val split = addressStr.split(":")
        if (split.size != 2) return false

        val targetHost = split[0]
        val targetPort = split[1].toIntOrNull() ?: return false
        val targetAddress = InetSocketAddress(targetHost, targetPort)
        val currentPort = server.port

        val payload = MinecraftPacketUtil.createSyncPayload(token, name, currentPort, secure = true)
        MinecraftPacketUtil.sendSyncHandshake(targetAddress, payload, 5000)
        this.lastSyncSentTime = System.currentTimeMillis()

        if (this.config.debug) {
            this.logger.info("Sent sync ping to $targetAddress with payload: $payload")
        }
        return true
    }

    @Throws(Exception::class)
    fun loadServerSync() {
        this.task?.cancel()

        val syncConfig = this.config.serverSync
        if (!syncConfig.enabled) {
            return
        }

        if (syncConfig.token.isBlank()) {
            this.logger.warning("Value server_sync.token has not been set!")
            return
        }

        if (syncConfig.name.isBlank()) {
            this.logger.warning("Value server_sync.name has not been set!")
            return
        }

        if (syncConfig.targetAddress.isBlank()) {
            this.logger.warning("Value server_sync.target_address has not been set!")
            return
        }

        val interval = 20L * syncConfig.interval

        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this, {
            try {
                sendSyncPing()
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
