package xyz.lychee.dynamicdns.sponge

import com.google.inject.Inject
import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import org.apache.logging.log4j.Logger
import org.bstats.sponge.Metrics
import org.spongepowered.api.Server
import org.spongepowered.api.Sponge
import org.spongepowered.api.command.Command
import org.spongepowered.api.config.ConfigDir
import org.spongepowered.api.event.Listener
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent
import org.spongepowered.api.event.lifecycle.StartedEngineEvent
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent
import org.spongepowered.api.scheduler.ScheduledTask
import org.spongepowered.api.scheduler.Task
import org.spongepowered.plugin.PluginContainer
import org.spongepowered.plugin.builtin.jvm.Plugin
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
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Plugin("dynamicdns")
class SpongeMain @Inject constructor(
    val pluginContainer: PluginContainer,
    val logger: Logger,
    @ConfigDir(sharedRoot = false) val configDir: Path,
    val metricsFactory: Metrics.Factory
) {
    lateinit var config: DynamicDnsConfig
        private set

    val configDoc: YamlDocument
        get() = config.yamlDocument

    var task: ScheduledTask? = null
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

    val julLogger: java.util.logging.Logger = object : java.util.logging.Logger("DynamicDNS", null) {
        override fun info(msg: String?) {
            if (msg != null) logger.info(msg)
        }

        override fun warning(msg: String?) {
            if (msg != null) logger.warn(msg)
        }

        override fun severe(msg: String?) {
            if (msg != null) logger.error(msg)
        }
    }

    companion object {
        @JvmStatic
        var instance: SpongeMain? = null
            private set
    }

    @Listener
    fun onRegisterCommands(event: RegisterCommandEvent<Command.Parameterized>) {
        val command = DynamicDnsCommand(this).build()
        event.register(this.pluginContainer, command, "dynamicdns", "ddns")
    }

    @Listener
    fun onServerStart(event: StartedEngineEvent<Server>) {
        instance = this
        this.metrics = this.metricsFactory.make(34845)

        try {
            val configFile = File(configDir.toFile(), "config.yml")
            val resourceStream = this.javaClass.classLoader.getResourceAsStream("config.yml")
            val yamlDoc = YamlDocument.create(
                configFile,
                resourceStream,
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                DumperSettings.DEFAULT
            )
            this.config = DynamicDnsConfig(yamlDoc, isProxy = false)
            this.reload()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Throws(Exception::class)
    fun reload() {
        this.config.reload()

        val server = Sponge.server()
        val bound = server.boundAddress().orElse(null)
        val port = bound?.port ?: 25565
        val host = bound?.hostString ?: "0.0.0.0"
        val bind = InetSocketAddress(host, port)

        val ngrokHook = NgrokHook(this.julLogger, bind, this.config.ngrok)
        val dynuHook = DynuHook(this.julLogger, this.config.dynu)
        val cfHook = CloudflareHook(this.julLogger, this.config.cloudflare)
        val duckHook = DuckDnsHook(this.julLogger, this.config.duckdns)
        val noipHook = NoIpHook(this.julLogger, this.config.noip)

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
        val address = InetSocketAddress(publicIp, port)
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
            val version = this.pluginContainer.metadata().version().toString()
            val updater = ModrinthUpdater(this.julLogger, version)
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

        val currentPort = Sponge.server().boundAddress().map { it.port }.orElse(25565)
        val payload = MinecraftPacketUtil.createSyncPayload(token, name, currentPort, secure = true)
        MinecraftPacketUtil.sendSyncHandshake(targetAddress, payload, 5000)
        this.lastSyncSentTime = System.currentTimeMillis()

        if (this.config.debug) {
            this.julLogger.info("Sent sync ping to $targetAddress with payload: $payload")
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

        val token = syncConfig.token
        if (token.isBlank()) {
            this.julLogger.warning("Value server_sync.token has not been set!")
            return
        }

        val name = syncConfig.name
        if (name.isBlank()) {
            this.julLogger.warning("Value server_sync.name has not been set!")
            return
        }

        val addressStr = syncConfig.targetAddress
        if (addressStr.isBlank()) {
            this.julLogger.warning("Value server_sync.target_address has not been set!")
            return
        }

        val split = addressStr.split(":")
        if (split.size != 2 || split[1].toIntOrNull() == null) {
            this.julLogger.warning("Invalid server_sync target address format. Expected format: host:port")
            return
        }

        val interval = syncConfig.interval

        val scheduledRunnable = Runnable {
            try {
                sendSyncPing()
            } catch (e: IOException) {
                this.julLogger.warning("Failed to send update: ${e.message}")
            } catch (t: Throwable) {
                this.julLogger.warning("Unexpected error in sync task: ${t.message}")
            }
        }

        val taskBuilder = Task.builder()
            .execute(scheduledRunnable)
            .interval(interval.toLong(), TimeUnit.SECONDS)
            .build()

        this.task = Sponge.asyncScheduler().submit(taskBuilder)
    }

    @Listener
    fun onServerStop(event: StoppingEngineEvent<Server>) {
        this.task?.cancel()
        this.ngrok?.unload()
        this.metrics = null
    }
}
