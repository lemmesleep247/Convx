package com.convx.music.lyrics

import android.content.Context
import com.convx.music.constants.EnableBetterLyricsKey
import com.convx.music.constants.EnableKugouKey
import com.convx.music.constants.EnableLrcLibKey
import com.convx.music.constants.EnableMusixmatchKey
import com.convx.music.constants.EnablePaxsenixKey
import com.convx.music.constants.EnableSimpMusicKey
import com.convx.music.constants.EnableUnisonKey
import com.convx.music.constants.EnableYouLyPlusKey
import com.convx.music.constants.PreferredLyricsProvider
import com.convx.music.utils.dataStore
import com.convx.music.utils.get

object LyricsProviderSettings {
    fun idForLegacyPreference(provider: PreferredLyricsProvider): String = when (provider) {
        PreferredLyricsProvider.LRCLIB -> "LrcLib"
        PreferredLyricsProvider.KUGOU -> "Kugou"
        PreferredLyricsProvider.BETTER_LYRICS -> "BetterLyrics"
        PreferredLyricsProvider.SIMPMUSIC -> "SimpMusic"
        PreferredLyricsProvider.YOULYPLUS -> "YouLyPlus"
        PreferredLyricsProvider.PAXSENIX -> "Paxsenix"
    }

    fun isEnabled(context: Context, provider: LyricsProvider): Boolean = when (provider.id) {
        "BetterLyrics" -> context.dataStore[EnableBetterLyricsKey] ?: true
        "Kugou" -> context.dataStore[EnableKugouKey] ?: true
        "LrcLib" -> context.dataStore[EnableLrcLibKey] ?: true
        "Musixmatch" -> context.dataStore[EnableMusixmatchKey] ?: true
        "Paxsenix" -> context.dataStore[EnablePaxsenixKey] ?: true
        "SimpMusic" -> context.dataStore[EnableSimpMusicKey] ?: true
        "Unison" -> context.dataStore[EnableUnisonKey] ?: true
        "YouLyPlus" -> context.dataStore[EnableYouLyPlusKey] ?: true
        else -> true
    }

    fun initialize(context: Context, provider: LyricsProvider) {
        provider.initialize(context.applicationContext)
    }
}
