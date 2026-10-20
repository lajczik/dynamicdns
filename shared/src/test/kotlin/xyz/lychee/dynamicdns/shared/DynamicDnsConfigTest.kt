package xyz.lychee.dynamicdns.shared

import dev.dejvokep.boostedyaml.YamlDocument
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class DynamicDnsConfigTest {

    @Test
    fun `test fields populated from yaml on reload`() {
        val yamlContent = """
            debug: true
            ngrok:
              enabled: true
              token: "test-token-123"
              region: "EU"
            dynu:
              enabled: true
              domain: "sub.example.com"
              client: "dynu-user"
              secret: "dynu-secret"
            server_sync:
              enabled: true
              token: "sync-secret-456"
              name: "lobby"
              target_address: "1.2.3.4:25565"
              interval: 15
            servers:
              lobby: "1.2.3.4:25566"
        """.trimIndent()

        val doc = YamlDocument.create(
            ByteArrayInputStream(yamlContent.toByteArray(StandardCharsets.UTF_8)),
            GeneralSettings.DEFAULT,
            LoaderSettings.DEFAULT,
            DumperSettings.DEFAULT
        )

        val config = DynamicDnsConfig(doc, isProxy = true)

        assertTrue(config.debug)
        assertTrue(config.ngrok.enabled)
        assertEquals("test-token-123", config.ngrok.token)
        assertEquals("EU", config.ngrok.region)

        assertTrue(config.dynu.enabled)
        assertEquals("sub.example.com", config.dynu.domain)
        assertEquals("dynu-user", config.dynu.client)
        assertEquals("dynu-secret", config.dynu.secret)

        assertTrue(config.serverSync.enabled)
        assertEquals("sync-secret-456", config.serverSync.token)
        assertEquals("lobby", config.serverSync.name)
        assertEquals("1.2.3.4:25565", config.serverSync.targetAddress)
        assertEquals(15, config.serverSync.interval)

        assertEquals(1, config.servers.size)
        assertEquals(25566, config.servers["lobby"]?.port)

        // Simulate reload with modified content
        val newContent = yamlContent
            .replace("debug: true", "debug: false")
            .replace("interval: 15", "interval: 60")
        doc.reload(ByteArrayInputStream(newContent.toByteArray(StandardCharsets.UTF_8)))
        config.reload()

        assertFalse(config.debug)
        assertEquals(60, config.serverSync.interval)
    }

    @Test
    fun `test legacy socket fallback fields populated`() {
        val yamlContent = """
            socket_client:
              enabled: true
              token: "legacy-token"
              name: "survival"
              target_address: "5.6.7.8:25565"
              interval: 45
        """.trimIndent()

        val doc = YamlDocument.create(
            ByteArrayInputStream(yamlContent.toByteArray(StandardCharsets.UTF_8)),
            GeneralSettings.DEFAULT,
            LoaderSettings.DEFAULT,
            DumperSettings.DEFAULT
        )

        val config = DynamicDnsConfig(doc, isProxy = false)

        assertTrue(config.serverSync.enabled)
        assertEquals("legacy-token", config.serverSync.token)
        assertEquals("survival", config.serverSync.name)
        assertEquals("5.6.7.8:25565", config.serverSync.targetAddress)
        assertEquals(45, config.serverSync.interval)
    }
}
