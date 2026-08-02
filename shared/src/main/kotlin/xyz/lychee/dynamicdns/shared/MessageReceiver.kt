package xyz.lychee.dynamicdns.shared

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.block.implementation.Section
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec

abstract class MessageReceiver(
    val logger: Logger,
    val config: YamlDocument
) {
    val servers: MutableMap<String, InetSocketAddress> = ConcurrentHashMap()
    val blacklist: MutableSet<InetAddress> = HashSet()
    val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r).apply { name = "DynamicDNS-Worker" }
    }
    var cipher: Cipher? = null

    companion object {
        @JvmStatic
        @Throws(NoSuchPaddingException::class, NoSuchAlgorithmException::class, InvalidKeyException::class)
        fun createCipher(token: String, mode: Int): Cipher {
            val keyBytes = ByteArray(16)
            val tokenBytes = token.toByteArray()
            System.arraycopy(tokenBytes, 0, keyBytes, 0, minOf(tokenBytes.size, keyBytes.size))

            val cipher = Cipher.getInstance("AES")
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"))
            return cipher
        }
    }

    fun loadServers(token: String) {
        try {
            this.cipher = createCipher(token, Cipher.DECRYPT_MODE)
        } catch (e: Exception) {
            this.logger.log(Level.SEVERE, "Failed to load config", e)
        }

        if (!this.config.contains("servers")) return

        if (this.config.isSection("servers")) {
            val section: Section? = this.config.getSection("servers")
            if (section != null) {
                for (key in section.keys) {
                    val serverName = key.toString()
                    this.servers[serverName] = AddressUtil.parseAddress(section.getString(serverName))
                }
            }
        } else if (this.config.isList("servers")) {
            val serversList = this.config.getStringList("servers")
            if (!serversList.isNullOrEmpty()) {
                for (server in serversList) {
                    val split = server.split("-")
                    this.servers[split[0]] = AddressUtil.parseAddress(split[1])
                }
            }
        }
    }

    @Throws(IOException::class)
    protected open fun saveServers() {
        this.servers.forEach { (name, address) ->
            this.config.set("servers.$name", "${address.hostString}:${address.port}")
        }
        this.config.save()
    }

    abstract fun updateServers()

    fun start(address: InetSocketAddress) {
        val mainThread = Thread {
            try {
                ServerSocket(address.port).use { serverSocket ->
                    this.logger.info("Started listening $address for ip changes!")

                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val clientSocket = serverSocket.accept()
                            val inetAddress = clientSocket.inetAddress

                            if (inetAddress == null || this.blacklist.contains(inetAddress)) {
                                this.silentClose(clientSocket)
                                continue
                            }

                            this.executor.submit { this.handleClient(clientSocket, inetAddress) }
                        } catch (ex: IOException) {
                            if (serverSocket.isClosed) break
                            this.logger.log(Level.WARNING, "Error accepting connection", ex)
                        }
                    }
                }
            } catch (ex: IOException) {
                this.logger.log(Level.SEVERE, "Failed to start TCP server", ex)
            } finally {
                executor.shutdown()
            }
        }

        mainThread.name = "DynamicDNS-Server"
        mainThread.start()
    }

    private fun handleClient(clientSocket: Socket, inetAddress: InetAddress) {
        try {
            clientSocket.soTimeout = 3000

            val rawBytes = this.readAllBytesBounded(clientSocket.getInputStream())
            if (rawBytes.isEmpty()) {
                this.silentClose(clientSocket)
                return
            }

            val decrypted = this.cipher!!.doFinal(rawBytes)
            val message = String(decrypted, StandardCharsets.UTF_8)

            val data = message.split(":", limit = 3)

            if (data.size >= 2) {
                val serverName = data[0]
                val serverPort = data[1].trim().toInt()
                val serverAddress = InetSocketAddress(inetAddress, serverPort)

                this.servers[serverName] = serverAddress

                this.updateServers()
                this.saveServers()
            } else {
                this.logger.warning("Invalid message format from ${inetAddress.hostAddress}")
                this.blacklist.add(inetAddress)
                this.silentClose(clientSocket)
            }
        } catch (ex: Exception) {
            if (this.logger.isLoggable(Level.FINE)) {
                this.logger.log(Level.FINE, "Bot/Scanner connection issue from ${inetAddress.hostAddress}", ex)
            }

            this.blacklist.add(inetAddress)
            this.silentClose(clientSocket)
        }
    }

    @Throws(IOException::class)
    private fun readAllBytesBounded(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(512)
        var bytesRead: Int
        var totalRead = 0

        while (input.read(data, 0, data.size).also { bytesRead = it } != -1) {
            buffer.write(data, 0, bytesRead)
            totalRead += bytesRead
            if (totalRead > 2048) {
                throw IOException("Packet size limit exceeded (DoS Protection)")
            }
        }
        return buffer.toByteArray()
    }

    fun silentClose(clientSocket: Socket?) {
        try {
            if (clientSocket != null && !clientSocket.isClosed) {
                clientSocket.setSoLinger(true, 0)
                clientSocket.close()
            }
        } catch (ignored: IOException) {
        }
    }
}
