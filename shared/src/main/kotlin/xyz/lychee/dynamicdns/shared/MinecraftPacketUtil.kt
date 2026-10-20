package xyz.lychee.dynamicdns.shared

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object MinecraftPacketUtil {

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
        // Packet ID 0x00 (Handshake)
        writeVarInt(dataStream, 0x00)
        // Protocol Version
        writeVarInt(dataStream, protocolVersion)
        // Server Address (String)
        writeString(dataStream, hostname)
        // Server Port (Unsigned Short)
        writeUnsignedShort(dataStream, port)
        // Next State (VarInt: 1 = STATUS)
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
