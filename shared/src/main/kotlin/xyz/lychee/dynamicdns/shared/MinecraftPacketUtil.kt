package xyz.lychee.dynamicdns.shared

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.math.abs

data class ParsedSyncPayload(
    val serverName: String,
    val targetPortOrIp: String,
    val isSecure: Boolean
)

object MinecraftPacketUtil {

    /**
     * Builds a sync handshake virtualHost string.
     * When [secure] is true, generates a timestamped HMAC-SHA256 signed payload:
     * `sync.v2.<timestamp>.<name>.<portOrIp>.<signature>`
     * Otherwise generates legacy payload:
     * `sync.<token>.<name>.<portOrIp>`
     */
    fun createSyncPayload(
        token: String,
        name: String,
        portOrIp: String,
        secure: Boolean = true
    ): String {
        return if (secure && token.isNotBlank()) {
            val timestamp = System.currentTimeMillis() / 1000L
            val dataToSign = "$timestamp.$name.$portOrIp"
            val signature = HmacUtil.sign(token, dataToSign)
            "sync.v2.$timestamp.$name.$portOrIp.$signature"
        } else {
            "sync.$token.$name.$portOrIp"
        }
    }

    /**
     * Parses and verifies an incoming virtualHost handshake payload against the [expectedToken].
     * Supports both HMAC v2 format and legacy token format.
     */
    fun parseAndVerifySyncPayload(
        virtualHost: String,
        expectedToken: String,
        maxClockDriftSeconds: Long = 120L
    ): ParsedSyncPayload? {
        val cleanHost = virtualHost.trimEnd('.')
        val parts = cleanHost.split(".")

        // Format v2: sync.v2.<timestamp>.<name>.<portOrIp>.<signature>
        if (parts.size >= 6 && parts[0] == "sync" && parts[1] == "v2") {
            val timestamp = parts[2].toLongOrNull() ?: return null
            val name = parts[3]
            val signature = parts.last()
            // In case portOrIp contains dots or IP parts:
            val portOrIp = parts.subList(4, parts.size - 1).joinToString(".")

            val now = System.currentTimeMillis() / 1000L
            if (abs(now - timestamp) > maxClockDriftSeconds) {
                return null
            }

            val dataToSign = "$timestamp.$name.$portOrIp"
            if (!HmacUtil.verify(expectedToken, dataToSign, signature)) {
                return null
            }

            return ParsedSyncPayload(name, portOrIp, isSecure = true)
        }

        // Format legacy: sync.<token>.<name>.<portOrIp>
        if (parts.size >= 4 && parts[0] == "sync") {
            val receivedToken = parts[1]
            val name = parts[2]
            val portOrIp = parts.drop(3).joinToString(".")

            if (expectedToken.isBlank() || receivedToken != expectedToken) {
                return null
            }

            return ParsedSyncPayload(name, portOrIp, isSecure = false)
        }

        return null
    }

    /**
     * Writes a Minecraft VarInt to the given OutputStream.
     */
    @Throws(IOException::class)
    fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    /**
     * Reads a Minecraft VarInt from the given InputStream.
     */
    @Throws(IOException::class)
    fun readVarInt(input: InputStream): Int {
        var numRead = 0
        var result = 0
        var read: Int
        do {
            read = input.read()
            if (read == -1) {
                throw IOException("Premature end of stream while reading VarInt")
            }
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if (numRead > 5) {
                throw IOException("VarInt is too big")
            }
        } while ((read and 0x80) != 0)
        return result
    }

    /**
     * Writes a UTF-8 String prefixed with a VarInt length.
     */
    @Throws(IOException::class)
    fun writeString(out: OutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    /**
     * Writes an unsigned 16-bit short in Big-Endian format.
     */
    @Throws(IOException::class)
    fun writeUnsignedShort(out: OutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /**
     * Builds a framed Minecraft Handshake packet (Packet ID 0x00).
     */
    @Throws(IOException::class)
    fun createHandshakePacket(
        hostname: String,
        port: Int,
        protocolVersion: Int = 47,
        nextState: Int = 1
    ): ByteArray {
        val dataStream = ByteArrayOutputStream()
        writeVarInt(dataStream, 0x00)
        writeVarInt(dataStream, protocolVersion)
        writeString(dataStream, hostname)
        writeUnsignedShort(dataStream, port)
        writeVarInt(dataStream, nextState)

        val packetData = dataStream.toByteArray()
        val framedStream = ByteArrayOutputStream()
        writeVarInt(framedStream, packetData.size)
        framedStream.write(packetData)
        return framedStream.toByteArray()
    }

    /**
     * Status Request packet: Length 1 (0x01), Packet ID 0 (0x00).
     */
    val STATUS_REQUEST_PACKET = byteArrayOf(0x01, 0x00)

    /**
     * Sends the fabricated Minecraft Handshake packet followed by a Status Request packet
     * to the target Velocity proxy over a standard TCP Socket.
     */
    @Throws(IOException::class)
    fun sendSyncHandshake(
        targetAddress: InetSocketAddress,
        payload: String,
        timeoutMs: Int = 5000
    ) {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.setReuseAddress(true)
            socket.connect(targetAddress, timeoutMs)

            val handshakePacket = createHandshakePacket(
                hostname = payload,
                port = targetAddress.port,
                protocolVersion = 47,
                nextState = 1
            )

            val out = socket.outputStream
            out.write(handshakePacket)
            out.write(STATUS_REQUEST_PACKET)
            out.flush()
        }
    }
}
