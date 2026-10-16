package com.music.spine

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EclipseAddonTest {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun manifestBecomesOneModuleWithTokenisedBase() {
        val body = """{"id":"com.x.addon","name":"X","version":"1.0.0","resources":["search","stream"],"types":["track"]}"""
        assertTrue(EclipseAddon.isManifest(json, body))
        assertFalse(EclipseAddon.isManifest(json, """{"category:modules":[]}"""))
        val m = EclipseAddon.toModule(json, "https://a.io/tok123/manifest.json", body)
        assertEquals(EclipseAddon.TYPE, m.type)
        assertEquals("https://a.io/tok123", m.download)
        assertEquals("https://a.io/tok123/search?q=a%20b%26c", EclipseAddon.searchUrl(m.download, "a b&c"))
    }

    @Test
    fun searchAndStreamMapOntoModuleContract() {
        val s = EclipseAddon.parseSearch(json, """{"tracks":[{"id":"t1","title":"T","artist":"A","duration":240,"artworkURL":"u","format":"flac"}],"albums":[]}""", 8)
        assertEquals("t1", s.tracks.single().id)
        assertEquals(240, s.tracks.single().duration)
        assertEquals("u", s.tracks.single().albumCover)
        val st = EclipseAddon.parseStream(json, "t1", """{"url":"https://cdn/x.m3u8","codec":"flac","manifest":"hls","expiresAt":99}""")
        assertEquals("https://cdn/x.m3u8", st.streamUrl)
        assertEquals("hls", st.streamType)
        assertEquals("flac", st.track?.codec)
        assertEquals(99L, st.expiresAt)
        assertEquals(null, EclipseAddon.parseStream(json, "t1", """{"url":"u","manifest":"none"}""").streamType)
        assertTrue(EclipseAddon.isAllowedUrl("http://10.0.2.2:7000/manifest.json"))
        assertFalse(EclipseAddon.isAllowedUrl("http://evil.com/manifest.json"))
    }
}
