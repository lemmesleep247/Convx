/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.lyrics

import android.content.Context
import android.util.LruCache
import com.convx.music.constants.LyricsProviderOrderKey
import com.convx.music.constants.PreferredLyricsProvider
import com.convx.music.constants.PreferredLyricsProviderKey
import com.convx.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.convx.music.extensions.toEnum
import com.convx.music.models.MediaMetadata
import com.convx.music.utils.NetworkConnectivityObserver
import com.convx.music.utils.dataStore
import com.convx.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
    private val providerRepository: LyricsProviderRepository,
) {
    /**
     * Resolves the ordered list of lyrics providers from the user's saved priority order.
     * Falls back to migrating the legacy [PreferredLyricsProvider] enum if the new order
     * preference has not been written yet, ensuring a smooth upgrade for existing users.
     */
    private suspend fun resolveLyricsProviders(): List<LyricsProvider> {
        val preferences = context.dataStore.data.first()
        val orderString = preferences[LyricsProviderOrderKey].orEmpty()

        if (orderString.isNotBlank()) {
            return LyricsProviderRegistry.getOrderedProviders(orderString)
        }

        // Use the legacy provider only when the user explicitly saved it; an unset key must keep
        // the LrcLib-first default instead of putting the slower YouLyPlus chain first.
        val preferredEnum = preferences[PreferredLyricsProviderKey]
            ?.let { it.toEnum(PreferredLyricsProvider.YOULYPLUS) }
            ?.let(LyricsProviderSettings::idForLegacyPreference)
            ?: return LyricsProviderRegistry.getDefaultProviderOrder()
                .mapNotNull(LyricsProviderRegistry::getProvider)

        val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
        val migratedOrder = listOf(preferredEnum) + defaultOrder.filter { it != preferredEnum }
        return migratedOrder.mapNotNull(LyricsProviderRegistry::getProvider)
    }

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null

    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        currentLyricsJob?.cancel()

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            val providerId = LyricsProviderRegistry.getProviderForStoredName(cached.providerId)?.id
                ?: cached.providerId
            return LyricsWithProvider(cached.lyrics, providerId)
        }

        if (!hasNetworkConnection()) {
            return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
        }

        val providers = resolveLyricsProviders().usableFor(isLocalSong(mediaMetadata.id))
        for (provider in providers) {
            if (!LyricsProviderSettings.isEnabled(context, provider)) continue

            try {
                val result = providerRepository.fetch(
                    provider,
                    mediaMetadata.id,
                    mediaMetadata.title,
                    mediaMetadata.artists.joinToString { it.name },
                    mediaMetadata.duration,
                    mediaMetadata.album?.title,
                )
                val lyrics = result.getOrNull()
                if (lyrics != null) return LyricsWithProvider(lyrics, provider.id)
                result.exceptionOrNull()?.let(::reportException)
            } catch (e: Exception) {
                reportException(e)
            }
        }

        return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }

        if (!hasNetworkConnection()) return

        val allResult = mutableListOf<LyricsResult>()
        val providers = resolveLyricsProviders().usableFor(isLocalSong(mediaId))
        currentLyricsJob = CoroutineScope(SupervisorJob()).launch {
            providers.forEach { provider ->
                if (LyricsProviderSettings.isEnabled(context, provider)) {
                    try {
                        LyricsProviderSettings.initialize(context, provider)
                        provider.getAllLyrics(mediaId, songTitle, songArtists, duration, album) { lyrics ->
                            val result = LyricsResult(provider.id, provider.displayName, lyrics)
                            allResult += result
                            callback(result)
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
            }
            cache.put(cacheKey, allResult)
        }

        currentLyricsJob?.join()
    }

    fun cancelCurrentLyricsJob() {
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    /** MediaStore content IDs cannot be used by providers that require a YouTube video ID. */
    private fun isLocalSong(id: String): Boolean = id.startsWith("content://")

    private fun hasNetworkConnection(): Boolean = try {
        networkConnectivity.isCurrentlyConnected()
    } catch (_: Exception) {
        // Proceed when the connectivity probe itself fails.
        true
    }

    private fun List<LyricsProvider>.usableFor(isLocalSong: Boolean): List<LyricsProvider> =
        if (!isLocalSong) this else filterNot(LyricsProvider::requiresVideoId)

    companion object {
        private const val MAX_CACHE_SIZE = 3
    }
}

data class LyricsResult(
    val providerId: String,
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)
