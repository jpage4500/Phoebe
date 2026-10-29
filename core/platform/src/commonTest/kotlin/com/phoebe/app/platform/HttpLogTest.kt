package com.phoebe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpLogTest {
    @Test
    fun sanitizeUrlRedactsSensitiveQueryParams() {
        assertEquals(
            "http://jf:8096/Audio/1/stream?static=true&api_key=***",
            HttpLog.sanitizeUrl("http://jf:8096/Audio/1/stream?static=true&api_key=abc123"),
        )
        assertEquals(
            "http://jf:8096/QuickConnect/Connect?secret=***",
            HttpLog.sanitizeUrl("http://jf:8096/QuickConnect/Connect?secret=s3cr3t"),
        )
        assertEquals(
            "http://nd/rest/ping?u=joe&t=***&s=***&f=json",
            HttpLog.sanitizeUrl("http://nd/rest/ping?u=joe&t=hash&s=salt&f=json"),
        )
        assertEquals(
            "http://plex/library?X-Plex-Token=***",
            HttpLog.sanitizeUrl("http://plex/library?X-Plex-Token=tok"),
        )
    }

    @Test
    fun sanitizeUrlLeavesUrlsWithoutSecretsAlone() {
        val url = "http://jf:8096/Users/AuthenticateByName"
        assertEquals(url, HttpLog.sanitizeUrl(url))
        assertEquals("http://jf/Items?limit=5", HttpLog.sanitizeUrl("http://jf/Items?limit=5"))
    }

    @Test
    fun redactHandlesFreeTextAndAuthorizationTokens() {
        assertEquals(
            "timeout for http://jf/Items/1/Download?api_key=*** after 60s",
            HttpLog.redact("timeout for http://jf/Items/1/Download?api_key=abc after 60s"),
        )
        assertEquals(
            """MediaBrowser Client="Phoebe", Token="***"""",
            HttpLog.redact("""MediaBrowser Client="Phoebe", Token="abc123""""),
        )
    }
}
