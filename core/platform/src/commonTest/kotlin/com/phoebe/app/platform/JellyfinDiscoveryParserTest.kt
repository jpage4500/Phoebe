package com.phoebe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinDiscoveryParserTest {
    @Test
    fun mapsDiscoveryResponseToJellyfinServer() {
        val server = parseJellyfinDiscoveryServer(
            """
            {
              "Address": "http://192.168.1.20:8096",
              "Id": "server-id",
              "Name": "Studio Jellyfin"
            }
            """.trimIndent(),
        )

        assertEquals("jellyfin:server-id", server?.id)
        assertEquals("Studio Jellyfin", server?.name)
        assertEquals("http://192.168.1.20:8096", server?.uri)
        assertEquals(listOf("http://192.168.1.20:8096"), server?.localConnectionUris)
    }

    @Test
    fun prefersRespondingHostOverAdvertisedContainerAddress() {
        val server = parseJellyfinDiscoveryServer(
            """{"Address":"http://172.22.0.5:8096","Id":"server-id","Name":"dell","EndpointAddress":null}""",
            sourceHost = "192.168.0.160",
        )

        assertEquals("http://192.168.0.160:8096", server?.uri)
        assertEquals(listOf("http://192.168.0.160:8096", "http://172.22.0.5:8096"), server?.connectionUris)
    }
}
