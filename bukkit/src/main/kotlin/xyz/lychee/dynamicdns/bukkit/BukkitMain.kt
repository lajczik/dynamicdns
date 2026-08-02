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
import xyz.lychee.dynamicdns.shared.DynuHook
import xyz.lychee.dynamicdns.shared.MessageReceiver
import xyz.lychee.dynamicdns.shared.NgrokHook
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher

class BukkitMain : JavaPlugin() {
    lateinit var configDoc: YamlDocument
        private set
    var task: BukkitTask? = null
    var metrics: Metrics? = null
    var ngrok: NgrokHook? = null
    var dynu: DynuHook? = null

    companion object {
        @JvmStatic
        var instance: BukkitMain? = null
            private set
    }

    override fun onEnable() {
        instance = this
        this.metrics = Metrics(this, 27000)

        try {
            this.configDoc = YamlDocument.create(
                File(this.dataFolder, "config.yml"),
                this.getResource("config.yml"),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setCreateFileIfAbsent(true).setAutoUpdate(true).build(),
                DumperSettings.DEFAULT
            )

            this.reload()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Throws(Exception::class)
    fun reload() {
        this.configDoc.reload()

        val ngrokHook = NgrokHook(this.logger, InetSocketAddress(Bukkit.getIp(), Bukkit.getPort()), this.configDoc)
        val dynuHook = DynuHook(this.logger, this.configDoc)
        this.ngrok = ngrokHook
        this.dynu = dynuHook

        val address = if (this.configDoc.getBoolean("ngrok.enabled", false)) {
            AddressUtil.parseAddressFromUri(ngrokHook.load())
        } else {
            InetSocketAddress(AddressUtil.checkPublicIp(), Bukkit.getPort())
        }

        if (this.configDoc.getBoolean("dynu.enabled", false)) {
            dynuHook.load(address)
        }

        this.loadSocketClient()
    }

    @Throws(Exception::class)
    fun loadSocketClient() {
        if (!this.configDoc.getBoolean("socket_client.enabled", false)) {
            return
        }

        val token = this.configDoc.getString("socket_client.token")
        if (token == null) {
            this.logger.warning("Value socket_client.token has not been set!")
            return
        }

        val addressStr = this.configDoc.getString("socket_client.target_address")
        if (addressStr == null) {
            this.logger.warning("Value socket_client.target_address has not been set!")
            return
        }

        val split = addressStr.split(":")
        if (split.size != 2) {
            this.logger.warning("Invalid socket_client target address format. Expected format: host:port")
            return
        }

        this.task?.cancel()

        val cipher = MessageReceiver.createCipher(token, Cipher.ENCRYPT_MODE)
        val message = "${this.configDoc.getString("socket_client.name")}:${server.port}"
        val encrypted = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))

        val socketAddress = InetSocketAddress(split[0], split[1].toInt())

        val interval = 20L * this.configDoc.getInt("socket_client.interval", 30)
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 5000
                    socket.connect(socketAddress, 5000)
                    socket.outputStream.write(encrypted)
                }
            } catch (e: IOException) {
                this.logger.warning("Failed to send update: ${e.message}")
            }
        }, interval, interval)
    }

    override fun onDisable() {
        this.task?.cancel()
        this.metrics?.shutdown()
        this.ngrok?.unload()
    }
}
