package xyz.lychee.dynamicdns.shared

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class MinecraftPacketUtilTest {

    @Test
    fun testVarIntRoundTrip() {
        val testValues = listOf(
            0, 1, 2, 127, 128, 255, 256, 32767, 65535, 2147483647, -1, -2147483648
        )

        for (value in testValues) {
            val out = ByteArrayOutputStream()
            MinecraftPacketUtil.writeVarInt(out, value)

            val input = ByteArrayInputStream(out.toByteArray())
            val decoded = MinecraftPacketUtil.readVarInt(input)

            assertEquals(value, decoded, "Failed for value $value")
        }
    }

    @Test
    fun testCreateHandshakePacketStructure() {
        val payload = "sync.secretToken.survival.25566"
        val packet = MinecraftPacketUtil.createHandshakePacket(
            hostname = payload,
            port = 25565,
            protocolVersion = 47,
            nextState = 1
        )

        val input = ByteArrayInputStream(packet)
        val packetLength = MinecraftPacketUtil.readVarInt(input)
        val packetId = MinecraftPacketUtil.readVarInt(input)
        val protocolVersion = MinecraftPacketUtil.readVarInt(input)

        val hostLen = MinecraftPacketUtil.readVarInt(input)
        val hostBytes = ByteArray(hostLen)
        val bytesRead = input.read(hostBytes)
        val hostname = String(hostBytes, StandardCharsets.UTF_8)

        val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
        val nextState = MinecraftPacketUtil.readVarInt(input)

        assertEquals(0, packetId)
        assertEquals(47, protocolVersion)
        assertEquals(payload, hostname)
        assertEquals(hostBytes.size, bytesRead)
        assertEquals(25565, port)
        assertEquals(1, nextState)
        assertEquals(0, input.available(), "Stream should be fully consumed")
    }

    @Test
    fun testStatusRequestPacket() {
        assertArrayEquals(byteArrayOf(0x01, 0x00), MinecraftPacketUtil.STATUS_REQUEST_PACKET)
    }
}
