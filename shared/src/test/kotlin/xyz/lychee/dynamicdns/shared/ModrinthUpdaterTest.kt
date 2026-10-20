package xyz.lychee.dynamicdns.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class ModrinthUpdaterTest {

    @Test
    fun `test isNewer version comparisons`() {
        // Newer versions
        assertTrue(ModrinthUpdater.isNewer("1.0", "1.1"))
        assertTrue(ModrinthUpdater.isNewer("1.0", "2.0"))
        assertTrue(ModrinthUpdater.isNewer("1.2", "2.0"))
        assertTrue(ModrinthUpdater.isNewer("2.0", "2.0.1"))
        assertTrue(ModrinthUpdater.isNewer("2.0", "2.1"))
        assertTrue(ModrinthUpdater.isNewer("v2.0", "v2.0.1"))
        assertTrue(ModrinthUpdater.isNewer("2.0-SNAPSHOT", "2.1"))

        // Equal or older versions
        assertFalse(ModrinthUpdater.isNewer("2.0", "2.0"))
        assertFalse(ModrinthUpdater.isNewer("2.0", "1.2"))
        assertFalse(ModrinthUpdater.isNewer("2.0", "1.0"))
        assertFalse(ModrinthUpdater.isNewer("2.1", "2.0"))
        assertFalse(ModrinthUpdater.isNewer("2.0.1", "2.0"))
        assertFalse(ModrinthUpdater.isNewer("v2.0", "2.0"))
    }

    @Test
    fun `test parseFirstVersion extracts correctly from json array`() {
        val sampleJson = """
            [
              {
                "id": "abc12345",
                "project_id": "O06FqZLO",
                "name": "DynamicDNS 2.1",
                "version_number": "2.1",
                "version_type": "release",
                "files": [
                  {
                    "url": "https://cdn.modrinth.com/data/O06FqZLO/versions/abc12345/DynamicDNS.jar",
                    "filename": "DynamicDNS.jar",
                    "primary": true
                  }
                ]
              },
              {
                "id": "old12345",
                "project_id": "O06FqZLO",
                "name": "DynamicDNS 1.2",
                "version_number": "1.2",
                "version_type": "release",
                "files": [
                  {
                    "url": "https://cdn.modrinth.com/data/O06FqZLO/versions/old12345/DynamicDNS.jar",
                    "filename": "DynamicDNS.jar",
                    "primary": true
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = ModrinthUpdater.parseFirstVersion(sampleJson)
        assertNotNull(parsed)
        assertEquals("2.1", parsed!!.versionNumber)
        assertEquals("DynamicDNS 2.1", parsed.name)
        assertEquals("https://cdn.modrinth.com/data/O06FqZLO/versions/abc12345/DynamicDNS.jar", parsed.downloadUrl)
    }

    @Test
    fun `test parseFirstVersion handles invalid json gracefully`() {
        assertNull(ModrinthUpdater.parseFirstVersion("{}"))
        assertNull(ModrinthUpdater.parseFirstVersion("[]"))
        assertNull(ModrinthUpdater.parseFirstVersion("invalid json"))
    }
}
