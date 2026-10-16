/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.lyrics

object LyricsProviderRegistry {
    private val providers = listOf(
        YouLyPlusLyricsProvider,
        PaxSenixLyricsProvider,
        MusixmatchLyricsProvider,
        UnisonLyricsProvider,
        BetterLyricsProvider,
        SimpMusicLyricsProvider,
        LrcLibLyricsProvider,
        KuGouLyricsProvider,
        YouTubeSubtitleLyricsProvider,
        YouTubeLyricsProvider,
    ).associateBy(LyricsProvider::id)

    private val legacyDisplayNames = providers.values.associate { it.displayName to it.id }

    val providerIds = providers.keys.toList()

    fun getProvider(id: String): LyricsProvider? = providers[id]

    fun getProviderForStoredName(value: String): LyricsProvider? =
        providers[value] ?: legacyDisplayNames[value]?.let(providers::get)

    fun deserializeProviderOrder(orderString: String): List<String> {
        if (orderString.isBlank()) return getDefaultProviderOrder()
        return orderString.split(",").map { it.trim() }.filter { it in providerIds }
    }

    fun serializeProviderOrder(providers: List<String>): String =
        providers.filter { it in providerIds }.joinToString(",")

    // LrcLib and Kugou lead: both are free, keyless, fast, and cover synced
    // lyrics for the widest audience (Kugou for CJK). The remote/aggregator
    // services follow; the YouTube-only pair stays last since it can only
    // serve videoId tracks.
    fun getDefaultProviderOrder(): List<String> = listOf(
        "LrcLib",
        "Kugou",
        "YouLyPlus",
        "Paxsenix",
        "Musixmatch",
        "Unison",
        "BetterLyrics",
        "SimpMusic",
        "YouTubeSubtitle",
        "YouTubeMusic",
    )

    fun getOrderedProviders(orderString: String): List<LyricsProvider> =
        deserializeProviderOrder(orderString).mapNotNull(::getProvider)

    fun getDisplayName(id: String): String = getProviderForStoredName(id)?.displayName ?: id
}
