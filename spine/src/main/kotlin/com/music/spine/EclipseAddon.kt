package com.music.spine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI

/** Adapts Eclipse HTTP addons to the app's Spine module interface. */
object EclipseAddon {
    const val TYPE = "ECLIPSE"

    /** True when [body] is an Eclipse manifest rather than a Spine module index. */
    fun isManifest(json: Json, body: String): Boolean = runCatching {
        val obj = json.decodeFromString(JsonObject.serializer(), body)
        "resources" in obj && "id" in obj && obj.keys.none { it.startsWith("category:") }
    }.getOrDefault(false)

    /** Removes `/manifest.json` while preserving any token path segment. */
    fun baseUrl(manifestUrl: String): String =
        manifestUrl.substringBefore('?').removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")

    /** HTTPS only, except loopback/emulator hosts for addon development (per the spec). */
    fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" ||
            (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))
    }

    fun toModule(json: Json, manifestUrl: String, body: String): SpineModule {
        val m = json.decodeFromString(Manifest.serializer(), body)
        return SpineModule(
            id = m.id,
            name = m.name,
            version = m.version,
            description = m.description,
            type = TYPE,
            download = baseUrl(manifestUrl),
            icon = m.icon,
            declaredTags = m.types,
        )
    }

    fun searchUrl(base: String, query: String): String =
        "$base/search?q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    fun streamUrl(base: String, id: String): String =
        "$base/stream/" + java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    fun parseSearch(json: Json, body: String, limit: Int): ModuleSearchResponse {
        val r = json.decodeFromString(Search.serializer(), body)
        val tracks = r.tracks.take(limit).map {
            ModuleSearchResult(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album.orEmpty(),
                albumCover = it.artworkURL,
                duration = it.duration ?: 0,
                format = it.format.orEmpty(),
            )
        }
        return ModuleSearchResponse(tracks = tracks, total = tracks.size)
    }

    fun parseStream(json: Json, id: String, body: String): ModuleStreamResponse {
        val s = json.decodeFromString(Stream.serializer(), body)
        return ModuleStreamResponse(
            streamUrl = s.url,
            track = ModuleStreamTrack(
                id = id,
                audioQuality = s.quality.orEmpty(),
                format = s.format ?: s.container,
                codec = s.codec,
                bitDepth = s.bitDepth,
                sampleRate = s.sampleRate,
            ),
            streamType = s.manifest?.takeUnless { it == "none" },
            expiresAt = s.expiresAt,
        )
    }

    @Serializable
    internal data class Manifest(
        val id: String,
        val name: String,
        val version: String = "",
        val description: String = "",
        val icon: String? = null,
        val resources: List<String> = emptyList(),
        val types: List<String> = emptyList(),
    )

    @Serializable
    internal data class Search(val tracks: List<Track> = emptyList())

    @Serializable
    internal data class Track(
        val id: String,
        val title: String = "",
        val artist: String = "",
        val album: String? = null,
        val duration: Int? = null,
        @SerialName("artworkURL") val artworkURL: String? = null,
        val isrc: String? = null,
        val format: String? = null,
    )

    @Serializable
    internal data class Stream(
        val url: String? = null,
        val format: String? = null,
        val quality: String? = null,
        val expiresAt: Long? = null,
        val codec: String? = null,
        val container: String? = null,
        val manifest: String? = null,
        val sampleRate: Double? = null,
        val bitDepth: Int? = null,
    )
}
